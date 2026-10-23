# 위키 던전 부드러운 HGSS풍 리디자인 — 설계

사용자 결정(2026-10-23): 포켓몬 HGSS 구도(위에서 내려다보는 타일맵, 주인공 중심 카메라, 지붕+정면이 보이는 건물, 발판·HP 상자·대사창 전투)를 오마주하되 그림체는 부드러운 일러스트(파스텔 그러데이션, 둥근 모서리, 검은 외곽선 없음, 은은한 그림자). 남·여 캐릭터 선택. 배포까지.

확정 시안: `.superpowers/brainstorm/22343-1792737962/content/soft-village-v3.html`(마을 톤 기준), `hgss-battle.html`(전투 배치 기준 — 그림체는 v3 톤으로), `hgss-village.html`(캐릭터 선택 화면 배치).

## 표절 방지 원칙

포켓몬 이름·몬스터볼·로고·실제 캐릭터·타일을 쓰지 않는다. 몬스터 16종, 주인공 2명, 건물 8채는 각 던전 주제(쿠버네티스 조타륜, 자바 커피, DB 원통 등)로 새로 디자인한다. 배치 문법(발판, HP 상자, "야생의 ○○가 나타났다!")만 빌린다.

## 범위

| 영역 | 지금 | 바뀐 뒤 |
| --- | --- | --- |
| 마을 | 24×24 데이터를 아이소메트릭 마름모로 렌더 | 같은 데이터를 위에서 본 정사각 타일로 렌더. 지형(잔디·흙길·연못·광장·꽃)은 코드로 그린다. 나무·분수·가로등·건물은 SVG |
| 던전 | 아이소메트릭 방 + 기둥 | 위에서 본 방(던전별 바닥 색·벽·깔개·기둥) |
| 전투 | 둥근 카드 두 장(장면/문제) | 위: 하늘·숲 띠·발판·적(오른쪽 위)·주인공 뒷모습(왼쪽 아래)·HP 상자·대사창. 아래: 문제 카드 + 큰 색 버튼 4개(2×2). 넓은 화면은 가운데 정렬(최대 폭 560) |
| 몬스터 | 단순 물방울 SVG 16개 | 같은 파일 이름으로 새 디자인(부드러운 일러스트) |
| 주인공 | 후드티 학생 1명(좌우 반전) | 남·여 × 아래/위/옆(오른쪽은 반전) 걷기 그림 + 전투 뒷모습. 졸업 학사모 유지 |
| 성별 저장 | 없음 | 서버 `game_player.avatar`(`boy`/`girl`). 처음 마을에 들어오면 선택 화면, 마을 메뉴에서 바꾸기 |

## 구조

- 격자: `IsoGrid`를 `TopDownGrid`(정사각 타일, `toScreen`=타일 중심, `toTile`, `findPath` 그대로)로 바꾼다. 깊이(priority)는 y 기준.
- 지형: 마을·던전 바닥을 한 번 `Picture`로 구워 매 프레임 그린다(지금 `IsoTileBaker`와 같은 방식).
- 에셋 이름(계약): `assets/world/`의 `monster_<id>.svg`, `boss_<id>.svg`, `building_<id>.svg`, `tree.svg`, `fountain.svg`, `lamp.svg`, `flag.svg`, `student_<boy|girl>_<down|up|side>.svg`, `student_<boy|girl>_back.svg`(전투), `student_cap.svg`. 옛 `student.svg`, `student_back.svg`는 지운다. id는 `k8s jvm db net build ops ai shell`.
- API: `GET /api/game/me` 응답에 `avatar`(null 가능) 추가. `PUT /api/game/me/avatar` `{avatar: "boy"|"girl"}` → 204. 그 밖의 값은 400. Flyway V22 `game_player(user_id varchar(64) PK, avatar varchar(8) not null, updated_at timestamptz not null)`.
- 옛 클라이언트(APK 3.0.7 이하)는 `avatar`를 무시하고 지금 화면을 그대로 쓴다. 응답 필드는 추가만.

## 확인 기준

- 마을: 주인공 중심 12×9칸 안팎이 보이고(폰 폭은 7칸 안팎), 방향키·탭 이동과 문 입장이 지금처럼 동작.
- 던전: 걷다가 전투가 생기고 보스까지 진행.
- 전투: 정답·오답 피드백, 하트, 처치 수, 보스 표시가 지금과 같다.
- 성별: 첫 진입에 선택 화면, 고른 캐릭터가 마을·던전·전투 뒷모습에 나온다. 다른 기기에서도 같다.
- `flutter analyze` error·warning 0(≤52), `flutter test` PASS, `./gradlew :chat-service:build` PASS, 운영 E2E.
