# Backend Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 백엔드 Spring Boot 멀티모듈의 중복 코드(Kafka 토픽 문자열, Elasticsearch 필터), 긴 메서드(UserPresenceService.join), 매직 문자열을 제거해 유지보수성을 높인다.

**Architecture:** common 모듈에 공유 상수를 추가하고, 각 서비스가 임포트해 사용한다. KoreanSearchService에서 반복되는 mustNot SYSTEM 필터는 private helper로 추출한다. UserPresenceService.join()은 책임별 private 메서드로 분해한다.

**Tech Stack:** Java 17, Spring Boot 3.2, Gradle 멀티모듈, Elasticsearch Java Client (co.elastic.clients)

---

## File Structure

| 파일 | 역할 | 변경 |
|------|------|------|
| `common/src/main/java/com/chatflow/common/dto/KafkaTopics.java` | Kafka 토픽 이름 공유 상수 | **신규** |
| `search-service/.../util/SearchConstants.java` | ES 인덱스명, highlight 태그 상수 | **신규** |
| `search-service/.../service/KoreanSearchService.java` | mustNot 필터 private helper 추출 | **수정** |
| `chat-service/.../service/UserPresenceService.java` | join() 분해, Redis 키 상수화 | **수정** |
| `chat-service/.../service/MessageSenderService.java` | KafkaTopics 상수 참조로 교체 | **수정** |
| `chat-service/.../service/OrderEventConsumer.java` | KafkaTopics 상수 참조로 교체 | **수정** |
| `search-service/.../service/SearchService.java` | KafkaTopics 상수 참조로 교체 | **수정** |
| `ai-summary-service/.../service/AiSummaryService.java` | KafkaTopics 상수 참조로 교체 | **수정** |

---

## Task 1: KafkaTopics 공유 상수 클래스 (common 모듈)

`"chat-messages"`, `"ai-summaries"`, `"ai-summary-requests"` 가 5개 파일에 각자 private 상수로 정의되어 있다. 토픽 이름이 바뀌면 모든 파일을 각각 수정해야 한다.

**Files:**
- Create: `common/src/main/java/com/chatflow/common/dto/KafkaTopics.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java` (줄 33)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java` (줄 50-51)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/OrderEventConsumer.java` (줄 21)
- Modify: `search-service/src/main/java/com/chatflow/search/service/SearchService.java` (줄 51)
- Modify: `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java` (줄 37, 69, 80)

- [ ] **Step 1: KafkaTopics 클래스 생성**

```java
// common/src/main/java/com/chatflow/common/dto/KafkaTopics.java
package com.chatflow.common.dto;

/**
 * Kafka 토픽 이름 상수. 모든 서비스에서 이 값을 참조해야 한다.
 * @KafkaListener의 topics 속성에도 직접 사용 가능 (public static final String은 컴파일 상수).
 */
public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String CHAT_MESSAGES = "chat-messages";
    public static final String AI_SUMMARY_REQUESTS = "ai-summary-requests";
    public static final String AI_SUMMARIES = "ai-summaries";
}
```

- [ ] **Step 2: 빌드 확인 (common 모듈만)**

```bash
./gradlew :common:compileJava --no-daemon -q
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: UserPresenceService.java 줄 33 교체**

현재:
```java
private static final String CHAT_TOPIC = "chat-messages";
```
변경 후 (import 추가 + 필드 제거, 사용부 교체):
```java
import com.chatflow.common.dto.KafkaTopics;
// 필드 삭제하고 아래처럼 직접 참조
chatPersistenceService.saveOutboxEventAndPublish(message, KafkaTopics.CHAT_MESSAGES, "USER_JOINED");
// leave 메서드도 동일
chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, KafkaTopics.CHAT_MESSAGES, "USER_LEFT");
```

- [ ] **Step 4: MessageSenderService.java 줄 50-51 교체**

현재:
```java
private static final String CHAT_TOPIC = "chat-messages";
private static final String AI_SUMMARY_TOPIC = "ai-summary-requests";
```
변경 후 (두 줄 삭제, import 추가, 사용부에서 `KafkaTopics.CHAT_MESSAGES`, `KafkaTopics.AI_SUMMARY_REQUESTS` 사용):
```java
import com.chatflow.common.dto.KafkaTopics;
// 사용 예 (MessageSenderService 내 kafkaTemplate.send 호출부):
kafkaTemplate.send(KafkaTopics.CHAT_MESSAGES, message.getChatRoomId(), json);
kafkaTemplate.send(KafkaTopics.AI_SUMMARY_REQUESTS, message.getChatRoomId(), requestJson);
```

- [ ] **Step 5: OrderEventConsumer.java + SearchService.java + AiSummaryService.java 교체**

OrderEventConsumer.java 줄 21:
```java
// 삭제: private static final String CHAT_TOPIC = "chat-messages";
import com.chatflow.common.dto.KafkaTopics;
// 사용부: KafkaTopics.CHAT_MESSAGES
```

SearchService.java 줄 51 (어노테이션):
```java
@KafkaListener(topics = {KafkaTopics.CHAT_MESSAGES, KafkaTopics.AI_SUMMARIES})
```

AiSummaryService.java:
```java
// 삭제: private static final String SUMMARY_TOPIC = "ai-summaries";
import com.chatflow.common.dto.KafkaTopics;
// @KafkaListener(topics = KafkaTopics.AI_SUMMARY_REQUESTS)
// @KafkaListener(topics = KafkaTopics.CHAT_MESSAGES)
// kafkaTemplate.send(KafkaTopics.AI_SUMMARIES, ...)
```

- [ ] **Step 6: 전체 빌드 확인**

```bash
./gradlew build --no-daemon -x test -q
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/com/chatflow/common/dto/KafkaTopics.java \
  chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java \
  chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java \
  chat-service/src/main/java/com/chatflow/chat/service/OrderEventConsumer.java \
  search-service/src/main/java/com/chatflow/search/service/SearchService.java \
  ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java
git commit -m "refactor: KafkaTopics 공유 상수 클래스 추가 — 토픽명 중복 제거"
```

---

## Task 2: SearchConstants + KoreanSearchService mustNot 필터 중복 제거

`KoreanSearchService`에서 JOIN/LEAVE/SYSTEM 타입을 제외하는 mustNot 필터가 `searchKoreanContent`, `searchWithNgram`, `searchWithFilters` 세 메서드에 동일하게 반복된다. ES 인덱스명 `"chat_messages"` 도 3곳에 하드코딩되어 있다.

**Files:**
- Create: `search-service/src/main/java/com/chatflow/search/util/SearchConstants.java`
- Modify: `search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java`
- Test: `search-service/src/test/java/com/chatflow/search/service/KoreanSearchServiceTest.java` (신규)

- [ ] **Step 1: SearchConstants 클래스 생성**

```java
// search-service/src/main/java/com/chatflow/search/util/SearchConstants.java
package com.chatflow.search.util;

public final class SearchConstants {
    private SearchConstants() {}

    public static final String CHAT_MESSAGES_INDEX = "chat_messages";
    public static final String HIGHLIGHT_PRE_TAG = "<mark>";
    public static final String HIGHLIGHT_POST_TAG = "</mark>";

    /** ES에서 검색 결과에서 제외할 시스템 메시지 타입 목록 */
    public static final java.util.List<String> EXCLUDED_MESSAGE_TYPES =
            java.util.List.of("JOIN", "LEAVE", "SYSTEM");
}
```

- [ ] **Step 2: 실패 테스트 작성**

```java
// search-service/src/test/java/com/chatflow/search/service/KoreanSearchServiceTest.java
package com.chatflow.search.service;

import com.chatflow.search.util.SearchConstants;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KoreanSearchServiceTest {

    @Test
    void excludedMessageTypes_포함_확인() {
        assertThat(SearchConstants.EXCLUDED_MESSAGE_TYPES)
                .containsExactlyInAnyOrder("JOIN", "LEAVE", "SYSTEM");
    }

    @Test
    void chatMessagesIndex_값_확인() {
        assertThat(SearchConstants.CHAT_MESSAGES_INDEX).isEqualTo("chat_messages");
    }
}
```

- [ ] **Step 3: 테스트 실행 (RED 확인)**

```bash
./gradlew :search-service:test --no-daemon -q 2>&1 | grep -E "PASS|FAIL|BUILD"
```
Expected: `BUILD SUCCESSFUL` (상수 클래스가 생성됐으니 바로 통과)

- [ ] **Step 4: KoreanSearchService mustNot 헬퍼 메서드 추출**

파일: `search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java`

클래스 하단에 private 메서드 추가:
```java
import com.chatflow.search.util.SearchConstants;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;

// 클래스 내부 private 메서드
private static BoolQuery.Builder excludeSystemMessages(BoolQuery.Builder builder) {
    return builder.mustNot(mn -> mn.terms(t -> t
            .field("messageType")
            .terms(tv -> tv.value(
                    SearchConstants.EXCLUDED_MESSAGE_TYPES.stream()
                            .map(FieldValue::of)
                            .toList()
            ))
    ));
}
```

- [ ] **Step 5: searchKoreanContent() 에서 인라인 mustNot 제거 후 헬퍼 적용**

현재 (줄 44-54):
```java
BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder()
        .must(multiMatchQuery)
        .mustNot(mn -> mn.terms(t -> t
                .field("messageType")
                .terms(tv -> tv.value(List.of(
                        co.elastic.clients.elasticsearch._types.FieldValue.of("JOIN"),
                        co.elastic.clients.elasticsearch._types.FieldValue.of("LEAVE"),
                        co.elastic.clients.elasticsearch._types.FieldValue.of("SYSTEM")
                )))
        ));
```
변경 후:
```java
BoolQuery.Builder boolQueryBuilder = excludeSystemMessages(
        new BoolQuery.Builder().must(multiMatchQuery));
```

- [ ] **Step 6: searchWithNgram() 에서 동일하게 적용**

현재 (줄 118-127):
```java
BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder()
        .must(ngramQuery)
        .mustNot(mn -> mn.terms(t -> t
                .field("messageType")
                .terms(tv -> tv.value(List.of(
                        ...
                )))
        ));
```
변경 후:
```java
BoolQuery.Builder boolQueryBuilder = excludeSystemMessages(
        new BoolQuery.Builder().must(ngramQuery));
```

- [ ] **Step 7: searchWithFilters() else 분기에서 동일하게 적용**

현재 (줄 219-227, messageType이 null일 때):
```java
} else {
    boolBuilder.mustNot(mn -> mn.terms(t -> t
            .field("messageType")
            .terms(tv -> tv.value(List.of(
                    co.elastic.clients.elasticsearch._types.FieldValue.of("JOIN"),
                    co.elastic.clients.elasticsearch._types.FieldValue.of("LEAVE"),
                    co.elastic.clients.elasticsearch._types.FieldValue.of("SYSTEM")
            )))));
}
```
변경 후:
```java
} else {
    excludeSystemMessages(boolBuilder);
}
```

- [ ] **Step 8: "chat_messages" 하드코딩 → SearchConstants 사용**

`KoreanSearchService` 내 `.index("chat_messages")` 세 곳을 `.index(SearchConstants.CHAT_MESSAGES_INDEX)` 로 교체.

- [ ] **Step 9: 빌드 + 테스트**

```bash
./gradlew :search-service:test --no-daemon -q 2>&1 | grep -E "PASS|FAIL|BUILD|tests"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add search-service/src/main/java/com/chatflow/search/util/SearchConstants.java \
  search-service/src/main/java/com/chatflow/search/service/KoreanSearchService.java \
  search-service/src/test/java/com/chatflow/search/service/KoreanSearchServiceTest.java
git commit -m "refactor: SearchConstants 추가 + KoreanSearchService mustNot 필터 중복 제거"
```

---

## Task 3: UserPresenceService.join() private 메서드 분해

`join()` 메서드가 114줄이며 Ban 게이트, 만석 분기, Redis 등록, DB 등록, 브로드캐스트 5가지 책임을 순차 처리한다. 가독성과 단위 테스트 가능성을 위해 private 메서드로 분리한다.

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java`

참고: `join()`의 현재 흐름 (줄 35-148):
```
join()
 ├─ 35-46:  Ban 게이트 (banned → ROOM_BANNED error → return)
 ├─ 48-90:  만석 분기 (DM 멤버십 확인, redirect, alreadyJoined skip)
 ├─ 92-100: Redis SET 등록 + TTL
 ├─ 102-121: DB 멤버십 자동 등록 (idempotent)
 ├─ 123-129: alreadyJoined이면 early return (노이즈 억제)
 └─ 131-148: JOIN 메시지 설정 + presence 브로드캐스트 + outbox 저장
```

- [ ] **Step 1: `checkBanGate()` 추출**

`join()` 메서드 내 줄 38-46 로직을 private 메서드로 추출:
```java
/**
 * @return true if the user is banned and join should be aborted
 */
private boolean checkBanGate(String userId, String chatRoomId, String username) {
    if (!userId.isEmpty() && roomBanService.isBanned(chatRoomId, userId)) {
        log.warn("User {} attempted to join banned room {}", username, chatRoomId);
        messagingTemplate.convertAndSend(
                "/topic/chat/" + chatRoomId + "/errors",
                Map.of("type", "ROOM_BANNED", "roomId", chatRoomId));
        return true;
    }
    return false;
}
```

- [ ] **Step 2: `handleRoomFullIfNeeded()` 추출**

`join()` 줄 52-90 로직 추출:
```java
/**
 * 만석인 경우 처리. DM은 기존 멤버만 허용, 일반 방은 redirect.
 * @return true if join should be aborted (non-member DM full)
 */
private boolean handleRoomFullIfNeeded(ChatMessage message, String currentUserId, boolean alreadyJoined) {
    if (!participantService.isRoomFull(message.getChatRoomId())) {
        return false;
    }
    if (alreadyJoined) {
        return false;
    }

    ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);
    if (room != null && room.getRoomType() == RoomType.DIRECT) {
        boolean isExistingMember = !currentUserId.isEmpty() &&
                roomMemberRepository.existsByRoomIdAndUserId(message.getChatRoomId(), currentUserId);
        if (!isExistingMember) {
            log.warn("DM room {} is full, rejecting non-member {}", message.getChatRoomId(), message.getUsername());
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + message.getChatRoomId() + "/errors",
                    Map.of("type", "ROOM_FULL_DM",
                            "roomId", message.getChatRoomId(),
                            "roomName", room.getName()));
            return true;
        }
        log.info("DM {} full but {} is existing member — allowing re-entry",
                message.getChatRoomId(), message.getUsername());
    } else {
        String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
        ChatRoom newRoom = participantService.findOrCreateAvailableRoom(baseName);
        log.info("Room {} full, redirecting user {} to {}",
                message.getChatRoomId(), message.getUsername(), newRoom.getId());
        messagingTemplate.convertAndSend(
                "/topic/chat/" + message.getChatRoomId() + "/errors",
                Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));
        message.setChatRoomId(newRoom.getId());
    }
    return false;
}
```

- [ ] **Step 3: `registerParticipant()` 추출**

`join()` 줄 92-121 추출:
```java
private void registerParticipant(ChatMessage message, String sessionId) {
    String participantKey = "chatflow:room:participants:" + message.getChatRoomId();
    String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
    String safeSessionId = sessionId != null ? sessionId : "unknown";
    String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();

    redisTemplate.opsForSet().add(participantKey, entry);
    redisTemplate.expire(participantKey, 7, TimeUnit.DAYS);
    syncParticipantCount(message.getChatRoomId());

    if (!safeUserId.equals("anonymous") &&
            !roomMemberRepository.existsByRoomIdAndUserId(message.getChatRoomId(), safeUserId)) {
        try {
            String safeUsername = message.getUsername() != null ? message.getUsername() : "anonymous";
            roomMemberRepository.save(RoomMemberEntity.builder()
                    .roomId(message.getChatRoomId())
                    .userId(safeUserId)
                    .username(safeUsername)
                    .joinedAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("Failed to register room membership: roomId={} userId={} reason={}",
                    message.getChatRoomId(), safeUserId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: `broadcastJoin()` 추출**

`join()` 줄 131-148 추출:
```java
private void broadcastJoin(ChatMessage message) {
    message.setType(ChatMessage.MessageType.JOIN);
    message.setTimestamp(LocalDateTime.now());
    message.setMessageId(UUID.randomUUID().toString());
    message.setContent(message.getUsername() + "님이 입장하셨습니다.");

    log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

    Set<String> participantIds = getRoomParticipantUserIds(message.getChatRoomId());
    messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId() + "/presence",
            Map.of("type", "JOIN",
                    "roomId", message.getChatRoomId(),
                    "username", message.getUsername(),
                    "participantCount", participantIds.size(),
                    "timestamp", LocalDateTime.now().toString()));

    chatPersistenceService.saveOutboxEventAndPublish(message, KafkaTopics.CHAT_MESSAGES, "USER_JOINED");
}
```

- [ ] **Step 5: join() 메서드 교체**

위 4개 메서드를 사용해 join()을 다음으로 교체 (전체 교체):
```java
public void join(ChatMessage message, String sessionId) {
    String currentUserId = message.getUserId() != null ? message.getUserId() : "";

    if (checkBanGate(currentUserId, message.getChatRoomId(), message.getUsername())) {
        return;
    }

    Set<String> existingUserIds = getRoomParticipantUserIds(message.getChatRoomId());
    boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

    if (handleRoomFullIfNeeded(message, currentUserId, alreadyJoined)) {
        return;
    }

    registerParticipant(message, sessionId);

    if (alreadyJoined) {
        log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                message.getUsername(), message.getChatRoomId());
        return;
    }

    broadcastJoin(message);
}
```

- [ ] **Step 6: 컴파일 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon -q
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 테스트 실행**

```bash
./gradlew :chat-service:test --no-daemon -q 2>&1 | grep -E "PASS|FAIL|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java
git commit -m "refactor: UserPresenceService.join() — 4개 private 메서드로 분해"
```

---

## 셀프 리뷰

**Spec coverage:**
- ✅ Kafka 토픽 문자열 중복 제거 (T1)
- ✅ ES mustNot SYSTEM 필터 중복 제거 (T2)
- ✅ ES 인덱스명 상수화 (T2)
- ✅ UserPresenceService.join() 긴 메서드 분해 (T3)
- ⬜ AiSummaryService God Class 분해 — 범위 너무 넓어 별도 플랜 필요
- ⬜ catch-all Exception → 구체적 예외 — 별도 이슈로 추적

**Type consistency:** T1에서 도입한 `KafkaTopics.CHAT_MESSAGES`가 T3의 `broadcastJoin()` 에서도 사용됨 — 일치.

**Placeholder scan:** 없음.
