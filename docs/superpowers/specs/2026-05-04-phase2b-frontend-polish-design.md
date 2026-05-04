# Phase 2B — Frontend Polish — Design Spec

- **Date**: 2026-05-04
- **Status**: Draft
- **Phase**: Phase 2 of 3, Cycle B (frontend 폴리시)
- **Owner**: seungwon
- **Goal**: 데스크톱 웹 / 모바일 웹에서 "다른 앱처럼 자연스럽다"는 느낌을 만드는 폴리시 묶음 — 드래그-드롭/클립보드, 명령 팔레트, PWA 강화. 백엔드 변경 없음.

---

## 1. Problem & Motivation

ChatFlow는 데스크톱에서 마우스/키보드 사용자가, 모바일에서 터치 사용자가 자주 마주치는 "당연한" UX가 일부 빠져있다.

- **드래그-드롭 / 클립보드**: 데스크톱 사용자가 이미지를 붙여넣거나 파일을 끌어다 놓을 수 없어 매번 file picker 클릭.
- **검색/이동의 비효율**: 방을 찾거나 사용자를 찾을 때마다 사이드바를 스크롤해야 한다. 키보드로 빠르게 이동하는 진입점이 없음.
- **모바일 웹의 어색함**: PWA로 설치해도 standalone 느낌이 약하고, safe-area / 가상 키보드가 입력창을 가리는 등 사소한 마찰이 잔존.

이 사이클은 "당연히 되어야 할 것들"을 채워 사용자 만족도를 끌어올리는 게 목적이다.

---

## 2. Scope

### In scope
- **드래그-드롭 + 클립보드** (데스크톱 웹 — 모바일은 무관):
  - 채팅 입력 영역에 이미지 파일 끌어 놓으면 업로드 → 메시지 발송 (기존 `onFilePick` 재활용)
  - Ctrl/Cmd+V 클립보드에 이미지 → 동일 흐름
- **명령 팔레트 (Cmd+K / Ctrl+K)**:
  - 글로벌 단축키 → `CommandPaletteOverlay`
  - 액션 카테고리:
    - 방 이동 (검색 매칭, 최근 방 우선)
    - 사용자 찾기 → 미리보기 다이얼로그 또는 DM 시작 (DM 시작은 Phase 3로 미룸 — 미리보기까지만)
    - 빠른 액션: 새 방 만들기, 검색 페이지로, 로그아웃, 테마 토글
  - 키보드 네비게이션 (↑↓, Enter, Esc) — Phase 3 a11y의 베이스
- **PWA / 모바일 웹 강화**:
  - `manifest.json` 정비 (display: standalone, theme_color, icons 일관)
  - safe-area inset 처리 (iOS notch 대응)
  - 가상 키보드 등장 시 입력창 가림 해결 (`MediaQuery.of(context).viewInsets.bottom` 활용)
  - 모바일 사이드바 pull-to-refresh (방 목록 새로고침)

### Out of scope
- 명령 팔레트로 DM 시작 (Phase 3)
- 검색 필터 (사람/날짜/타입) — Phase 3
- a11y 베이스라인 (ARIA / 색대비 / 스크린리더) — Phase 3
- swipe back gesture (모바일 웹 한정 — 브라우저 native가 이미 지원하므로 의도적으로 제외)
- 백엔드 변경 일체 없음

---

## 3. Architecture

### 3.1 드래그-드롭 + 클립보드
- **`chat_input.dart`** 내부에 `Listener` (paste 이벤트) + Flutter Web `dart:html` (조건부 import) drag/drop 핸들러 추가
- 또는 더 간단히: Flutter `dart:ui_web` API 사용 가능 여부 확인. 없으면 conditional import 패턴 (`platform_drop_io.dart` / `platform_drop_web.dart`)
- 입력 시점에 이미지 bytes 추출 → 기존 `widget.onFilePick(fileName, bytes, mimeType, content="")` 호출

### 3.2 명령 팔레트
- **신규 위젯**: `frontend/lib/features/command_palette/command_palette_overlay.dart`
  - `Overlay` + `Shortcuts` + `Actions` (Flutter 표준)
  - `MaterialApp.shortcuts` 또는 globalShortcuts에 Cmd+K / Ctrl+K 등록
- **데이터 소스**:
  - `chatRoomsProvider` 활용 (이미 있는 방 목록)
  - `/api/users/search` (gateway-service에 이미 존재) — 사용자 검색
  - 빠른 액션은 정적 리스트
- **검색 필터링**: substring 매칭, 점수 순 정렬

### 3.3 PWA
- `frontend/web/manifest.json` 갱신
- `frontend/web/index.html` viewport meta 점검 (`viewport-fit=cover` for safe-area)
- 본문 위젯 트리에 `SafeArea` 적용 (이미 있다면 보강)
- `chat_input.dart`의 `Padding` → `viewInsets.bottom` 추가
- 사이드바 `RefreshIndicator` wrap

---

## 4. Data Model

새 모델 없음. 기존 데이터 구조 재활용.

명령 팔레트 내부에서 액션 타입을 표현하는 sealed-like enum:

```dart
sealed class CommandAction {
  String get title;
  String? get subtitle;
  IconData get icon;
  void execute(BuildContext context, WidgetRef ref);
}
class GoToRoomAction extends CommandAction {...}
class ViewProfileAction extends CommandAction {...}
class QuickAction extends CommandAction {...}  // 새 방, 검색, 로그아웃 등
```

---

## 5. UI / 사용자 흐름

### 5.1 드래그-드롭
- 데스크톱 웹에서 채팅 입력 영역에 파일 hover → 점선 박스 + "여기에 놓아 업로드" 안내
- 파일 drop 또는 paste → 즉시 업로드 진행 인디케이터 → 발송
- 이미지가 아닌 파일은 기존 onFilePick 흐름으로 처리 (확장자 검증은 onFilePick에서 함)

### 5.2 명령 팔레트
- Cmd+K (mac) / Ctrl+K (win/linux) → 화면 중앙 오버레이 모달
- 검색 input + 결과 리스트 (max 8개)
- 카테고리:
  - "방" (대화방)
  - "사용자" (검색 결과)
  - "액션" (정적 빠른 액션)
- 결과 항목: leading icon + title + subtitle
- ↑↓ 키 네비게이션, Enter 실행, Esc 닫기

### 5.3 PWA
- iPhone Safari "홈 화면에 추가" 후 standalone 실행 시 ChatFlow가 native앱처럼 보이도록 manifest 정비
- 모바일에서 메시지 입력 시 키보드가 입력창을 가리지 않음
- 사이드바를 아래로 끌어당기면 방 목록 새로고침

---

## 6. Tests

### 6.1 단위/위젯 테스트
- `command_palette_overlay_test.dart`:
  - 검색어 입력 → 매칭 결과 노출
  - ↑↓ 화살표로 선택 이동
  - Enter → 액션 콜백 호출
  - Esc → 닫힘
- `chat_input_drop_test.dart` (가능한 범위):
  - 클립보드 paste 이벤트 시뮬레이션 → onFilePick 호출 검증
  - drop을 직접 시뮬레이션하기는 어려우므로 helper 함수 단위 테스트로 대체
- PWA 관련은 위젯 테스트보단 manifest.json + viewport meta 정적 검증

### 6.2 E2E 수동 검증
1. 데스크톱 Chrome — 이미지 파일을 채팅 입력에 드래그 → 업로드 + 발송
2. Cmd+K → "땅콩" 입력 → 사용자 결과 → Enter → 미리보기 다이얼로그
3. iPhone Safari — 홈 추가 → standalone 실행 → 메시지 입력 시 키보드 안 가림
4. 모바일 사이드바 pull-down → 방 목록 새로고침

---

## 7. Migration / Rollout

백엔드 변경 없음. frontend 이미지만 재빌드.

---

## 8. 판단 근거

| 결정 | 대안 | 채택 이유 |
|---|---|---|
| 명령 팔레트는 새 페이지 X / Overlay만 | 별도 라우트 | 모달 UX가 표준 (VS Code, Linear, Slack), 단축키 + 닫기로 충분 |
| 사용자 클릭 시 미리보기까지만 (DM 시작 X) | DM 자동 생성 | DM 흐름은 별도 토픽 — Phase 3로 |
| swipe back은 의도적으로 빼기 | 자체 구현 | 모바일 브라우저 기본 동작에 의존 — 자체 구현은 마찰만 |
| 클립보드/드롭 이미지는 onFilePick 재활용 | 별도 흐름 | 검열/검증/메시지 발송 모두 기존 검증된 로직 통과 |

---

## 9. Success Criteria

- [ ] 데스크톱 Chrome — 이미지 드래그-drop, Ctrl+V 페이스트 모두 업로드/발송 동작
- [ ] Cmd+K (mac) / Ctrl+K (win) — 어디서든 즉시 오버레이 등장, 키보드 네비 정상
- [ ] iPhone Safari standalone — 가상 키보드 등장 시 입력창 가시
- [ ] 모바일 사이드바 pull-to-refresh → 새 방 목록 fetch
- [ ] manifest.json validator 통과 (필수 필드 충족)
- [ ] frontend 전체 test green
- [ ] flutter analyze 경고 0 (T7/T8 폴리시 도중 발생한 minor도 같이 정리)

---

## End of Spec
