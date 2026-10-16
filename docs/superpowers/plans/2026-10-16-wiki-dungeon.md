# 위키 던전 게임 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아이소메트릭 마을에서 위키 카테고리별 건물(던전)에 들어가 랜덤 인카운터 문제 배틀로 클리어하는 게임을 chat-service(game 패키지)와 Flutter 앱(`/world`, Flame)에 추가한다.

**Architecture:** 서버는 REST만 쓴다. 판(run) 상태는 Valkey, 결과와 진행도는 Postgres에 둔다. 정답은 서버에서만 채점한다. 클라이언트는 Flame으로 아이소 타일을 Canvas에 직접 그리고(A안 파스텔 미니어처), 건물·캐릭터·몬스터는 SVG를 `flame_svg`로 그린다. 문제 카드와 HUD는 Flutter 오버레이 위젯이다.

**Tech Stack:** Spring Boot 3.2 / JPA / Flyway / StringRedisTemplate, Flutter 3.41, Riverpod 2, GoRouter 14, Dio, `flame`, `flame_svg`.

**Spec:** `docs/superpowers/specs/2026-10-16-wiki-dungeon-design.md` (section ②)

**Integration facts:** `.superpowers/notes/game-integration.md` — 구현자는 먼저 읽는다.

## Global Constraints

- 게임 규칙 상수는 스펙 표 그대로: 하트 3, 보스까지 처치 5, 보스 3연속 정답, 인카운터 6~12걸음, 판 TTL 30분, 건물 개방 최소 승인 문제 5, 오답 재출제 1일→3일→7일(맞히면 다음 단계, 틀리면 1일).
- 클라이언트로 가는 응답에 `answerIndex`/`answer_index`를 넣지 않는다(답안 제출 응답의 `correctIndex`만 예외).
- 관리자 API는 env `GAME_ADMIN_USER_IDS`(쉼표 구분) 허용 목록만. 비어 있으면 전부 403.
- JSON 컬럼은 TEXT + Jackson(프로젝트에 jsonb 선례 없음, prod `ddl-auto: validate`).
- Valkey 키는 `chatflow:game:...`. Redis 장애 시 판 시작/진행은 503 `GAME_UNAVAILABLE`(조용히 성공한 척 하지 않는다).
- 새 서비스·파드를 만들지 않는다. 기존 채팅 동작을 바꾸지 않는다.
- 시크릿 값 금지. 커밋 끝에 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- 검증: `./gradlew build` EXIT 0, `flutter analyze` 신규 0, `flutter test` PASS, `flutter build web --release` 성공.

## Rulings (계획 작성 시)

- Ruling: 스펙의 `flame_tiled`/Tiled `.tmx` 대신 맵을 Dart 상수 격자로 정의하고 타일은 Canvas 폴리곤으로 그린다 — A안 시안(파스텔 아이소 면 + 그림자)이 텍스처 타일셋보다 단색 폴리곤과 더 잘 맞고, 외부 에셋 다운로드·라이선스 관리가 사라진다 — 틀렸을 때 비용: 맵을 크게 늘리면 Tiled로 옮기는 작업.
- Ruling: 학생·몬스터·건물은 SVG 파일(`frontend/assets/world/*.svg`)로 직접 그리고 `flame_svg`로 렌더링한다. 걷기 애니메이션은 프레임 시트 대신 위아래 흔들림(bob) + 좌우 반전으로 한다 — 비용: 애니메이션 표현력이 단순.

---

### Task 1: 게임 스키마·엔티티·관리자 API (chat-service)

**Files:**
- Create: `chat-service/src/main/resources/db/migration/V19__game_tables.sql`
- Create: `chat-service/src/main/java/com/chatflow/chat/game/{entity,repository,service,controller,dto}/...`
- Modify: `ChatServiceApplication.java` (`@EntityScan`에 `com.chatflow.chat.game.entity` 추가), 테스트 `RepositoryTestConfig.java`(game entity/repository 스캔 추가), `GlobalExceptionHandler`(게임 예외), `FlywayMigrationTest`(18→19, V19 검사 추가)
- Modify: `helm/chatflow/charts/chat-service/templates/configmap.yaml`(키 `GAME_ADMIN_USER_IDS: {{ .Values.gameAdminUserIds | default "" | quote }}`), `helm/chatflow/charts/chat-service/values.yaml`(`gameAdminUserIds: ""`)

**V19 내용:** 스펙의 `game_dungeon`, `game_topic`, `game_monster`, `game_question`(choices TEXT), `game_progress`(+ `username varchar(100)` — 랭킹 표시용, 클리어·답안 시 갱신), `game_answer` 테이블과 제약/인덱스. `game_dungeon` 시드 8행과 보스 몬스터 8행(스펙 표의 id·이름·보스 이름, `building_style` = id, 보스 `sprite_key` = `boss_<id>`).

**Interfaces (Produces):**
- `GameAdminGuard.requireAdmin(String userId)` → 허용 목록 밖이면 `GameForbiddenException`(403 `GAME_FORBIDDEN`)
- `POST /api/game/admin/questions/bulk` body = 스펙 bulk JSON → `ApiResponse<{inserted:int, skipped:int}>`
  - 검증: `dungeonId`는 8개 중 하나, `choices` 정확히 4개·각 1~200자, `answerIndex` 0..3, `stem` 1~500자, `explanation` 1~1000자, topic `title` 1~200자. 위반 시 400 `VALIDATION_ERROR`(배치 전체 거부).
  - topic은 `(dungeonId,title)`로 upsert, 몬스터는 topic당 1개 upsert(`name`, `spriteKey` 기본 `slime`).
  - `content_hash` = SHA-256 hex of `stem.trim() + "\u0001" + String.join("\u0001", choices.trim()) + "\u0001" + answerIndex`. 같은 해시는 skip.
- `GET /api/game/admin/questions?status=DRAFT&page=0&size=20` → `ApiResponse<{items:[{id, dungeonId, topicTitle, stem, choices, answerIndex, explanation, status}], total}>`
- `PATCH /api/game/admin/questions/{id}` body `{stem?, choices?, answerIndex?, explanation?, status?}` → 수정된 문제. 내용이 바뀌면 `content_hash` 재계산(충돌 시 409 `DUPLICATE_QUESTION`). `status`가 APPROVED/REJECTED로 바뀌면 `reviewed_at=now`.
- 리포지토리: `GameQuestionRepository.countApprovedByDungeon()`(dungeonId→count), `findApprovedIdsByDungeon(dungeonId)`.

- [ ] **Step 1:** 실패하는 테스트: `GameAdminGuardTest`(빈 목록→403, 목록 밖→403, 목록 안→통과, 공백/쉼표 트리밍), `GameAdminControllerTest`(standalone MockMvc, 헤더 없음 401, 비관리자 403, bulk 성공·검증 실패 400, PATCH 승인), `GameQuestionServiceTest`(해시 중복 skip, topic/monster upsert, PATCH 해시 충돌 409), `GameRepositoryTest`(@DataJpaTest: 승인 문제 카운트).
- [ ] **Step 2:** 마이그레이션·엔티티·리포지토리·서비스·컨트롤러 구현.
- [ ] **Step 3:** `FlywayMigrationTest` 19로, V19 테이블·시드 8행 검사 추가(Docker 없으면 skip되는 테스트다).
- [ ] **Step 4:** `./gradlew :chat-service:test` PASS → 전체 `./gradlew build`.
- [ ] **Step 5: Commit** `feat(game): schema, question bank and admin review API`

### Task 2: 판(run) 엔진과 플레이어 API (chat-service, Task 1 이후)

**Files:** `chat-service/src/main/java/com/chatflow/chat/game/service/{GameRunService,RunState,SpacedRepetition,LeaderboardService}.java`, `controller/GameController.java`, dto들, 테스트.

**Interfaces (Produces):**
- `RunState`(Valkey JSON, 키 `chatflow:game:run:{runId}`, TTL 30분, 매 요청 갱신): `{runId, userId, dungeonId, hearts, kills, bossStreak, phase: NORMAL|BOSS, seq, currentQuestionId, normalQueue:[qid], bossQueue:[qid], answered:{seq:{...result}}}`
- `POST /api/game/dungeons/{id}/runs` → 건물 `open=false`면 409 `DUNGEON_CLOSED`. 큐 구성: 이 사용자의 `game_answer` 중 그 건물 문제이며 최신 기록이 오답이고 `next_due_at <= now`인 것 먼저(최대 5), 나머지는 승인 문제에서 무작위. 일반 5개, 보스 3개(보스는 일반과 겹치지 않게, 문제가 부족하면 겹침 허용). 응답 `{runId, hearts:3, kills:0, killsToBoss:5}`.
- `GET /api/game/runs/{runId}/next` → `{seq, monster:{name, spriteKey, isBoss}, question:{id, stem, choices, topicTitle}}`. 남이 만든 run이면 404 `RUN_EXPIRED`(존재를 숨김). 일반 단계 몬스터 = 문제 topic의 몬스터, 보스 단계 = 건물 보스.
- `POST /api/game/runs/{runId}/answers {seq, choiceIndex}` → `{correct, correctIndex, explanation, topicTitle, sourceRef, hearts, kills, bossUnlocked, cleared, failed}`.
  - `seq`가 이미 답한 값이면 저장된 결과 그대로(멱등). 현재 seq가 아니면 409 `SEQ_MISMATCH`.
  - 정답: NORMAL이면 kills+1, kills==5면 phase=BOSS·`bossUnlocked=true`. BOSS면 bossStreak+1, 3이면 `cleared=true`, `game_progress` upsert(`cleared_at` 최초 시각 유지, `best_streak` max), run 삭제.
  - 오답: hearts-1. BOSS면 bossStreak=0이고 보스 큐 끝에 그 문제를 다시 넣는다. hearts==0이면 `failed=true`, run 삭제.
  - 모든 답안은 `game_answer`에 기록. `next_due_at`: `SpacedRepetition.next(previousCorrectStreak, correct, now)` — 오답이면 now+1일, 정답이면 직전 연속 정답 수 0→+1일, 1→+3일, ≥2→+7일.
- `GET /api/game/dungeons` → `[{id, name, buildingStyle, open, clearedByMe, clearCount}]` (`open` = 승인 문제 ≥ 5)
- `GET /api/game/me` → `{clearedCount, total:8, graduated}`
- `GET /api/game/leaderboard` → 상위 20 `[{userId, username, clearedCount, correctCount}]` (username은 Task 1 V19의 `game_progress.username`에서 읽는다. 답안 제출 때 `X-Username`으로 progress 행을 upsert해 갱신한다). Valkey `chatflow:game:leaderboard` 60초 캐시.
- Redis 실패 → 503 `GAME_UNAVAILABLE`.

- [ ] **Step 1:** 실패하는 테스트: `GameRunServiceTest`(시작 큐 구성·due 오답 우선, 정답 5회→보스, 보스 오답→streak 리셋·하트-1·큐 재삽입, 보스 3연속→cleared·progress upsert·run 삭제, 하트 0→failed, 멱등 seq, SEQ_MISMATCH, 남의 run 404), `SpacedRepetitionTest`, `GameControllerTest`(401, next 응답 JSON에 `answerIndex` 키가 없음을 `jsonPath("$..answerIndex").doesNotExist()`로 단언, DUNGEON_CLOSED 409), `LeaderboardServiceTest`(캐시 히트/미스).
- [ ] **Step 2:** 구현 → `./gradlew :chat-service:test` PASS → `./gradlew build`.
- [ ] **Step 3: Commit** `feat(game): run engine with encounters, boss, hearts and spaced repetition`

### Task 3: gateway 라우트 (Task 1과 병렬)

**Files:** `gateway-service/src/main/resources/application-prod.yml`, `application-local.yml.example`, `FallbackController`(+테스트)

- [ ] **Step 1:** fcm-service 라우트를 본떠 `game-service` 라우트 추가: `uri: ${CHAT_SERVICE_URL}`, `Path=/api/game/**`, RequestRateLimiter(replenish 20, burst 40, `#{@userKeyResolver}`), CircuitBreaker `gameCircuitBreaker` → `forward:/api/fallback/game`, Retry(retries 2, GET만, BAD_GATEWAY·GATEWAY_TIMEOUT). resilience4j 인스턴스 설정이 기존 패턴에 있으면 동일하게 추가. local example에도 라우트.
- [ ] **Step 2:** `FallbackController.gameFallback` 503 + `Retry-After: 15`, 테스트 추가.
- [ ] **Step 3:** `./gradlew :gateway-service:test` PASS. **Commit** `feat(gateway): route /api/game to chat-service`

### Task 4: 월드 엔진 — 아이소 격자, 마을·던전 씬, 학생 아바타 (프론트, Task 1과 병렬)

**Files:**
- Modify: `frontend/pubspec.yaml`(`flame`, `flame_svg` 추가 — `flutter pub add`로 Dart 3.11과 호환되는 버전을 받는다; `assets/world/` 등록)
- Create: `frontend/assets/world/` SVG: `student.svg`, `student_cap.svg`(학사모 레이어), 건물 8개 `building_<id>.svg`(A안 톤, 지붕 색은 스펙 시안 참고: k8s 붉은 성+깃대, jvm 대장간 굴뚝, db 도서관 기둥, net 요새 성벽, build 공방 톱니, ops 탑, ai 돔, shell 동굴 입구), 몬스터 기본 체형 8개 `monster_<id>.svg`, 보스 8개 `boss_<id>.svg`, `flag.svg`, `fountain.svg`, `tree.svg`
- Create: `frontend/lib/features/world/game/{iso_grid.dart, village_map.dart, village_game.dart, dungeon_game.dart, student_avatar.dart, encounter_counter.dart}`
- Test: `frontend/test/features/world/{iso_grid_test.dart, encounter_counter_test.dart, village_map_test.dart}`

**Interfaces (Produces):**
- `IsoGrid(tileW: 64, tileH: 32)`: `Vector2 toScreen(int col, int row)`, `(int,int) toTile(Vector2 screen)`, `List<(int,int)> findPath(start, goal, bool Function(int,int) walkable)`(A*, 4방향)
- `VillageMap`: 24×24 상수 격자(`G` 잔디, `P` 길, `W` 물, `T` 나무, `B` 건물 점유, `D` 문, `F` 분수, `S` 시작점), `Map<String,(int,int)> doors`(dungeonId→문 타일), `startTile`
- `EncounterCounter(Random rng)`: `bool step()` — 6~12걸음 사이 무작위 간격마다 true, true 후 다음 간격 재추첨
- `VillageGame extends FlameGame`: 콜백 `onDoorReached(String dungeonId)`, 입력 `setDungeonState(Map<String,({bool open,bool cleared,int clearCount})>)`, `setGraduated(bool)`, 키보드(방향키/WASD)·탭 이동
- `DungeonGame extends FlameGame`: 생성자 `(String dungeonId)`, 콜백 `onEncounter()`, `pause()/resume()`(배틀 중 이동 정지), 보스 단계에서 바닥 색 어둡게(`setBossPhase(bool)`)
- `StudentAvatar extends PositionComponent`: `moveAlong(List<(int,int)> path)`, `celebrate()`, `graduated` 세터(학사모 레이어 표시)

- [ ] **Step 1:** 실패하는 테스트: `iso_grid_test`(toScreen/toTile 왕복, A* 우회·도달 불가 시 빈 경로), `encounter_counter_test`(시드 고정 rng로 간격이 6~12 안), `village_map_test`(8개 문이 모두 걷기 가능 타일과 연결, 시작점에서 각 문까지 경로 존재).
- [ ] **Step 2:** 구현. 타일은 마름모 폴리곤(윗면 + 좌우 옆면 2톤)과 부드러운 그림자, 파스텔 팔레트(시안 A: 잔디 `#9fd8a8/#6fb487/#5a9d74`, 길 `#f3e6c8`, 물 `#7cc4f2`). 건물 앞 표지판에 클리어 인원, 클리어 건물엔 `flag.svg`, `open=false`면 반투명 + "공사 중".
- [ ] **Step 3:** `flutter analyze`·`flutter test` PASS. **Commit** `feat(world): isometric village and dungeon scenes with student avatar`

### Task 5: 배틀 오버레이·상태·API·라우팅 (프론트, Task 2·4 이후)

**Files:** `frontend/lib/features/world/{world_page.dart, dungeon_page.dart}`, `battle/battle_overlay.dart`, `state/{game_api.dart, game_models.dart, world_provider.dart, run_notifier.dart}`, `core/routing/app_router.dart`(`/world`, `/world/dungeon/:id`), `features/chat/chat_page.dart`(AppBar 와이드 IconButton `Icons.public` 툴팁 "월드", 모바일 팝업 메뉴 항목, 방 미선택 화면에도 진입 버튼), 테스트.

**Interfaces (Consumes):** Task 2 API 그대로, Task 4 게임 클래스.

- `GameApi(Dio)`: `listDungeons()`, `startRun(id)`, `next(runId)`, `answer(runId, seq, choice)`, `me()`, `leaderboard()`. 오류 코드 → `GameApiException(code)`: `RUN_EXPIRED`, `DUNGEON_CLOSED`, `SEQ_MISMATCH`, `GAME_UNAVAILABLE`.
- `worldProvider`(autoDispose, userId watch): 던전 목록 + me. `runNotifierProvider.autoDispose.family<RunNotifier, RunState, String dungeonId>`: `start()`, `encounter()`(next 호출 → 배틀 상태), `answer(i)`(중복 탭 방지), 결과에 따라 `cleared`/`failed` 상태.
- `BattleOverlay`: 시안 `battle-screen.html` 구성 — 왼쪽 몬스터(SVG)·학생 뒷모습·이름표(몬스터 이름, 주제, 하트, 처치 n/5), 오른쪽 문제 카드(주제 태그, 4지선다, 답 후 정답 강조 + 해설 + "원문 주제" 표시). 좁은 화면(<768)은 세로 배치. 정답이면 몬스터 사라지는 애니메이션과 학생 `celebrate()`.
- 클리어 시 축하 다이얼로그 후 마을로, 8/8이면 "졸업! 학사모를 받았다" 다이얼로그. 실패 시 "기절했다… 마을로 돌아간다". `RUN_EXPIRED` 시 "던전이 무너졌다" 후 마을로. 오프라인(Dio connection error)이면 배틀 진입 대신 스낵바.
- 마을 광장 분수 탭 → 랭킹 바텀시트(`leaderboard`).

- [ ] **Step 1:** 실패하는 테스트: `game_api_test`(가짜 HttpClientAdapter로 응답 파싱·오류 코드 매핑), `run_notifier_test`(정답→처치, 5처치→보스, 실패/클리어 전이, 답 중복 탭 무시, RUN_EXPIRED 처리), `battle_overlay_test`(위젯: 4개 선택지, 답 후 해설 표시, 선택 후 버튼 비활성), `chat_page` 월드 버튼이 `/world`로 이동.
- [ ] **Step 2:** 구현 → `flutter analyze` 신규 0, `flutter test` PASS, `flutter build web --release`.
- [ ] **Step 3: Commit** `feat(world): battle overlay, run state and world entry points`

### Task 6: 관리자 검수 화면 (프론트, Task 1 이후)

**Files:** `frontend/lib/features/world/admin/{question_review_page.dart, admin_game_api.dart}`, 라우트 `/world/admin`, 테스트.

- 목록(DRAFT 기본, 탭으로 APPROVED/REJECTED), 문제 카드에 정답 표시, 인라인 편집(stem, 보기 4, 정답, 해설), 승인/반려 버튼, 페이지네이션. 403이면 "관리자만 볼 수 있습니다". 마을 화면 우상단에 관리자일 때만 보이는 진입 아이콘(403 응답으로 판별하지 말고 `GET /api/game/admin/questions?size=1` 성공 여부로 판별).
- [ ] **Step 1:** 실패하는 테스트(`admin_game_api_test`, `question_review_page_test`: 승인 탭 시 PATCH 호출, 403 메시지) → 구현 → 검증 → **Commit** `feat(world): admin question review page`

### Task 7: 문제 생성 스킬 `wiki-quiz-gen` (리포 밖, 병렬 가능)

**Files:** `~/.claude/skills/wiki-quiz-gen/SKILL.md`, `~/.claude/skills/wiki-quiz-gen/scripts/{sanitize_check.py, upload.sh}`, `~/.claude/skills/wiki-quiz-gen/fixtures/fake-note.md`

- `SKILL.md`: 스펙 "문제 생성 스킬" 7단계 절차, 카테고리 매핑 표(위키 1~8 → 8 dungeonId), 일반화 규칙(치환 목록 형식: 회사명→"A사" 등), 출력 JSON 스키마(= bulk API body), 주제당 3문제·4지선다·오답 보기 작성 지침, 몬스터 이름 짓기 지침(장애·버그를 의인화, 예: OOM 슬라임), `spriteKey`는 dungeonId 기본 체형 `monster_<id>`.
- `sanitize_check.py <json>`: 시크릿 패턴(정규식: JWT `eyJ[\w-]+\.[\w-]+\.`, `cfat_`, `AKIA[0-9A-Z]{16}`, 32자 이상 hex/base64 덩어리, 사설 IP `10.`·`192.168.`·`172.16-31.`, 이메일, 내부 도메인 목록 인자)과 금칙어 목록(인자 파일)이 문제 텍스트에 남아 있으면 해당 문제를 보고하고 exit 1. 표준 라이브러리만.
- `upload.sh <json> <base_url>`: 토큰은 env `CHATFLOW_TOKEN`에서만 읽는다(인자·파일 저장 금지). `POST /api/game/admin/questions/bulk`.
- `fixtures/fake-note.md`: 가짜 회사명·토큰·IP를 섞은 노트. `python3 sanitize_check.py`가 이 픽스처로 만든 미치환 JSON을 거부하는지 확인하는 자체 점검을 SKILL.md에 적는다.
- [ ] **Step 1:** 작성 → `python3 -I sanitize_check.py fixtures/bad.json` exit 1, `fixtures/good.json` exit 0 확인. (리포 커밋 없음)

### 배포·콘텐츠 (컨트롤러)

1. Task 1~6 머지 → `./gradlew build`, `flutter test`, web build → 최종 브랜치 리뷰 → develop 머지·push → develop-build → 태그 고정 → helm upgrade(`gameAdminUserIds`에 사용자 본인 userId — 운영 DB에서 사용자 확인 후 values-k3s에 넣는다) → CF 퍼지.
2. 스킬로 볼트에서 문제 생성 → 사용자에게 미리보기·치환표 확인 → 업로드 → 사용자 검수(승인).
3. QA E2E: 마을 → 열린 던전 → 인카운터 → 배틀 → 클리어.
