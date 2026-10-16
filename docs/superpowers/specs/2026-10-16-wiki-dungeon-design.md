# 위키 던전: ChatFlow 경량화와 학습 게임 설계

- 날짜: 2026-10-16
- 상태: 승인됨 (브레인스토밍에서 섹션 1~6 사용자 승인)
- 시안: `.superpowers/brainstorm/39161-1792127928/content/` (`style-directions.html` A안, `student-avatar.html` A안, `battle-screen.html`)

## 목표

인프라에 비해 커진 ChatFlow를 줄이고, 사용자의 Obsidian 노트로 만든 문제를 푸는 게임을 붙인다.

1. **경량화**: 채팅만 남기고 검색, AI 요약, Elasticsearch, Kafka를 없앤다.
2. **위키 던전**: 아이소메트릭 마을에서 주제별 건물(던전)에 들어가 포켓몬스터식 랜덤 인카운터로 문제 배틀을 하고 클리어한다.

## 결정 사항

| 항목 | 결정 |
|---|---|
| 문제 출처 | 볼트 `0. Wiki`, `0. Daily`, `5. 공부`, `개발 스터디` |
| 플레이어 | 가입한 누구나 (공개). 따라서 일반화와 사람 검수가 필수다 |
| 형식 | 카테고리(건물)별 도전, 오답 간격 반복 |
| 멀티플레이 | 혼자 탐험. 다른 사람은 클리어 깃발, 클리어 인원, 랭킹으로만 보인다 |
| 문제 생성 | 로컬 Claude 스킬이 생성·일반화하고 관리자 API로 업로드한다. 서버는 LLM을 부르지 않는다 |
| 클라이언트 | 기존 Flutter 앱 안에 Flame 엔진, `/world` 라우트 |
| 그래픽 | A안 아이소메트릭 미니어처 (파스텔, 부드러운 그림자) |
| 캐릭터 | 후드티·안경·백팩·전공책 대학생. 8개 건물을 모두 클리어하면 학사모 |
| 던전 진행 | 걷다가 랜덤 인카운터 → 문제 배틀 → 맞히면 처치, 틀리면 하트 -1 → 5마리 처치 시 보스 → 보스 3문제 연속 정답이면 클리어. 하트 0이면 마을로 귀환 |
| 서버 배치 | 새 서비스 없이 chat-service 안 `com.chatflow.chat.game` 패키지 |

## 하위 프로젝트 순서

① 경량화를 먼저 배포하고, ② 위키 던전을 그 위에 올린다. 각각 계획 → 구현 → 리뷰 → 배포를 따로 돈다.

---

## ① 경량화

### 지우는 것

| 영역 | 대상 |
|---|---|
| 모듈 | `search-service`, `ai-summary-service` (Gradle 서브프로젝트, Dockerfile 빌드 대상) |
| Helm | 하위 차트 `search-service`, `ai-summary-service` (+ `.tgz`), `Chart.yaml` 의존성, values의 해당 블록과 `kafka`·`elasticsearch` secret 참조 |
| CI | `develop-build.yml`, `ci.yml`, `deploy-k3s.yml`의 서비스 매트릭스 항목 |
| gateway | `/api/search/**`, `/api/ai-summary/**` 라우트와 서킷브레이커, 폴백 |
| chat-service | `OutboxPoller`, `KafkaTopicConfig`, `KafkaTopics`, `AiSummaryBroadcastService`, 감사 이벤트의 Kafka 발행, `/internal/rooms/**`(멤버십 조회 엔드포인트와 `InternalSecretVerifier` 중 이 엔드포인트 전용 부분), `spring-kafka` 의존성 |
| common | Kafka 전용 설정(`KafkaErrorConfig` 등), 다른 모듈이 더는 쓰지 않는 DTO |
| 인프라 | Elasticsearch, Kafka 3대 (helm 밖 `k8s/`·`scripts/deploy-k3s.sh infra`), `chatflow-kafka-secret`, `chatflow-elasticsearch-secret`, `chatflow-gemini-secret` |
| 프론트 | `features/search`, 방 안 검색(Ctrl+F 시트, `in_room_search_*`), AI 요약 카드, 입력창 "AI" 버튼(askAi), 커맨드 팔레트의 검색 항목, 관련 라우트와 문자열 |
| 문서 | CLAUDE.md·README의 아키텍처, 포트 표, Kafka 토픽, ES 섹션 |

### 지키는 것

- **메시지 저장 경로**: `ChatPersistenceService`가 outbox를 거쳐 메시지를 저장하고 있으면 outbox를 걷어내기 전에 직접 저장으로 바꾼다. 송신 → 저장 → 히스토리 조회 → 편집·삭제가 그대로 동작해야 한다. 이것이 ①의 최우선 회귀 기준이다.
- `outbox_events` 테이블은 Flyway로 지우지 않는다(롤백 여지). 새 마이그레이션으로 쓰지 않는다는 주석만 남기거나 그대로 둔다.
- 채팅, 방 관리, 초대, 멘션, 예약 메시지, 파일, FCM, 읽음 상태는 바뀌지 않는다.

### 완료 기준

- `./gradlew build` 통과, `flutter analyze`(신규 이슈 0)와 `flutter test` 통과
- 운영에 파드 3개(chat, gateway, frontend)와 Postgres, Valkey만 남는다
- 배포 전후 `kubectl top nodes`로 메모리 사용량을 비교해 기록한다
- QA 계정 브라우저 E2E: 로그인 → 방 입장 → 송수신 → 새로고침 후 히스토리 유지

---

## ② 위키 던전

### 아키텍처

```
Flutter 앱 /world (Flame, flame_tiled 아이소메트릭)
   │ REST(JWT)
gateway → chat-service game 패키지 → Postgres(game_*) / Valkey(판 상태, 랭킹 캐시)
로컬 맥: Claude 스킬 wiki-quiz-gen → JSON → 관리자 bulk API
```

채점은 서버가 한다. 정답 인덱스는 클라이언트로 내보내지 않는다.

### 데이터 모델 (Flyway V19~)

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `game_dungeon` | `id varchar PK`(예: `k8s`), `name`, `sort_order int`, `building_style varchar` | 시드 8행 |
| `game_topic` | `id bigserial`, `dungeon_id FK`, `title`, `summary text`, `source_ref varchar` | `(dungeon_id, title)` unique |
| `game_monster` | `id bigserial`, `topic_id FK null`, `dungeon_id FK`, `name`, `sprite_key`, `is_boss bool` | 보스는 건물당 1 |
| `game_question` | `id bigserial`, `topic_id FK`, `stem text`, `choices jsonb`(정확히 4개), `answer_index smallint 0..3`, `explanation text`, `status varchar`(DRAFT/APPROVED/REJECTED), `content_hash char(64)`, `created_at`, `reviewed_at` | `content_hash` unique |
| `game_progress` | `user_id varchar`, `dungeon_id FK`, `cleared_at timestamptz`, `best_streak int` | PK `(user_id, dungeon_id)` |
| `game_answer` | `id bigserial`, `user_id`, `question_id FK`, `correct bool`, `answered_at`, `next_due_at` | index `(user_id, next_due_at)` |

`content_hash`는 정규화한 `stem + choices + answer_index`의 SHA-256이다.

### 게임 규칙 상수

| 이름 | 값 |
|---|---|
| 시작 하트 | 3 |
| 보스 출현까지 처치 수 | 5 |
| 보스 문제 수 (연속 정답 필요) | 3 |
| 인카운터 걸음 간격 | 6~12 (클라이언트 난수) |
| 판 TTL | 30분 (Valkey `game:run:{runId}`) |
| 건물 개방 최소 승인 문제 수 | 5 |
| 오답 재출제 간격 | 1일 → 3일 → 7일 (맞힐 때마다 다음 단계, 틀리면 1일로) |

### 판(run) 흐름

1. `POST /api/game/dungeons/{id}/runs` → 서버가 문제 큐를 만든다. 다시 볼 때가 된 오답(`next_due_at <= now`)을 먼저 넣고, 승인 문제에서 무작위로 채워 일반 5개와 보스 3개를 준비한다. 하트 3으로 시작한다. 응답은 `{runId, hearts, kills: 0, killsToBoss: 5}`이다.
2. `GET /runs/{runId}/next` → `{seq, monster{name, spriteKey, isBoss}, question{id, stem, choices, topicTitle}}`를 돌려준다. `answer_index`는 넣지 않는다.
3. `POST /runs/{runId}/answers {seq, choiceIndex}` → `{correct, correctIndex, explanation, topicTitle, sourceRef, hearts, kills, bossUnlocked, cleared, failed}`. `game_answer`를 기록하고 `next_due_at`을 계산한다.
   - 같은 `seq`를 다시 제출하면 처음 결과를 그대로 돌려준다(멱등).
   - 보스 단계에서 오답이 나면 보스 연속 카운트를 0으로 되돌리고 보스 문제를 새로 뽑는다(하트도 -1).
   - 하트 0이면 `failed=true`, 판을 지운다.
   - 보스 3연속 정답이면 `cleared=true`, `game_progress`를 upsert하고 판을 지운다.
4. 판이 없거나 만료되면 404 `RUN_EXPIRED`를 돌려준다. 클라이언트는 "던전이 무너졌다" 안내 뒤 마을로 돌아간다.

### API

플레이어 (`/api/game/**`, 인증 필요, gateway가 넣는 `X-User-Id` 사용):

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/dungeons` | `[{id, name, buildingStyle, open, clearedByMe, clearCount}]` |
| POST | `/dungeons/{id}/runs` | 위 1 |
| GET | `/runs/{runId}/next` | 위 2 |
| POST | `/runs/{runId}/answers` | 위 3 |
| GET | `/me` | `{clearedCount, total: 8, graduated}` (`graduated` = 8/8) |
| GET | `/leaderboard` | 상위 20 `{username, clearedCount, correctCount}`, Valkey 60초 캐시 |

관리자 (`/api/game/admin/**`): `X-User-Id`가 env `GAME_ADMIN_USER_IDS`(쉼표 구분, helm Secret/values로 주입) 목록에 있을 때만 허용하고 그 외는 403. 앱의 RBAC "관리자"는 의료 역할이고 role 클레임은 chat-service로 전달되지 않으므로 쓰지 않는다. 목록이 비어 있으면 모든 관리자 호출을 거부한다:

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/questions/bulk` | 스킬 JSON 업로드. 토픽·몬스터는 없으면 만든다. 문제는 DRAFT로 들어가고 `content_hash`가 같으면 건너뛴다. 응답 `{inserted, skipped}` |
| GET | `/questions?status=DRAFT&page=` | 검수 목록 |
| PATCH | `/questions/{id}` | 내용 수정, `status` 변경 |

bulk JSON 형식:

```json
{ "topics": [ { "dungeonId": "jvm", "title": "...", "summary": "...", "sourceRef": "0. Wiki/2. JVM·Spring#...",
    "monster": { "name": "OOM 슬라임", "spriteKey": "slime" },
    "questions": [ { "stem": "...", "choices": ["..","..","..",".."], "answerIndex": 1, "explanation": "..." } ] } ] }
```

### 건물(던전) 8개

| id | 이름 | 위키 카테고리 | 보스 |
|---|---|---|---|
| `k8s` | 쿠버네티스 성 | 1. Kubernetes·배포 | 크래시루프 드래곤 |
| `jvm` | JVM 대장간 | 2. JVM·Spring | 가비지 골렘 |
| `db` | DB 도서관 | 3. DB·캐시·검색 | 데드락 리치 |
| `net` | 네트워크 요새 | 4. 네트워크·보안 | 타임아웃 와이번 |
| `build` | 빌드 공방 | 5. 빌드·Git | 머지 컨플릭트 키메라 |
| `ops` | 운영 관제탑 | 6. 운영·관측 | 알람 폭풍 정령 |
| `ai` | AI 연구소 | 7. AI 개발 도구 | 할루시네이션 미믹 |
| `shell` | 셸 동굴 | 8. macOS·셸 | 좀비 프로세스 |

### 클라이언트

`frontend/lib/features/world/`:

| 파일 | 역할 |
|---|---|
| `world_page.dart` | `/world` 라우트, `GameWidget` + 오버레이(HUD, 대화창, 배틀) |
| `game/village_game.dart` | 아이소메트릭 마을, 건물 문 판정, 깃발과 클리어 인원 |
| `game/dungeon_game.dart` | 던전 바닥, 걸음 카운터 인카운터, 보스 방 |
| `game/student_avatar.dart` | 4방향 걷기, 기쁨 모션, 졸업 시 학사모 |
| `game/iso_grid.dart` | 아이소메트릭 좌표 변환과 A* 경로 탐색 |
| `battle/battle_overlay.dart` | 몬스터, 하트, 문제 카드, 해설 |
| `state/world_provider.dart` | Riverpod 상태와 `/api/game` 호출 |
| `admin/question_review_page.dart` | 관리자 검수 화면 |

- 조작: 방향키·WASD, 모바일은 탭한 칸으로 A* 이동
- 에셋: 타일·건물은 CC0 아이소메트릭 미니어처 팩을 파스텔로 보정한다. 학생과 몬스터는 SVG로 그려 스프라이트 PNG로 굽는다. 몬스터는 카테고리별 기본 체형에 색과 소품을 바꾸고 보스는 따로 그린다. 맵은 Tiled `.tmx`로 마을 1장, 던전 1장(팔레트 교체)이다.
- 진입점: 앱 상단에 "월드" 버튼을 두고, 마을 광장의 게시판에서 랭킹을 연다.

### 문제 생성 스킬 `wiki-quiz-gen`

위치: `~/.claude/skills/wiki-quiz-gen/` (개인 스킬, 리포 밖).

1. 수집: 위키는 `##` 절, 일일·공부 노트는 배운 점 단락 단위로 주제 후보를 뽑는다.
2. 카테고리 배정: 8개 중 하나, 맞지 않으면 버린다.
3. 생성: 주제당 4지선다 3문제, 해설, 몬스터 이름.
4. 일반화: 회사명, 고객사명, 사내 시스템명, 사람 이름, 내부 도메인·IP·계정·티켓 번호를 일반명으로 바꾼다. 사건을 특정할 수 있는 날짜와 영향 범위는 지운다. 시크릿 패턴이 남으면 그 문제를 버린다.
5. 미리보기: `quiz-YYYYMMDD.json`과 치환 전후 비교표. 이 단계까지 원문은 맥 밖으로 나가지 않는다.
6. 업로드: 사용자 확인 뒤 관리자 bulk API.
7. 검수: 관리자 화면에서 승인한 문제만 출제된다.

### 오류 처리

- 판 만료: `RUN_EXPIRED` → 마을 귀환
- 중복 제출: `seq` 멱등
- 승인 문제 5개 미만 건물: `open=false`, "공사 중"
- 오프라인: 배틀 진입 차단, 마을 이동만 허용
- 관리자 API 비권한: 403 (허용 목록 밖이거나 목록이 비어 있음)

### 테스트

- 백엔드: 판 상태 전이(처치, 하트, 보스 리셋, 클리어, 실패) 단위 테스트. `next` 응답에 정답이 없는지 계약 테스트. 관리자 403. bulk 중복 제거. 간격 반복 계산.
- 프론트: 배틀 오버레이 위젯 테스트, provider 테스트, 아이소 좌표·A*·인카운터 카운터 단위 테스트.
- 스킬: 가짜 회사명과 토큰 패턴을 넣은 픽스처 노트로 일반화 결과에 하나도 남지 않는지 확인한다.
- 배포 후: QA 계정으로 마을 → 던전 → 배틀 → 클리어 브라우저 E2E.

## 범위 밖

- 실시간 멀티플레이(다른 플레이어 아바타 표시)
- 서버 측 LLM 호출
- 에셋 유료 구매
- iOS 빌드
