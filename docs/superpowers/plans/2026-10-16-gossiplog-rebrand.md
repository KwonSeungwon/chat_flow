# Gossiplog 리브랜딩 + 병원 컨셉 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 병원용 채팅 컨셉(직군 role, 방 허용 역할, 인수인계방·SBAR, 환자 카드, 긴급도)을 프론트·백엔드·주석·설정·문서에서 모두 걷어내고, 앱을 "채팅 + 위키 던전 게임" Gossiplog로 리브랜딩한다(새 로그인 화면, C안 아이콘).

**Design (사용자 승인 2026-10-16):** 시안 `.superpowers/brainstorm/40677-1792139190/content/icon-login.html` — 아이콘 C안(떠 있는 파스텔 섬 + 성 + 흰 말풍선, 하늘→크림 그라데이션 배경), 로그인 시안(왼쪽 히어로: 파스텔 그라데이션, 앱 이름, "친구랑 떠들다가, 같이 던전 깨러 가요.", 태그 💬 실시간 채팅/🏰 위키 던전/🎓 졸업까지 8개 건물, 미니 아이소 마을 일러스트와 "던전 갈 사람? 🙋" 말풍선 / 오른쪽: 로그인·회원가입 탭, 아이디·비밀번호만, 보라 그라데이션 "들어가기" 버튼, Android 앱 받기 / 모바일은 세로로 쌓음). 앱 이름 Gossiplog.

**Removal map (줄 번호):** `.superpowers/notes/medical-removal-map.md`

## Global Constraints

- 유지: 방 역할 `RoomRole` OWNER/MODERATOR/MEMBER와 `room_members.role`, 방 관리·신고·밴·뮤트, 게임 관리자 `GAME_ADMIN_USER_IDS`, 멘션, 예약, 파일, FCM, 위키 던전.
- DB 컬럼 `users.role`, `chat_rooms.allowed_roles`, `chat_messages.priority`는 이번 릴리스에서 **지우지 않는다**(롤링 업데이트 중 옛 파드가 읽음). 매핑과 사용만 없앤다. 컬럼 DROP은 후속 릴리스.
- 옛 클라이언트 호환: 서버는 요청의 `role`, `allowedRoles`, `priority`, `roomType: HANDOFF`, `type: PATIENT_CARD`를 무시하거나 400으로 거절하되 500을 내지 않는다(unknown JSON 속성 무시, roomType은 GENERAL만 허용하고 그 외는 GENERAL로 처리).
- 코드 패키지명 `com.chatflow`, 리포·helm 릴리스·네임스페이스·이미지 이름(`chatflow`)은 바꾸지 않는다. 사용자에게 보이는 이름만 Gossiplog.
- 병원 관련 주석·문자열·테스트 이름(예: "김간호사" → "김철수")도 바꾼다. 역사 문서 `docs/superpowers/**`는 손대지 않는다.
- 검증: `./gradlew build` EXIT 0, `flutter analyze` 신규 0, `flutter test` PASS, `flutter build web --release` 성공.
- 시크릿 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Rulings

- Ruling: 긴급도(priority ROUTINE/URGENT/STAT)는 인수인계 전용 의료 기능이라 API·UI에서 제거한다 — 비용: 다시 필요하면 재구현.
- Ruling: V20은 데이터 정규화만 한다 — HANDOFF 방 → GENERAL(색 `#00796B`이면 기본색 NULL 또는 앱 기본값), `allowed_roles` → NULL, PATIENT_CARD 메시지와 SBAR JSON 메시지(content가 `{"type":"SBAR"` 로 시작) DELETE(운영 데이터가 극소량이고 원문 JSON 노출을 막기 위해), 관련 `message_mentions`/리액션 등 FK 정리. 비용: 옛 의료 카드 메시지 영구 삭제(백업 있음).

---

### Task 1: chat-service·common 의료 기능 제거 + V20

- [ ] allowedRoles 제거(엔티티 매핑, 서비스, 컨트롤러 record, `ChatRoomResponse`, CreateRoomRequest, 매퍼).
- [ ] `RoomType.HANDOFF` 제거. 컨트롤러는 roomType 입력을 GENERAL만 받고 나머지(HANDOFF 포함)는 GENERAL로 처리(400·500 없음). DIRECT 생성 경로는 기존 그대로.
- [ ] `MessageType.PATIENT_CARD` 제거, `InboundChatCommand.ALLOWED_TYPES = {CHAT, FILE}`, PATIENT_CARD로 보내면 기존 거절 경로(오류 프레임)로 간다.
- [ ] priority 제거(BaseMessage, ChatMessageResponse, ChatMessageEntity 매핑, InboundChatCommand, RoomMessageController).
- [ ] V20__drop_medical_concepts_data.sql: 위 Ruling의 정규화. FK(멘션·리액션·스레드 parent·편집 이력·신고 등)를 확인해 삭제 순서를 맞추고, FlywayMigrationTest를 20으로 올리며 V20 검사(HANDOFF→GENERAL, PATIENT_CARD 삭제) 추가.
- [ ] 주석·테스트 정리(MuteGate PATIENT_CARD 테스트 → FILE 등 다른 사용자 작성 타입, VisibleRooms HANDOFF 픽스처 → GENERAL, RoomPermissionServiceTest handoff → GENERAL, ChatRoomMapperTest allowedRoles 제거, MentionExtractorTest 김간호사 → 김철수). 회귀 테스트: roomType "HANDOFF" 요청 → GENERAL 방 생성, 요청 JSON의 allowedRoles·priority는 무시.
- [ ] `./gradlew :common:build :chat-service:build` → Commit `refactor(chat): remove medical concepts (allowed roles, handoff, patient card, priority)`.

### Task 2: gateway 직군(role) 제거

- [ ] AuthService·JwtUtil·UserEntity·ProfileResponse에서 role 제거(JWT `role` claim 발급 중단, `getRole` 삭제). `AuthRequest`는 role 필드를 없애고 unknown 속성(옛 클라이언트의 role)을 무시한다. 응답에서 role 제거.
- [ ] `schema.sql`: 신규 DB용 CREATE TABLE에서 role 컬럼 제거. 기존 운영 DB는 컬럼이 남아 있고 DEFAULT 'NURSE'라 INSERT가 계속 성공함을 테스트나 주석으로 확인한다(DROP은 후속).
- [ ] 테스트 정리(AuthServiceTest, AuthBlacklistTest, ProfileControllerTest). 옛 클라이언트가 role을 보내도 가입이 성공하는 테스트 추가.
- [ ] `./gradlew :gateway-service:build` → Commit `refactor(gateway): drop medical job role from auth and profile`.

### Task 3: 프론트 의료 기능 제거 (login_page.dart는 Task 4 소유)

- [ ] 직군: auth_provider·storage_keys·dio_client·user_profile에서 role 제거(옛 secure storage 키는 로그아웃 정리 목록에서 계속 지운다).
- [ ] 방: chat_room `allowedRoles`·`isHandoff`, chat_rooms_provider, create_room_dialog의 인수인계 토글·허용 역할 칩·청록색, room_tile SBAR 배지.
- [ ] SBAR·환자 카드·긴급도: removal map의 파일 삭제와 편집(chat_input 인수인계 도구 메뉴·긴급도 토글, chat_messages_list 분기, chat_bubble·bubble_header_decorations 긴급 색/배지, thread_panel, message_send_helper, chat_notifier, chat_message 모델 priority).
- [ ] 키워드 알림 힌트 "예: 환자명, 약명, STAT" → 중립 예시("예: 점심, 회의, 던전"). mention_spans 주석·테스트 "김간호사" → "김철수".
- [ ] 테스트 정리(removal map 목록). `flutter analyze` 신규 0, `flutter test`, web build → Commit `refactor(frontend): remove medical roles, handoff, SBAR, patient card and priority`.

### Task 4: Gossiplog 리브랜딩 — 로그인 화면·아이콘·이름

- [ ] 아이콘: `frontend/assets/icon_source.svg`(1024 정사각, 둥근 사각 배경 없이 풀블리드 그라데이션 — 플랫폼이 마스킹)와 `icon_maskable.svg`(안전 영역 80% 안에 섬+말풍선)를 C안으로 새로 그린다. 파생 PNG를 다시 만든다: `assets/app_icon.png`, `web/favicon.png`, `web/icons/Icon-{192,512}.png`, `Icon-maskable-{192,512}.png`, Android `mipmap-*/ic_launcher*.png`(+ adaptive icon이 있으면 foreground/background), iOS `AppIcon.appiconset`(있으면). 변환은 로컬 도구(rsvg-convert/ImageMagick/inkscape 중 있는 것, 없으면 Flutter 테스트로 렌더 또는 Python+cairosvg)로 하고 방법을 보고한다. 24px 파비콘에서도 섬과 말풍선 윤곽이 보이는지 확인.
- [ ] 이름: Android `android:label`, `web/index.html` `<title>`·meta description("채팅하고 같이 던전 깨는 Gossiplog" 류, AI 문구 제거)·apple-mobile-web-app-title, `web/manifest.json` name/short_name/description/theme·background color, MaterialApp `title`, 앱 안의 "ChatFlow" 표시 문자열(사이드바 헤더, 로비, 로그인).
- [ ] 로그인 화면 `login_page.dart` 재작성: 위 Design 그대로. 직군 드롭다운과 `Icons.local_hospital` 제거, 회원가입은 아이디·비밀번호(+기존 비밀번호 확인/검증 규칙 유지). 넓은 화면(≥900) 좌우, 좁은 화면 세로. 히어로 일러스트는 CustomPainter 또는 SVG 에셋(아이소 섬·성 2채·분수·학생·말풍선, 시안과 같은 팔레트). 기존 로그인·가입 로직·오류 표시·Android 앱 다운로드 버튼·redirect 동작은 그대로.
- [ ] 위젯 테스트: 로그인/가입 탭 전환, 직군 선택이 없음, 가입 시 role을 보내지 않음, 좁은 폭 레이아웃 overflow 없음. 스크린샷을 scratchpad에 저장해 보고.
- [ ] `web/chatflow-app.apk`(git 추적 바이너리): `flutter build apk --release`가 이 머신에서 되면 새 이름·아이콘으로 다시 빌드해 교체하고, 안 되면 교체하지 않고 이유를 보고한다.
- [ ] `flutter analyze`·`flutter test`·web build → Commit `feat(frontend): Gossiplog rebrand with casual login and island icon`.

### Task 5: 문서 (Task 1~4 머지 후)

- [ ] README의 "🏥 EMR(병원 시스템) 연계 기능" 절 삭제(읽음 확인·암호화는 일반 기능 절로 이동), 아이콘 설명, 제품 소개를 "Gossiplog — 채팅 + 위키 던전"으로. CLAUDE.md 프로젝트 개요 첫 문장도 갱신(내부 코드명 ChatFlow는 유지 표기 가능). 하네스 문서에 의료 언급이 없음은 조사로 확인됨.
- [ ] Commit `docs: Gossiplog product description, drop EMR section`.

### 배포

develop 머지 → develop-build → 3개 서비스 태그 고정 → DB 백업 → helm upgrade(V20) → CF 퍼지 → QA E2E(로그인 화면, 가입에 직군 없음, 방 만들기에 인수인계 없음, 채팅 송수신, 월드) → 아이콘·탭 제목 확인. 후속: `users.role`·`allowed_roles`·`priority` 컬럼 DROP 릴리스. 이 마이그레이션에서 V20의 정규화(HANDOFF→GENERAL, PATIENT_CARD·SBAR 삭제)를 한 번 더 돌린다(롤링 중 옛 파드가 쓴 행 정리).
