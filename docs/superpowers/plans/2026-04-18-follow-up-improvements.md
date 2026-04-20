# ChatFlow Follow-up Improvements Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코드 리뷰에서 발견된 잔여 이슈 5개를 수정한다 — TOCTOU 방지, Redis N+1 제거, _localId 페이로드 오염, Sidebar 불필요 폴링, 데드코드 정리.

**Architecture:** Backend 3개(ChatRoomService @Transactional, Redis MGET, 데드코드)와 Frontend 2개(_localId cleanup, Sidebar 폴링 일시정지)는 서로 독립적으로 실행 가능하다. 두 서브시스템을 병렬 실행하려면 별도 플랜으로 분리를 고려하라.

**Tech Stack:** Spring Boot 3.2 (JPA, Spring Data Redis), Flutter 3.22 (Riverpod, stomp_dart_client)

---

## 개선 대상 요약

| # | 영역 | 심각도 | 설명 |
|---|------|--------|------|
| 1 | Backend | High | `findOrCreateAvailableRoom` @Transactional 누락 → TOCTOU 중복 방 생성 |
| 2 | Backend | High | `getUnreadCounts` Redis N번 RTT → MGET으로 1 RTT로 단축 |
| 3 | Frontend | Medium | `sendMessage` — `_localId` 키가 STOMP 페이로드에 포함되어 서버 전달 |
| 4 | Frontend | Medium | `ChatRoomSidebar` — STOMP 미연결(오프라인) 상태에서도 10초마다 폴링 |
| 5 | Backend | Low | `ChatRoomRepository.findAllByOrderByCreatedAtDesc` 데드코드 제거 |

---

## File Map

| 파일 | 변경 유형 | 담당 태스크 |
|------|-----------|------------|
| `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:212` | Modify (@Transactional 추가) | Task 1 |
| `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:354-372` | Modify (MGET 교체) | Task 2 |
| `chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java:15` | Modify (메서드 제거) | Task 5 |
| `frontend/lib/features/chat/chat_provider.dart:786-791` | Modify (_localId 제거 후 전송) | Task 3 |
| `frontend/lib/features/chat/widgets/chat_room_sidebar.dart:27-44` | Modify (연결 상태 가드) | Task 4 |

---

## Task 1: findOrCreateAvailableRoom @Transactional 추가 (TOCTOU 방지)

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:212`

**문제**: 두 요청이 동시에 "빈 방 없음"을 확인하면 둘 다 새 방을 생성할 수 있다. `findAvailableByBaseName` 결과가 비어있는 시점과 `save()` 사이에 경쟁 조건 존재.

**해결**: `@Transactional`을 추가해 조회~저장을 단일 트랜잭션으로 묶고, name 유니크 제약 위반 시 재조회로 기존 방 반환.

- [ ] **Step 1: 현재 메서드 확인**

```bash
grep -n "@Transactional\|findOrCreateAvailable" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java
```

Expected: `findOrCreateAvailableRoom` 라인에 `@Transactional` 없음

- [ ] **Step 2: @Transactional + DataIntegrityViolationException 방어 추가**

`ChatRoomService.java` 212라인의 `public ChatRoom findOrCreateAvailableRoom(String baseName)` 앞에 `@Transactional` 추가, 본문 하단 `save()` 호출을 try-catch로 감싸기:

```java
@Transactional
public ChatRoom findOrCreateAvailableRoom(String baseName) {
    String escapedPattern = baseName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    List<ChatRoom> available = chatRoomRepository.findAvailableByBaseName(baseName, escapedPattern);

    if (!available.isEmpty()) {
        return available.get(0);
    }

    long count = (long) chatRoomRepository.findByBaseName(baseName, escapedPattern).size();

    String newName = ChatRoom.nextOverflowName(baseName, count);
    ChatRoom newRoom = ChatRoom.builder()
            .id("room_" + UUID.randomUUID().toString())
            .name(newName)
            .description(baseName + " 채팅방 (자동 생성)")
            .color("#6366f1")
            .participantCount(0)
            .maxParticipants(MAX_PARTICIPANTS)
            .createdAt(LocalDateTime.now())
            .build();

    try {
        ChatRoom saved = chatRoomRepository.save(newRoom);
        evictRoomCaches(saved.getId());
        log.info("Auto-created overflow room: {} ({})", saved.getName(), saved.getId());
        return saved;
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
        // 동시 요청이 동일 방을 생성한 경우 — 이미 생성된 방 반환
        log.warn("Concurrent room creation detected for '{}', returning existing room", newName);
        List<ChatRoom> retry = chatRoomRepository.findByBaseName(newName, newName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
        if (!retry.isEmpty()) return retry.get(0);
        throw e;
    }
}
```

**주의**: `import org.springframework.dao.DataIntegrityViolationException;`이 이미 import되어 있는지 확인. 없으면 파일 상단 import 블록에 추가.

- [ ] **Step 3: 빌드 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java
git commit -m "$(cat <<'EOF'
fix: findOrCreateAvailableRoom @Transactional + 동시 생성 DataIntegrityViolation 방어

@Transactional 없이 조회-저장 사이 TOCTOU로 중복 방이 생성될 수 있었음.
@Transactional 추가 + save() 시 DataIntegrityViolationException 발생 시
기존 방을 재조회해 반환하는 retry 패턴 적용.

Constraint: ChatRoom.name에 유니크 제약이 있어야 DataIntegrityViolation 발생
Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: getUnreadCounts Redis MGET 최적화 (N RTT → 1 RTT)

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:354-372`

**문제**: 현재 roomId 개수만큼 Redis `get()` 호출. 채팅방 20개면 20번 RTT. 초당 여러 사용자가 사이드바를 갱신하면 Redis 부하 집중.

**해결**: `redisTemplate.opsForValue().multiGet(keys)` 1번으로 모든 readAt 값을 한꺼번에 조회.

- [ ] **Step 1: 현재 메서드 확인**

```bash
grep -n "getUnreadCounts\|multiGet\|opsForValue" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java | head -20
```

Expected: `getUnreadCounts` 내부에 `opsForValue().get(` 루프 존재, `multiGet` 없음

- [ ] **Step 2: MGET 기반으로 교체**

`getUnreadCounts` 메서드 전체 교체 (354~372라인):

```java
public Map<String, Long> getUnreadCounts(String userId, List<String> roomIds) {
    if (roomIds.isEmpty()) return Collections.emptyMap();

    List<String> keys = roomIds.stream()
            .map(id -> "chatflow:readat:" + id + ":" + userId)
            .collect(java.util.stream.Collectors.toList());

    // N번 RTT → 1번 RTT
    List<String> values = redisTemplate.opsForValue().multiGet(keys);

    Map<String, Long> result = new LinkedHashMap<>();
    for (int i = 0; i < roomIds.size(); i++) {
        String roomId = roomIds.get(i);
        String readAtStr = (values != null) ? values.get(i) : null;
        try {
            if (readAtStr == null) {
                result.put(roomId, chatMessageRepository.countNewChatMessages(
                        roomId, LocalDateTime.of(2000, 1, 1, 0, 0)));
            } else {
                LocalDateTime readAt = LocalDateTime.parse(readAtStr);
                result.put(roomId, chatMessageRepository.countNewChatMessages(roomId, readAt));
            }
        } catch (Exception e) {
            result.put(roomId, 0L);
        }
    }
    return result;
}
```

필요한 import (없으면 추가):
```java
import java.util.Collections;
import java.util.stream.Collectors;
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java
git commit -m "$(cat <<'EOF'
perf: getUnreadCounts Redis 개별 get N회 → multiGet 1회 RTT로 최적화

채팅방 N개당 N번의 Redis 왕복을 multiGet으로 1회로 단축.
DB countNewChatMessages는 변경 없음 (향후 batch 최적화 대상).

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: sendMessage — _localId STOMP 페이로드 오염 수정

**Files:**
- Modify: `frontend/lib/features/chat/chat_provider.dart:786-791`

**문제**: `msg` 맵에 `'_localId': localId`가 포함된 채로 `_stompService.sendMessage(msg)` 호출. 서버 Jackson 역직렬화가 `@JsonIgnoreProperties(ignoreUnknown=true)` 설정이면 무해하지만, 불필요한 키가 서버에 전달된다. 오프라인 큐에는 dedup용으로 `_localId`를 유지해야 하므로 STOMP 전송 직전에만 제거한다.

- [ ] **Step 1: 현재 sendMessage 코드 확인**

```bash
grep -n "_localId\|sendMessage" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/features/chat/chat_provider.dart | head -15
```

Expected: `_localId` 키가 msg 맵에 있고 그대로 sendMessage에 전달됨

- [ ] **Step 2: STOMP 전송 직전 _localId 제거**

`chat_provider.dart`의 sendMessage 메서드에서 STOMP 전송 부분(786-791라인):

현재:
```dart
    state = state.copyWith(messages: [...state.messages, localMsg]);
    if (_stompService.isConnected) {
      _stompService.sendMessage(msg);
    } else {
      _offlineQueue.add(msg);
    }
```

수정 후:
```dart
    state = state.copyWith(messages: [...state.messages, localMsg]);
    if (_stompService.isConnected) {
      final sendPayload = Map<String, dynamic>.from(msg)..remove('_localId');
      _stompService.sendMessage(sendPayload);
    } else {
      _offlineQueue.add(msg);  // 큐에는 _localId 유지 (flush dedup용)
    }
```

- [ ] **Step 3: analyze 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend
flutter analyze --no-fatal-infos 2>&1 | grep "^error" | head -10
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/lib/features/chat/chat_provider.dart
git commit -m "$(cat <<'EOF'
fix: sendMessage STOMP 페이로드에서 _localId 제거 (서버 전달 방지)

오프라인 큐 dedup을 위해 msg 맵에 보관하던 _localId를
STOMP 전송 직전 복사본에서만 제거. 오프라인 큐는 _localId 유지.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ChatRoomSidebar 오프라인 폴링 일시정지

**Files:**
- Modify: `frontend/lib/features/chat/widgets/chat_room_sidebar.dart:27-44`

**문제**: `Timer.periodic(10초)`가 STOMP 연결 상태와 무관하게 항상 실행. 오프라인 시 `fetchRooms()`와 `fetchUnreadCounts()`는 Dio 에러를 발생시키고 UI 에러 로그를 채운다. 연결 복구 시 타이머가 자동으로 재개되면 충분하므로 타이머 자체를 끄는 것보다 **콜백 내에서 연결 상태를 체크**하는 방식이 가장 단순하다.

**해결**: Timer 콜백에서 `chatRoomsProvider` 상태의 연결 플래그를 확인하거나, `chatNotifierProvider`의 isConnected를 확인 후 미연결 시 스킵.

- [ ] **Step 1: 현재 Sidebar 코드 확인**

```bash
cat -n /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/features/chat/widgets/chat_room_sidebar.dart | head -60
```

Expected: `Timer.periodic(const Duration(seconds: 10), (_) async { ... fetchRooms ... fetchUnreadCounts ...}` 패턴

- [ ] **Step 2: isConnected 체크로 폴링 가드 추가**

sidebar에서 현재 선택된 roomId를 알아야 chatNotifierProvider를 참조할 수 있다. 대신 **HTTP API 자체가 연결 없어도 성공할 수 있으므로** (게이트웨이는 WebSocket과 별도), 더 적절한 가드는 **앱이 백그라운드가 아닐 때만** 폴링하는 것이다.

그러나 STOMP 연결 상태가 오프라인(네트워크 없음)을 나타내므로, StompService의 `isConnected`를 임계값으로 쓰는 것은 합리적이다. Flutter에서 StompService 싱글턴에 접근하려면 Provider를 통해야 한다.

`chat_room_sidebar.dart`에서 StompService 접근 방법 확인:
```bash
grep -n "stompService\|StompService\|isConnected\|chatNotifier" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/features/chat/widgets/chat_room_sidebar.dart
```

만약 StompService provider가 없다면 `stompServiceProvider`를 확인:
```bash
grep -rn "stompServiceProvider\|Provider.*StompService" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/
```

- [ ] **Step 3: 연결 상태 가드 구현**

`_refreshTimer` 콜백에서 `stompServiceProvider`(또는 `chatNotifierProvider`)의 isConnected 확인 후 미연결 시 스킵:

```dart
// initState 내 Timer.periodic 콜백 수정:
_refreshTimer = Timer.periodic(const Duration(seconds: 10), (_) async {
  if (_disposed) return;
  // 네트워크 오프라인 시 불필요한 API 호출 스킵
  final isConnected = ref.read(stompServiceProvider).isConnected;
  if (!isConnected) return;
  ref.read(chatRoomsProvider.notifier).fetchRooms();
  final counts = await ref.read(chatRoomsProvider.notifier).fetchUnreadCounts();
  if (!_disposed && counts.isNotEmpty) {
    final current = Map<String, int>.from(ref.read(roomUnreadCountsProvider));
    current.addAll(counts);
    ref.read(roomUnreadCountsProvider.notifier).state = current;
  }
});
```

**만약 `stompServiceProvider`가 없다면** 먼저 확인:
```bash
grep -rn "stompServiceProvider" /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/
```

없으면 `frontend/lib/core/network/stomp_service.dart` 하단이나 별도 provider 파일에 추가:
```dart
// stomp_service.dart 또는 providers.dart에 추가
final stompServiceProvider = Provider<StompService>((ref) => StompService());
```

**주의**: StompService가 이미 chatNotifierProvider 내부에서 생성된다면 sinlgeton 패턴이 깨질 수 있다. 이 경우 `chatNotifier`에서 isConnected getter를 노출하는 방법을 사용:
```dart
// chat_provider.dart ChatNotifier에 getter 추가
bool get isConnected => _stompService.isConnected;

// sidebar에서:
final roomId = widget.selectedRoomId;
if (roomId != null) {
  final isConnected = ref.read(chatNotifierProvider(roomId)).isConnected; // 안됨 — roomId 없음
}
```

roomId 없이 접근 불가하면 `AppLifecycleState`를 이용:
```dart
// _ChatRoomSidebarState에 WidgetsBindingObserver 추가
class _ChatRoomSidebarState extends ConsumerState<ChatRoomSidebar>
    with WidgetsBindingObserver {
  // ...
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // ...
  }
  
  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // ...
  }
  
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _refreshTimer?.cancel();
      _refreshTimer = null;
    } else if (state == AppLifecycleState.resumed && _refreshTimer == null) {
      _startRefreshTimer();
    }
  }
```

**실제 구현 전 먼저 탐색**: 위 3가지 방법 중 코드베이스 패턴에 맞는 것을 선택하라. `stompServiceProvider`가 이미 있으면 방법 1, 없고 chatNotifier에 접근 가능하면 getter 추가, 없으면 AppLifecycleState 사용.

- [ ] **Step 4: analyze 확인**

```bash
flutter analyze --no-fatal-infos 2>&1 | grep "^error" | head -10
```

- [ ] **Step 5: 커밋**

```bash
git add frontend/lib/features/chat/widgets/chat_room_sidebar.dart
# stompServiceProvider 추가했다면 해당 파일도 포함
git commit -m "$(cat <<'EOF'
fix: ChatRoomSidebar 오프라인 시 불필요한 폴링 스킵

STOMP 미연결(네트워크 없음) 상태에서 10초마다 fetchRooms/fetchUnreadCounts를
호출하던 동작 제거. 연결 상태 확인 후 미연결 시 콜백 조기 반환.

Confidence: medium
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ChatRoomRepository 데드코드 제거

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java:15`

**문제**: `findAllByOrderByCreatedAtDesc()` 메서드가 어디서도 호출되지 않는다. Task 5(어제 작업)에서 `findOrCreateAvailableRoom`이 `findAvailableByBaseName`/`findByBaseName`으로 교체되며 유일한 호출처가 사라졌다.

- [ ] **Step 1: 사용처 확인**

```bash
grep -rn "findAllByOrderByCreatedAtDesc" \
  /Users/seungwon-kwon/IdeaProjects/chat_flow/chat-service/src/
```

Expected: `ChatRoomRepository.java`에만 선언, 다른 파일에서 호출 없음

- [ ] **Step 2: 메서드 제거**

`ChatRoomRepository.java` 15라인의:
```java
List<ChatRoom> findAllByOrderByCreatedAtDesc();
```
이 한 줄을 삭제.

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java
git commit -m "$(cat <<'EOF'
chore: findAllByOrderByCreatedAtDesc 데드코드 제거

findOrCreateAvailableRoom이 findAvailableByBaseName/findByBaseName으로 교체되며
findAllByOrderByCreatedAtDesc의 유일한 호출처가 사라짐. 미사용 메서드 제거.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

### Spec 커버리지 체크
| 발견된 이슈 | Task | 커버됨 |
|------------|------|--------|
| findOrCreateAvailableRoom TOCTOU | Task 1 | ✓ |
| getUnreadCounts Redis N+1 | Task 2 | ✓ |
| sendMessage _localId STOMP 오염 | Task 3 | ✓ |
| Sidebar 오프라인 폴링 | Task 4 | ✓ |
| findAllByOrderByCreatedAtDesc 데드코드 | Task 5 | ✓ |

### Placeholder 검사
- Task 4 Step 3에 3가지 구현 방법 제시 — 탐색 결과에 따라 선택하라는 가이드 포함 ✓ (TBD 아님, 조건부 실제 코드)
- 나머지 모든 Task에 실제 코드 블록 포함 ✓

### 타입 일관성 검사
- Task 2: `values.get(i)` — `multiGet`이 `List<String>`을 반환하므로 인덱스 접근 올바름 ✓
- Task 1: `DataIntegrityViolationException` — catch 블록과 throw 타입 일치 ✓
- Task 3: `Map<String, dynamic>.from(msg)..remove('_localId')` — Flutter/Dart 문법 올바름 ✓

### 실행 순서 권장
- Task 5 (데드코드) → Task 1 (TOCTOU) → Task 2 (Redis MGET): 백엔드 순차 실행
- Task 3 → Task 4: 프론트엔드 순차 실행 (Task 4는 탐색이 필요하므로 3 먼저)
- Backend와 Frontend는 병렬로 실행 가능

---

## 다음 세션 후보 (이번 플랜 범위 밖)

아래 항목은 이번 플랜에 포함하지 않았으나 추후 개선 대상:

| 항목 | 이유 |
|------|------|
| AI Summary Kafka Consumer async 처리 | AI Summary 서비스 전체 리팩터링 필요 |
| Flyway 마이그레이션 도입 | ddl-auto는 이미 `validate`로 안전함 — 긴급하지 않음 |
| JPQL LIKE ESCAPE 절 명시 | 현 PostgreSQL 기본 설정에서 동작함 — 이식성 개선만 |
| Kafka 파티션 수 증가 | 운영 부하 확인 후 결정 필요 |
| LoginRateLimitFilter X-Forwarded-For 강화 | K3s 뒤에 Cloudflare가 있어 현재 안전 |
