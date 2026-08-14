---
name: chatflow-qa
description: "ChatFlow 통합 품질 검증 스킬. 새 피처 구현 완료 후 Spring 백엔드 API와 Flutter 프론트엔드 DTO의 경계면 불일치를 잡아낸다. API 응답 shape 검증, Kafka DTO 역직렬화 검증, STOMP 토픽 경로 일관성, Gateway 라우팅 확인을 수행한다. PR 리뷰 전, 배포 전에 반드시 실행한다."
---

# ChatFlow 통합 QA 스킬

## 핵심 원칙

"코드가 존재하는가"가 아니라 "양쪽 경계면이 실제로 일치하는가"를 교차 비교한다. API와 프론트엔드를 동시에 읽고 shape을 비교하는 것이 핵심이다.

## 검증 Area 1: Spring API ↔ Flutter DTO

**읽어야 할 파일:**
```
Spring: chat-service/src/main/java/**/controller/*.java
        chat-service/src/main/java/**/dto/*.java
        common/src/main/java/**/dto/*.java
Flutter: frontend/lib/shared/models/*.dart
         frontend/lib/features/**/*_provider.dart   (provider는 피처 루트에 위치)
         frontend/lib/core/network/api_response.dart (공용 envelope unwrap 헬퍼)
```

**비교 방법:** Spring Controller의 `ApiResponse<T>` 내부 타입 필드명과 Flutter `fromJson()` 메서드의 `json['fieldName']`을 직접 나열해 비교한다.

**자주 발생하는 불일치:**
| 패턴 | Spring | Flutter (올바른) | Flutter (잘못된) |
|------|--------|----------------|----------------|
| ID 타입 | `Long id` | `int id` | `String id` |
| 날짜 | `LocalDateTime createdAt` | `DateTime.parse(json['createdAt'])` | `json['createdAt']` 그대로 |
| 래핑 | `ApiResponse<T>` | `apiResponseList/Map` 공용 헬퍼 | 수동 `['data']` 파싱 또는 unwrap 누락 |
| nullable | `@Nullable String x` | `String? x` | `String x` |

**빠른 비교 명령:**
```bash
# Spring DTO 필드 추출
grep -rn "private " chat-service/src/main/java/**/dto/ --include="*.java"

# Flutter fromJson 필드 추출
grep -rn "json\['" frontend/lib/shared/models/ --include="*.dart"
```

## 검증 Area 2: Kafka DTO ↔ Consumer

**토픽별 교차 비교:**

| 토픽 | Producer 위치 | Consumer 위치 |
|------|-------------|-------------|
| `chat-messages` | `chat-service/**/event/` | `search-service/**/consumer/` |
| `ai-summary-requests` | `chat-service/**` | `ai-summary-service/**/client/` |
| `ai-summaries` | `ai-summary-service/**` | `search-service/**/consumer/` |

각 토픽의 Producer가 직렬화하는 DTO 클래스와 Consumer가 역직렬화하는 타입 파라미터가 동일한지 확인한다.

## 검증 Area 3: STOMP 토픽 경로

```bash
# Spring @SendTo, @MessageMapping 경로 추출
grep -rn "@SendTo\|@MessageMapping" chat-service/src/ --include="*.java"

# Flutter subscribe, send 경로 추출
grep -rn "subscribe\|\.send(" frontend/lib/ --include="*.dart"
```

확인 항목:
- `@SendTo("/topic/chat/{roomId}")` ↔ `subscribe('/topic/chat/$roomId')`
- `@MessageMapping("/chat.sendMessage")` ↔ `send('/app/chat.sendMessage')`

## 검증 Area 4: Gateway 라우팅

```bash
# 새 API 경로가 Gateway에 포함되는지 확인
grep -n "predicates\|uri" gateway-service/src/main/resources/application*.yml
```

확인 항목:
- 새 `/api/{path}/**` 경로가 라우팅 규칙에 포함
- SecurityConfig에서 인증 예외 처리 필요 경로

## QA 보고서 형식

`_workspace/04_qa_report.md`에 저장:

```markdown
# ChatFlow QA 보고서
## 피처: {이름} | 검증 일시: {날짜}

### Area 1: API ↔ DTO
| 엔드포인트 | Spring 타입 | Flutter DTO | 결과 | 비고 |
|-----------|-----------|------------|------|------|

### Area 2: Kafka
| 토픽 | Producer DTO | Consumer 타입 | 결과 |
|------|-------------|-------------|------|

### Area 3: STOMP
| 방향 | Spring | Flutter | 결과 |
|-----|--------|--------|------|

### Area 4: Gateway
| 새 경로 | 라우팅 포함 | 결과 |
|--------|-----------|------|

## 최종: **PASS** / **FAIL**

실패 항목 (파일명:라인 포함):
- {구체적 수정 요청}
```

## 판정 기준

- **PASS**: 모든 Area 체크 항목 일치
- **FAIL**: 하나라도 불일치 → 담당 에이전트에게 파일명과 라인 번호를 포함한 구체적 수정 요청
