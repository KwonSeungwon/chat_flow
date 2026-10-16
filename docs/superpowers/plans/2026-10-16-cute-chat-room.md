# 아기자기한 채팅방 (카카오톡 구조 + 더 둥글게) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 채팅 화면(사이드바 방 목록, 방 헤더, 메시지 영역, 말풍선, 입력창)을 Gossiplog 로그인 화면과 같은 파스텔 컨셉으로 바꾼다. 레이아웃은 카카오톡을 따르되 모서리를 더 둥글게 한다.

**Design (사용자 요청 2026-10-16, 이번 작업은 사용자가 승인 생략을 허용):** 기준 화면 `.playwright-mcp/chatroom-before.png`(현재 어두운 Nebula), 참고 로그인 팔레트 `frontend/lib/features/auth/login_page.dart` 상단 상수(ink `#24304D`, inkSoft `#5B6788`, muted `#6B7896`, hint `#9AA6C2`, line `#E3E8F2`, blue `#6C8CFF`, blueDeep `#4F6EE6`, violet `#A07BFF`, 배경 그라데이션 `#EEF4FF → #FDF3E7`).

## Global Constraints

- 기능·동작·위젯 키·시맨틱 라벨은 바꾸지 않는다. 바꾸는 것은 색, 모양, 간격, 배치다. 메시지 메뉴, 리액션, 스레드, 파일, 멘션, 편집, 읽음 표시, 예약, 드래그 앤 드롭은 그대로 동작해야 한다.
- 기본 테마를 라이트로 바꾼다(`ThemeModeNotifier` 초기값과 폴백). 저장된 사용자 선택은 그대로 존중한다. 다크 토글은 유지하고 다크에도 같은 모양(둥근 말풍선·스쿼클 아바타)을 적용한다.
- 색은 `AppColors`/`ColorScheme` 토큰으로 모은다. 채팅 위젯에 새 하드코딩 색을 흩뿌리지 않는다.
- `docs/superpowers/**` 이전 문서는 손대지 않는다. 시크릿 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- 검증: `flutter analyze` 신규 이슈 0(기준 60), `flutter test` 전부 PASS, `flutter build web --release` 성공, 넓은 폭(1200×800)과 좁은 폭(400×800) 위젯 스크린샷을 scratchpad에 저장해 보고.

## Rulings

- Ruling: 내 말풍선은 카카오톡처럼 노란색 계열(파스텔 버터 `#FFE68A` 근처, 글자 ink `#24304D`), 상대 말풍선은 흰색 + 아주 옅은 그림자. 다크에서도 내 말풍선은 같은 노랑, 상대는 `#2A2F45` 근처. — 카카오톡 인지도. 비용: 브랜드 보라와 대비가 약하면 색만 바꾸면 된다.
- Ruling: 강조색(전송 버튼, 선택된 방, 배지 외 액션)은 로그인 버튼과 같은 blue→violet 그라데이션. 안 읽은 수 배지는 카카오톡처럼 코랄 레드 `#FF6B5B` 근처 알약.
- Ruling: AI 기능 제거 때 남은 `isAiQuestion`·"To AI" 분기는 도달할 수 없는 잔재라 이번에 지운다(이 브랜치가 고치는 말풍선 파일 안에 있고 제거한 기능의 흔적).
- Ruling: `web/manifest.json` theme/background color를 라이트 기본값(`#EEF4FF`)으로 바꾸고 `manifest_test.dart` 기대값을 맞춘다 — 기본 테마가 라이트로 바뀌어 이 계약의 전제가 바뀜.

---

### Task 1: 파스텔 테마 + 카카오톡형 채팅 화면

- [ ] 테마: `app_theme.dart` 라이트 ColorScheme을 로그인 팔레트로 다시 잡는다(scaffold 배경 흰색에 가까운 `#F7F9FE`, surface 흰색, outline `#E3E8F2`, primary `#6C8CFF`, onSurface ink). `AppColors`에 채팅 토큰을 추가한다: 채팅방 배경(라이트 `#EEF4FF→#FDF3E7` 은은한 세로 그라데이션 또는 단색 `#E8F0FB`, 다크 `#161A26`), 내 말풍선/글자, 상대 말풍선/글자, 안 읽음 배지, 강조 그라데이션. 다크 ColorScheme은 남색-라벤더로 살짝 부드럽게 하되 구조는 유지. 공통 모양: 입력·카드·버튼 radius 18~24, 다이얼로그 24, 바텀시트 28.
- [ ] 기본 테마 라이트: `theme_provider.dart` 초기값과 `orElse` 폴백을 `ThemeMode.light`로.
- [ ] 말풍선(`chat_bubble.dart`, `bubble_footer.dart`, `bubble_header_decorations.dart`, `file_bubble.dart`, `system_bubble.dart`, `avatar.dart`):
  - 카카오톡 배치: 상대 메시지는 왼쪽에 스쿼클 아바타(40px, 모서리 반경 ≈ 14), 그룹 첫 메시지 위에 이름(작은 inkSoft), 연속 메시지는 아바타 자리 비움(기존 그룹핑 로직 재사용). 내 메시지는 오른쪽, 아바타·이름 없음.
  - 시간과 읽음 수는 말풍선 바깥 아래쪽 옆(내 메시지는 왼쪽, 상대는 오른쪽)에 작은 글씨로. 읽음 숫자는 강조색.
  - 말풍선 radius 20, 꼬리 쪽 위 모서리만 6(내 것은 오른쪽 위, 상대는 왼쪽 위). 패딩 넉넉히(가로 14, 세로 10).
  - 인용·전달·멘션 하이라이트 색을 새 말풍선 색 위에서 읽히도록 맞춘다(노란 배경 위 ink 글자 기준).
  - 날짜 구분선과 시스템 메시지는 반투명 흰 알약(다크는 반투명 검정).
- [ ] 메시지 영역(`chat_messages_list.dart`, `chat_room_content.dart`): 배경을 채팅방 배경 토큰으로. 빈 상태 아이콘·문구도 파스텔.
- [ ] 입력창(`chat_input.dart`): 흰 하단 바, 둥근 알약 입력칸(radius 24), 첨부·이모지 원형 버튼, 전송 버튼은 원형 강조 그라데이션(비활성은 연회색).
- [ ] 방 헤더(`chat_page.dart`/`chat_room_content.dart`의 AppBar 영역): 흰 배경, 방 이름 ink 굵게, 아이콘 inkSoft.
- [ ] 사이드바(`chat_room_sidebar.dart`, `room_tile.dart`, `sidebar_header.dart`, `lobby_placeholder.dart`): 흰 배경, 방 타일 radius 18, 스쿼클 방 아바타, 선택된 방은 옅은 파랑 배경(`#EEF3FF`)과 둥근 모서리(왼쪽 세로 막대 제거), 안 읽음 배지 코랄 알약, 마지막 메시지 미리보기·시간 색은 muted.
- [ ] 스레드 패널·다이얼로그는 테마 토큰만으로 자연스럽게 따라오게 하고 하드코딩 다크 색이 있으면 토큰으로 바꾼다.
- [ ] 잔재 제거: `isAiQuestion`·"To AI" 분기와 그 인자(호출부 포함).
- [ ] manifest 색과 `manifest_test.dart`를 Ruling대로.
- [ ] 테스트: 색·모양 변경으로 깨지는 기존 테스트는 의도를 유지한 채 고친다. 새 테스트: 기본 테마 라이트, 내 말풍선이 노랑 토큰을 쓰는지, 상대 메시지 그룹 첫 줄에만 아바타·이름이 보이는지, 400px 폭 overflow 없음.
- [ ] 스크린샷: 위젯 테스트(골든 아님, `matchesGoldenFile` 대신 `RepaintBoundary.toImage` 등으로 PNG 저장)로 라이트·다크 채팅방(내/상대 메시지, 연속 메시지, 시스템 메시지, 날짜선, 입력창, 사이드바) 1200×800, 400×800을 scratchpad에 저장.
- [ ] `flutter analyze`·`flutter test`·`flutter build web --release` → Commit `feat(frontend): pastel KakaoTalk-style rounded chat room`.

### 배포

develop 머지 → develop-build → frontend 태그 고정 → helm upgrade → CF 퍼지 → 브라우저 E2E(채팅방 화면, 송수신, 다크 토글). APK는 pubspec 3.0.1+3으로 메인 체크아웃에서 다시 빌드.
