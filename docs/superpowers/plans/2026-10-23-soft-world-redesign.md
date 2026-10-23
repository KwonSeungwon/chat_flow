# 위키 던전 부드러운 HGSS풍 리디자인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 마을·던전·전투를 HGSS 구도 + 부드러운 일러스트로 다시 그리고, 남·여 캐릭터 선택을 넣고 배포한다.

**Spec:** `docs/superpowers/specs/2026-10-23-soft-world-redesign-design.md` (먼저 읽는다)

**톤 기준 시안(직접 열어 본다):** `.superpowers/brainstorm/22343-1792737962/content/soft-village-v3.html`(색·모양·그림자 — 그리기 코드를 그대로 참고해도 된다), `hgss-battle.html`(전투 배치), `hgss-village.html`(선택 화면 배치). 브라우저 없이 볼 때는 `.playwright-mcp/vc-village3.png`, `vc-hgss.png`, `vc-village.png` 스크린샷.

**Tech Stack:** Flutter 3.41 + Flame 1.x + flame_svg, Riverpod; Spring Boot 3.2 + JPA + Flyway(PG16/H2).

## Global Constraints

- 표절 금지: 포켓몬 이름·몬스터볼·로고·실제 캐릭터/타일 사용 금지. 배치 문법만 빌린다.
- 그림체: 파스텔 그러데이션, 둥근 모서리, 검은 외곽선 없음(외곽선이 필요하면 본체보다 어두운 같은 계열 색, 0.8~1.5px), 부드러운 그림자(검정 15~25% 알파). 시안 v3 팔레트를 따른다.
- 에셋 계약(이름·viewBox·월드 크기)은 아래 표가 기준이다. 타일 한 칸 = 월드 48px.
- 기존 동작 유지: 이동(방향키·WASD·탭 A*), 문 입장, 분수 탭(리더보드), 클리어 깃발/인원 표시, 졸업 학사모, 축하 깡충, 전투 흐름(정답·오답·하트·처치 수·보스·결과)은 그대로.
- 옛 클라이언트 호환: `/api/game/me` 응답은 필드 추가만.
- 검증: `cd frontend && flutter analyze`(error·warning 0, issue ≤52), `flutter test` PASS, `./gradlew :chat-service:build` PASS. 시크릿 금지. 커밋 끝 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

### 에셋 계약 (`frontend/assets/world/`)

| 파일 | viewBox | 월드 크기(w×h) | 앵커 | 내용 |
| --- | --- | --- | --- | --- |
| `building_<id>.svg` ×8 | `0 0 144 168` | 144×168 | 아래 가운데 = 3×3 점유칸 아래 변 가운데 | 지붕(위 ~60%)+정면(문은 가운데 아래, 폭 ~32), 던전 주제 간판 |
| `tree.svg` | `0 0 48 60` | 48×60 | 아래 가운데 = 타일 아래 변 | 몽글몽글 수관 + 줄기 + 바닥 그림자 |
| `fountain.svg` | `0 0 144 120` | 144×120 | 아래 가운데 = 분수칸 아래 변(3×3 덮음) | 돌 테두리·물·가운데 조각 |
| `lamp.svg` | `0 0 24 72` | 24×72 | 아래 가운데 | 가로등(은은한 빛) |
| `flag.svg` | 기존 유지 가능(톤만 맞춤) | 기존 | 기존 | 클리어 깃발 |
| `student_<boy\|girl>_<down\|up\|side>.svg` ×6 | `0 0 40 56` | 40×56 | (0.5, 0.97) | 걷기 그림. side는 오른쪽을 본다(왼쪽은 좌우 반전) |
| `student_<boy\|girl>_back.svg` ×2 | `0 0 120 140` | 전투 위젯용 | – | 전투 뒷모습(어깨 위) |
| `student_cap.svg` | `0 0 40 56` | 40×56 | (0.5, 0.97) | 학사모 레이어(down/up/side 공용, 머리 위치 맞춤) |
| `monster_<id>.svg` ×8 | `0 0 120 120` | 위젯 | – | 일반 몬스터 |
| `boss_<id>.svg` ×8 | `0 0 160 160` | 위젯 | – | 보스(같은 계열, 더 크고 화려함) |

id: `k8s jvm db net build ops ai shell`. 옛 `student.svg`, `student_back.svg`는 삭제.

---

### Task 1: 백엔드 — 캐릭터 선택 저장

**Files:** Create `chat-service/src/main/resources/db/migration/V22__game_player_avatar.sql`, `game/entity/GamePlayer.java`, `game/repository/GamePlayerRepository.java`, `game/dto/AvatarRequest.java`. Modify `game/dto/GameMeResponse.java`, `game/service/GameDungeonQueryService.java`(또는 새 `GamePlayerService`), `game/controller/GameController.java`. Test: `GameControllerTest`, `GameDungeonQueryServiceTest`(또는 새 서비스 테스트), Flyway 테스트가 있으면 V22 포함 확인.

**Interfaces — Produces:** `GET /api/game/me` → `{clearedCount,total,graduated,admin,avatar}`(`avatar`: `"boy"|"girl"|null`). `PUT /api/game/me/avatar` body `{"avatar":"boy"|"girl"}` → 204, 그 밖 400. 사용자 id는 기존 컨트롤러와 같은 헤더(`AuthHeaders.X_USER_ID`)에서.

- [ ] V22: `CREATE TABLE game_player (user_id VARCHAR(64) PRIMARY KEY, avatar VARCHAR(8) NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL);` (H2·PG 모두 되는 문법)
- [ ] 실패 테스트: me가 avatar null → PUT boy → me가 boy, PUT 'cat' → 400, 다른 사용자와 분리
- [ ] 구현(업서트: 있으면 갱신, 없으면 insert), `./gradlew :chat-service:build` → 커밋

### Task 2: 에셋 — 새 그림 29장

**Files:** `frontend/assets/world/` 위 표의 파일 전부(새로 쓰거나 덮어씀), 옛 `student.svg`·`student_back.svg` 삭제는 Task 3이 코드 참조를 바꾼 뒤(이 태스크에서는 지우지 않는다). 검토용 미리보기 `frontend/assets/world/_preview.html`은 만들지 말고 scratchpad에 둔다.

- [ ] 시안 v3·hgss-battle·hgss-village 톤으로 표의 viewBox·내용대로 작성. 몬스터는 던전 주제를 의인화한 귀여운 생물(쿠버네티스=조타륜 해파리 등 — 시안의 조타 해파리를 부드러운 톤으로). 보스는 같은 계열의 더 큰 진화형 느낌. 남·여 주인공은 hgss-village 선택 화면의 디자인(남: 파란 모자·백팩, 여: 노란 베레모·빨간 리본·분홍 치마)을 v3 톤으로.
- [ ] flame_svg가 렌더하지 못하는 기능 금지: `<filter>`, `<mask>`, `<pattern>`, CSS `<style>`, `<text>`, 외부 참조. 그러데이션(`linearGradient`/`radialGradient`)·`opacity`·`clipPath`는 허용(안 되면 단색으로).
- [ ] 미리보기 HTML(scratchpad)로 전부 한 장에 그려 PNG로 남기고 보고서에 경로를 적는다 → 커밋(에셋만)

### Task 3: 마을 — 위에서 본 타일맵 + 방향별 주인공

**Files:** Create `game/top_down_grid.dart`(+ `test/.../top_down_grid_test.dart`), `game/soft_terrain.dart`(지형 굽기). Modify `game/village_game.dart`, `game/student_avatar.dart`, `game/world_sprite.dart`(필요 시), `game/dungeon_game.dart`의 `IsoGrid` 참조는 Task 4에서. Delete `game/iso_grid.dart`와 그 테스트는 Task 4 끝에서(던전이 아직 쓴다).

**Interfaces — Produces:** `class TopDownGrid { const TopDownGrid({double tile = 48}); Vector2 toScreen(int c,int r) /*타일 중심*/; (int,int) toTile(Vector2); List<(int,int)> findPath(...) /*IsoGrid와 같은 시그니처·동작*/; }`. `enum StudentLook { boy, girl }`(`state/game_models.dart`). `StudentAvatar({required TopDownGrid grid, required (int,int) tile, StudentLook look = StudentLook.boy, ...})` — 이동 방향에 따라 down/up/side 그림, 왼쪽은 side 반전, 걷는 동안 살짝 흔들림. `VillageGame`에 `StudentLook look` 생성자 인자.

- [ ] 실패 테스트: TopDownGrid toScreen/toTile 왕복, findPath(IsoGrid 테스트를 옮겨 온다)
- [ ] 지형: 잔디(그러데이션+흰·초록 얼룩), 흙길(이어진 하나의 둥근 길 — 타일마다 물결 자국 없이), 광장(벽돌 타일), 연못, 꽃(결정적 해시). 한 번 `Picture`로 굽는다.
- [ ] 소품: 나무(`tree.svg`), 분수, 가로등 몇 개, 흰 울타리 한 줄(시안처럼) — 걷기 판정은 `VillageMap` 그대로(소품은 장식만).
- [ ] 깊이: priority = 아래 변 y. 주인공이 건물 뒤(위쪽 칸)로 가면 가려진다.
- [ ] 카메라: 주인공 follow, zoom = 화면 폭 기준 가로 약 12칸(폰 폭 400px는 약 7칸) 보이게, 맵 밖 여백이 지나치게 보이면 경계 고정.
- [ ] 건물 라벨(이름·클리어 인원)·깃발·잠김 표시를 새 위치에 맞춘다. 탭 → 문 이동, 분수 탭, 키보드 이동 동작 유지(`village_game_test` 통과·갱신).
- [ ] `flutter analyze`·`flutter test` → 커밋

### Task 4: 던전 — 위에서 본 방

**Files:** Modify `game/dungeon_game.dart`(+ 테스트). Delete `game/iso_grid.dart`, `test/features/world/iso_grid_test.dart`, `assets/world/student.svg`, `assets/world/student_back.svg`(참조 0 확인 후).

**Interfaces — Consumes:** Task 3의 `TopDownGrid`, `StudentAvatar(look:)`. **Produces:** `DungeonGame`에 `StudentLook look` 인자.

- [ ] 방: 던전별 바닥 색(지금 `buildingStyle`/색 매핑 재사용), 위쪽 벽(창·깃발 장식), 가운데 깔개 길, 기둥(가림 깊이 유지). 걷기·전투 발생(`EncounterCounter`)·보스 흐름 그대로.
- [ ] `grep -rn "IsoGrid\|iso_grid\|student.svg\|student_back.svg" frontend/` 0건 → `flutter analyze`·`flutter test` → 커밋

### Task 5: 전투 화면

**Files:** Modify `battle/battle_overlay.dart`(+ `battle_overlay_test.dart`).

**Interfaces — Consumes:** `StudentLook`. **Produces:** `BattleOverlay(..., StudentLook look = StudentLook.boy)` — 기존 인자는 그대로.

- [ ] 위 장면: 부드러운 하늘 그러데이션 + 구름 + 먼 숲 띠 + 줄무늬 풀밭(v3 톤), 적 발판(오른쪽 위)·내 발판(왼쪽 아래), 적 `monster_/boss_` SVG, 주인공 `student_<look>_back.svg`, HP 상자 두 개(이름·Lv 대신 주제·하트·처치/보스까지 등 지금 정보), 대사창("야생의 ○○가 나타났다!" / 정답·오답 피드백 문구). 보스는 대사·배경 강조.
- [ ] 아래: 문제 카드 + 2×2 큰 둥근 버튼(빨강·파랑·초록·주황 파스텔, 안쪽 하이라이트), 정답/오답 표시와 해설은 지금처럼. 좁은 화면은 위·아래 세로 배치, 넓은 화면도 세로 배치 가운데 정렬(최대 폭 560).
- [ ] 기존 테스트 기대(문구·키·탭)를 유지하거나 같은 의미로 갱신 → `flutter analyze`·`flutter test` → 커밋

### Task 6: 캐릭터 선택 + 연결

**Files:** Modify `state/game_models.dart`(GameMe.avatar), `state/game_api.dart`(`setAvatar`), `state/world_provider.dart`, `world_page.dart`, `dungeon_page.dart`, 테스트(`game_api_test`, `world_page_test`, `fake_game_api.dart`).

**Interfaces — Consumes:** Task 1 API, Task 3·4·5의 `look` 인자.

- [ ] `GameMe.avatar`(`String?` → `StudentLook?` 변환, 모르는 값은 null). `GameApi.setAvatar(StudentLook)` → PUT.
- [ ] 마을 첫 진입에 avatar가 null이면 선택 화면(시안 hgss-village 오른쪽, v3 톤: 둘 중 고르고 "이 학생으로 입학!") — 고르기 전에는 이동 안 함. 고르면 PUT 후 마을 시작. 마을 앱바 메뉴에 "캐릭터 바꾸기".
- [ ] 고른 look을 VillageGame·DungeonGame·BattleOverlay에 넘긴다. PUT 실패 시 스낵바 + 로컬 선택은 이번 세션에만 적용.
- [ ] `flutter analyze`·`flutter test` → 커밋

### 배포 (컨트롤러)

develop 머지 → CI·develop-build → 태그 고정(chat-service, frontend) → pg_dump → helm upgrade(V22) → CF 퍼지(대시보드) → E2E: 선택 화면 → 남/여 선택 → 마을 이동·건물 입장 → 던전 걷기 → 전투 화면(정답·오답) → 재접속 시 선택 유지. 스크린샷을 남긴다. APK 3.0.8.
