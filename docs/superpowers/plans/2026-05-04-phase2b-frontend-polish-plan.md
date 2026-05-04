# Phase 2B — Frontend Polish — Implementation Plan

- **Date**: 2026-05-04
- **Spec**: `docs/superpowers/specs/2026-05-04-phase2b-frontend-polish-design.md`
- **Total Tasks**: 3 (각각 subagent에 단독 디스패치 가능)
- **Backend changes**: 없음

---

## Task graph

```
T1 (드래그-드롭 + 클립보드) — chat_input.dart 수정
   ↓ (독립, T2/T3와 병렬 가능하나 순차 진행)
T2 (명령 팔레트) — overlay + actions + 단축키 + main.dart 통합
   ↓
T3 (PWA 폴리시) — manifest + safe-area + 키보드 + pull-to-refresh
```

T1 ~ T3 모두 frontend 단독, 동일 파일 충돌 없음 — 그럼에도 쉽게 추적하기 위해 순차 dispatch.

---

## Task T1 — 드래그-드롭 + 클립보드 이미지 붙여넣기

### 컨텍스트
ChatFlow 데스크톱 웹에서 사용자가 이미지를 채팅 입력 영역에 끌어다 놓거나, 스크린샷을 클립보드에서 붙여넣는 기능 부재. 기존 `onFilePick(fileName, bytes, mimeType, content)` 콜백이 있어 재활용 가능.

### Files to create
- `frontend/lib/features/chat/widgets/drop_zone_overlay.dart`
  - 입력 영역 위에 표시되는 드래그-오버 비주얼 (점선 박스 + "여기에 놓기" 안내)
- `frontend/lib/features/chat/helpers/clipboard_paste_handler.dart`
  - 플랫폼 독립 헬퍼: `Future<({String name, Uint8List bytes, String mimeType})?> readClipboardImage()`
  - Web: `package:web` 또는 `dart:html` clipboard API
  - Native: `Clipboard.getData(Clipboard.kTypePlainText)` (이미지 미지원 시 null)

### Files to modify
- `frontend/lib/features/chat/widgets/chat_input.dart`
  - 입력 영역을 `Listener` (`onPointerDown`)와 `KeyboardListener` (Ctrl+V) 또는 `Shortcuts(LogicalKeySet(LogicalKeyboardKey.control, LogicalKeyboardKey.keyV))`로 wrap
  - Web 드래그-드롭: `_DropTarget` 헬퍼 (kIsWeb 분기)
  - drop / paste 이벤트 발생 시:
    1. 이미지 bytes 추출
    2. 파일명/MIME 추론
    3. `widget.onFilePick(...)` 호출
  - 드래그 hover 시 `DropZoneOverlay` 노출 (Stack overlay)

### Tests
- `frontend/test/features/chat/helpers/clipboard_paste_handler_test.dart`
  - native fallback이 null 반환하는지
  - mock Web binding으로 image MIME 분기 검증
- `frontend/test/features/chat/widgets/drop_zone_overlay_test.dart`
  - 표시/숨김 상태 시각 차이

### Acceptance
- 데스크톱 Chrome에서 이미지 파일을 채팅 입력 영역에 drop → 업로드 인디케이터 → 발송 (E2E 수동)
- Ctrl+V (또는 Cmd+V on macOS) 클립보드 이미지 → 동일 흐름
- 비-이미지 drop은 무시 (검증 메시지 없이 silent)
- `flutter analyze` 경고 0
- 위 단위 테스트 green

### 주의
- Flutter Web에서 drag/drop은 default로 브라우저가 페이지 navigation을 트리거할 수 있음 — `preventDefault` 필수
- conditional import 패턴 (`_io.dart` / `_web.dart`) 활용

---

## Task T2 — 명령 팔레트 (Cmd+K / Ctrl+K)

### 컨텍스트
키보드 우선 사용자에게 빠른 진입점 제공. VS Code / Slack 식 모달 오버레이.

### Files to create
- `frontend/lib/features/command_palette/command_palette_overlay.dart`
  - `Future<void> showCommandPalette(BuildContext context)` — `showDialog` 또는 `OverlayEntry`
  - 내부: `TextField` (autofocus) + `ListView` of matched commands
  - ↑↓ 키 네비, Enter 실행, Esc 닫기 (`FocusNode` + `Shortcuts`/`Actions`)
- `frontend/lib/features/command_palette/command_action.dart`
  - sealed class `CommandAction` + 3 subtypes
  - `GoToRoomAction`, `ViewProfileAction`, `QuickAction`
  - 각자 `String matchScore(String query)` 메서드 (substring 점수)
- `frontend/lib/features/command_palette/command_palette_provider.dart`
  - 검색어 → 결과 리스트 변환 로직
  - 방 검색: `chatRoomsProvider` watch
  - 사용자 검색: 기존 `/api/users/search` Dio 호출 (debounce 200ms)
  - 빠른 액션: 정적

### Files to modify
- `frontend/lib/main.dart`
  - `MaterialApp.shortcuts`에 `LogicalKeySet(meta/control + keyK)` 등록
  - `MaterialApp.actions`에 `OpenCommandPaletteIntent` → `Action` 매핑
  - 또는 `CallbackShortcuts`로 wrap

### Tests
- `frontend/test/features/command_palette/command_action_test.dart`
  - matchScore 정렬 검증
- `frontend/test/features/command_palette/command_palette_overlay_test.dart`
  - 검색어 입력 → 결과 노출
  - ↑↓로 선택 이동
  - Enter → execute 호출
  - Esc → Navigator.pop

### Acceptance
- Cmd+K / Ctrl+K → 모달 즉시 등장
- "땅" 입력 → "땅콩" 사용자 + "땅콩-DM" 방 모두 표시
- ↑↓ + Enter로 키보드만으로 이동/실행
- Esc 닫힘
- `flutter analyze` 경고 0
- 위 위젯 테스트 green

### 주의
- 디바운스가 사용자 검색에 적용되었는지 확인 (1글자마다 API 호출 X)
- 명령 팔레트가 다른 다이얼로그(모달) 위에 떠야 한다면 `Overlay` 직접 사용
- 우선순위: 매칭 점수 + 카테고리 (방/사용자/액션 순)

---

## Task T3 — PWA / 모바일 웹 강화

### 컨텍스트
모바일 웹 / 홈 화면 추가 시 native-like UX 보강. iOS notch + Android 가상 키보드 + pull-to-refresh.

### Files to modify
- `frontend/web/manifest.json` 정비:
  - `display: "standalone"` 확인
  - `theme_color`, `background_color` 일관 (앱 다크 색상)
  - icons 192/512 모두 존재 확인
  - `start_url: "."`, `scope: "/"`
- `frontend/web/index.html`:
  - `<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">` (notch 안전영역)
  - `<meta name="apple-mobile-web-app-capable" content="yes">` (이미 있음)
  - 추가: `<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">` (이미 있음)
- `frontend/lib/features/chat/widgets/chat_input.dart`:
  - 가장 바깥 `Padding` 또는 `Container`에 `EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom)` 추가 — 가상 키보드 가림 방지
  - 또는 Scaffold 레벨에서 `resizeToAvoidBottomInset: true` 확인 (이미 true이지만 검증)
- `frontend/lib/features/chat/widgets/chat_room_sidebar.dart`:
  - 방 목록 영역을 `RefreshIndicator`로 wrap → onRefresh: `ref.invalidate(chatRoomsProvider)`
- `frontend/lib/main.dart` 또는 chat_page Scaffold 본문:
  - `SafeArea` 누락 영역 점검 (사이드바 / AppBar 포함 여부 확인)

### Tests
- 위젯 테스트 추가 어려움 (PWA + safe-area는 디바이스 의존)
- 정적 검증: `manifest.json`이 valid JSON + 필수 필드 존재 확인 (간단한 단위 테스트)
- pull-to-refresh: `RefreshIndicator` 위에서 swipe down 시뮬레이션 → onRefresh 호출 검증

### Acceptance
- iPhone Safari 홈 추가 → standalone 실행 → 상단 notch 색 자연스러움
- 모바일에서 메시지 입력 → 가상 키보드 등장 → 입력창 보임 (가려지지 않음)
- 모바일 사이드바 pull-down → spinner → 방 목록 새로고침
- `manifest.json` valid
- `flutter analyze` 경고 0

### 주의
- PWA는 빌드 후 standalone에서 직접 검증해야 — 위젯 테스트로 완전히 커버 못 함, 수동 QA 필수
- safe-area는 기존 코드에 이미 일부 적용되어 있을 수 있음 — 빠진 구역만 보강

---

## Final QA

3개 task 완료 후:
1. `flutter analyze` 경고 0 (전체)
2. `flutter test` green
3. 수동:
   - 데스크톱: drag-drop, Ctrl+V, Cmd+K
   - 모바일 Safari: standalone, 키보드 가림 없음, pull-to-refresh
4. 빌드 + 배포 (frontend만)
5. Cloudflare 캐시 퍼지

---

## Risk

| 리스크 | 완화 |
|---|---|
| Flutter Web drag/drop API 빈약 / dart:html 의존 | conditional import 패턴 + kIsWeb 가드 |
| Cmd+K shortcut이 OS/브라우저 기본 단축키와 충돌 | 일반적으로 사용 안 됨 (Slack, VS Code도 같은 키) |
| iOS PWA standalone 캐시 이슈 | manifest 변경 후 사용자에게 "홈에서 삭제 후 재추가" 안내 필요 — 새 사용자는 무관 |
| 모바일 키보드 viewInsets 처리 시 다른 위젯 레이아웃 깨짐 | 영향 범위가 chat_input 한 군데로 국소화 |

---

## End of Plan
