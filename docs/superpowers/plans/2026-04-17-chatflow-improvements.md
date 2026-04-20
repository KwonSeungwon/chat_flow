# ChatFlow Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코드베이스 분석에서 발견된 6가지 버그·보안 취약점을 수정하고, 미커밋 작업을 커밋한다.

**Architecture:** Flutter 프론트엔드(GoRouter/Riverpod/STOMP) + Spring Boot 백엔드(JPA/Redis)를 다루는 멀티레이어 수정. 각 태스크는 독립적으로 실행 가능.

**Tech Stack:** Flutter 3.22, Riverpod 2.5, GoRouter 14, Spring Boot 3.2, JPA, Redis (Lettuce), PostgreSQL 16

---

## 개선 대상 요약

| # | 영역 | 심각도 | 설명 |
|---|------|--------|------|
| 0 | Git | - | 미커밋 변경사항(13 modified + 3 untracked) 커밋 |
| 1 | Frontend | **Critical** | StompService disconnect 시 토큰·콜백 미정리 |
| 2 | Frontend | **High** | GoRouter async redirect — auth hydration 전 깜빡임 |
| 3 | Frontend | **High** | 오프라인 큐 dedup — 해시 기반 ID로 메시지 오탈락 가능 |
| 4 | Backend | **High** | batch DELETE native query — PostgreSQL ORDER BY 누락 |
| 5 | Backend | **Medium** | ChatRoomService.findOrCreateAvailableRoom N+1 쿼리 |
| 6 | Backend | **Low** | setParticipantCount 불필요한 명시적 save() |

---

## File Map

| 파일 | 변경 유형 | 담당 태스크 |
|------|-----------|------------|
| `frontend/lib/core/network/stomp_service.dart` | Modify:231-242 | Task 1 |
| `frontend/lib/core/routing/app_router.dart` | Modify (전체 재작성) | Task 2 |
| `frontend/lib/features/auth/auth_provider.dart` | Modify (isHydrated 추가) | Task 2 |
| `frontend/lib/features/chat/chat_provider.dart:765-806` | Modify | Task 3 |
| `frontend/lib/shared/models/chat_message.dart:137` | Modify | Task 3 |
| `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java:27-28` | Modify | Task 4 |
| `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:213-239` | Modify | Task 5 |
| `chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java` | Modify (쿼리 추가) | Task 5 |
| `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:194-201` | Modify | Task 6 |

---

## Task 0: 미커밋 변경사항 커밋

**Files:**
- Stage all: 13 modified + 3 untracked (`LoginRateLimitFilter.java`, `RateLimiterConfig.java`, `FallbackController.java`)

- [ ] **Step 1: 변경사항 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow
git status --short
git diff --stat
```

Expected: 13 modified files + 3 untracked listed

- [ ] **Step 2: 스테이지 및 커밋**

```bash
git add \
  chat-service/src/main/java/com/chatflow/chat/config/JwtAuthFilter.java \
  chat-service/src/main/java/com/chatflow/chat/config/SecurityConfig.java \
  chat-service/src/main/java/com/chatflow/chat/config/WebSocketEventListener.java \
  chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java \
  chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java \
  chat-service/src/main/java/com/chatflow/chat/service/MessageRetentionService.java \
  chat-service/src/main/java/com/chatflow/chat/service/MessageSenderService.java \
  chat-service/src/main/resources/application-prod.yml \
  frontend/lib/features/chat/chat_provider.dart \
  gateway-service/src/main/java/com/chatflow/gateway/security/AuthService.java \
  gateway-service/src/main/java/com/chatflow/gateway/security/SecurityConfig.java \
  gateway-service/src/main/resources/application-prod.yml \
  k8s/infra/k3s-infra.yaml \
  gateway-service/src/main/java/com/chatflow/gateway/config/LoginRateLimitFilter.java \
  gateway-service/src/main/java/com/chatflow/gateway/config/RateLimiterConfig.java \
  gateway-service/src/main/java/com/chatflow/gateway/controller/FallbackController.java
```

```bash
git commit -m "$(cat <<'EOF'
feat: 3-tier rate limit + circuit breaker + leaveRoom @Transactional 수정

- Gateway: LoginRateLimitFilter (IP 기반, 10req/60s)
- Gateway: RateLimiterConfig (userKeyResolver @Primary)
- Gateway: FallbackController (/api/fallback/*)
- Gateway: application-prod.yml — 서비스별 CB + RateLimiter 라우트 필터
- Chat: leaveRoom @Transactional (CGLIB 자기호출 수정)
- Chat: MessageRetentionService 배치 DELETE (5000/batch)
- Chat: JwtAuthFilter·SecurityConfig·WebSocketEventListener 보안 강화
- Frontend: leaveRoom 성공 후 fetchRooms() + dispose 이중호출 제거
- K8s: PostgreSQL secretKeyRef, Valkey requirepass

Constraint: 게이트웨이 자체 컨트롤러(/api/auth/**)는 라우트 필터 미적용 — WebFilter로 별도 처리
Confidence: high
Scope-risk: broad

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

Expected: `[develop <hash>] feat: 3-tier rate limit...`

---

## Task 1: StompService — disconnect 시 민감 필드 정리 (보안)

**파일:**
- Modify: `frontend/lib/core/network/stomp_service.dart:231-242`

**문제**: `disconnect()` 호출 후 `_currentToken`, `_tokenProvider`, `_currentUsername`, `_currentUserId`가 메모리에 잔존. 로그아웃 후 재연결 시 오래된 토큰으로 동작할 수 있음.

- [ ] **Step 1: disconnect() 수정**

`frontend/lib/core/network/stomp_service.dart`의 `disconnect()` 메서드:

현재 코드 (231-242):
```dart
  void disconnect() {
    _manualDisconnect = true;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _currentRoomId = null;
    _onMessage = null;
    _onConnectionChanged = null;
    _onReadReceipt = null;
    _onTyping = null;
    _onRoomFull = null;
  }
```

수정 후:
```dart
  void disconnect() {
    _manualDisconnect = true;
    _reconnectTimer?.cancel();
    _client?.deactivate();
    _connected = false;
    _currentRoomId = null;
    _currentToken = null;
    _tokenProvider = null;
    _currentUsername = null;
    _currentUserId = null;
    _onMessage = null;
    _onConnectionChanged = null;
    _onReadReceipt = null;
    _onTyping = null;
    _onRoomFull = null;
  }
```

- [ ] **Step 2: 검증 — connect() 호출 시 필드가 올바르게 재설정되는지 확인**

`connect()` 메서드 첫 부분(44-50라인)이 아래처럼 필드를 명시적으로 재할당함을 확인:
```dart
  void connect({...}) {
    _currentRoomId = roomId;
    _currentUsername = username;
    _currentUserId = userId;
    _currentToken = token;
    _tokenProvider = tokenProvider;
```

이미 올바르게 재할당하므로 disconnect 후 null 설정해도 재연결에 문제없음. ✓

- [ ] **Step 3: 커밋**

```bash
git add frontend/lib/core/network/stomp_service.dart
git commit -m "$(cat <<'EOF'
fix(security): StompService disconnect 시 토큰·자격증명 즉시 정리

logout 후 메모리에 JWT 토큰 잔존 방지.
_currentToken, _tokenProvider, _currentUsername, _currentUserId를
disconnect() 에서 null 처리. connect() 는 재연결 시 명시적 재설정.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: GoRouter async hydration 경쟁 조건 수정

**파일:**
- Modify: `frontend/lib/features/auth/auth_provider.dart` (isHydrated 필드 추가)
- Modify: `frontend/lib/core/routing/app_router.dart` (RouterNotifier + refreshListenable)

**문제**: `GoRouter.redirect`가 매 탐색마다 `FlutterSecureStorage`를 비동기 읽음. AuthNotifier 초기화(`_hydrate()`) 완료 전에 redirect가 실행되면 `/login`으로 깜빡임 후 `/chat`으로 되돌아가는 UX 불량.

**해결**: `RouterNotifier`(ChangeNotifier)가 authProvider를 watch하고, GoRouter가 이를 `refreshListenable`로 사용. redirect에서는 스토리지 읽기 대신 이미 hydrate된 상태를 사용.

- [ ] **Step 1: AuthState에 isHydrated 필드 추가**

`frontend/lib/features/auth/auth_provider.dart`:

```dart
class AuthState {
  final String? token;
  final String? userId;
  final String username;
  final String role;
  final String? profileImageUrl;
  final bool isLoading;
  final String? error;
  final bool isHydrated;  // ← 추가

  const AuthState({
    this.token,
    this.userId,
    this.username = '',
    this.role = 'NURSE',
    this.profileImageUrl,
    this.isLoading = false,
    this.error,
    this.isHydrated = false,  // ← 추가 (기본값 false)
  });

  bool get isAuthenticated => token != null;

  AuthState copyWith({
    String? token,
    String? userId,
    String? username,
    String? role,
    String? profileImageUrl,
    bool? isLoading,
    String? error,
    bool? isHydrated,  // ← 추가
  }) {
    return AuthState(
      token: token ?? this.token,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      role: role ?? this.role,
      profileImageUrl: profileImageUrl ?? this.profileImageUrl,
      isLoading: isLoading ?? this.isLoading,
      error: error,
      isHydrated: isHydrated ?? this.isHydrated,  // ← 추가
    );
  }
}
```

- [ ] **Step 2: `_hydrate()` 완료 시 isHydrated = true 설정**

`auth_provider.dart`의 `_hydrate()`:

```dart
  Future<void> _hydrate() async {
    try {
      final token = await _storage.read(key: StorageKeys.token);
      final userId = await _storage.read(key: StorageKeys.userId);
      final username = await _storage.read(key: StorageKeys.username);
      if (token != null) {
        final role = await _storage.read(key: StorageKeys.role);
        final profileImageUrl = await _storage.read(key: StorageKeys.profileImage);
        state = AuthState(
          token: token,
          userId: userId,
          username: username ?? '',
          role: role ?? 'NURSE',
          profileImageUrl: profileImageUrl,
          isHydrated: true,   // ← 추가
        );
      } else {
        state = const AuthState(isHydrated: true);   // ← 추가 (token 없어도 hydrated)
      }
    } catch (e) {
      debugPrint('[AuthNotifier] _hydrate error: $e');
      state = const AuthState(isHydrated: true);   // ← 추가
    }
  }
```

**주의**: 기존 코드에서 token == null 일 때 state 변경이 없었음. `state = const AuthState(isHydrated: true)` 라인을 else 분기에 추가.

- [ ] **Step 3: RouterNotifier + GoRouter 재작성**

`frontend/lib/core/routing/app_router.dart` 전체 교체:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/auth/login_page.dart';
import '../../features/chat/chat_page.dart';
import '../../features/search/search_page.dart';

class _RouterNotifier extends ChangeNotifier {
  final Ref _ref;
  late final ProviderSubscription<AuthState> _sub;

  _RouterNotifier(this._ref) {
    _sub = _ref.listen<AuthState>(authProvider, (_, __) => notifyListeners());
  }

  @override
  void dispose() {
    _sub.close();
    super.dispose();
  }

  String? redirect(BuildContext context, GoRouterState state) {
    final auth = _ref.read(authProvider);
    if (!auth.isHydrated) return null;   // hydration 미완료 — 대기
    final isLoginPage = state.matchedLocation == '/login';
    if (!auth.isAuthenticated && !isLoginPage) return '/login';
    if (auth.isAuthenticated && isLoginPage) return '/chat';
    return null;
  }
}

final _routerNotifierProvider = Provider<_RouterNotifier>((ref) {
  final notifier = _RouterNotifier(ref);
  ref.onDispose(notifier.dispose);
  return notifier;
});

final appRouterProvider = Provider<GoRouter>((ref) {
  final notifier = ref.watch(_routerNotifierProvider);
  return GoRouter(
    initialLocation: '/chat',
    refreshListenable: notifier,
    redirect: notifier.redirect,
    routes: [
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginPage(),
      ),
      GoRoute(
        path: '/chat',
        builder: (context, state) => const ChatPage(),
        routes: [
          GoRoute(
            path: ':roomId',
            builder: (context, state) {
              final roomId = state.pathParameters['roomId'];
              final messageId = state.uri.queryParameters['messageId'];
              return roomId != null
                  ? ChatPage(roomId: roomId, scrollToMessageId: messageId)
                  : const ChatPage();
            },
          ),
        ],
      ),
      GoRoute(
        path: '/search',
        builder: (context, state) => const SearchPage(),
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text('Page not found: ${state.error}')),
    ),
  );
});
```

- [ ] **Step 4: authProvider import 선언 확인**

`auth_provider.dart` 파일에 `authProvider` Provider가 있는지 확인:
```bash
grep -n "authProvider" /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/lib/features/auth/auth_provider.dart | head -5
```

없으면 파일 하단에 추가:
```dart
final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  return AuthNotifier(ref.read(dioClientProvider));
});
```

(이미 존재하면 스킵)

- [ ] **Step 5: Flutter 앱 빌드 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend
flutter analyze --no-fatal-infos 2>&1 | grep -E "error:|Error" | head -20
```

Expected: 에러 없음 (warning은 무시)

- [ ] **Step 6: 커밋**

```bash
git add \
  frontend/lib/features/auth/auth_provider.dart \
  frontend/lib/core/routing/app_router.dart
git commit -m "$(cat <<'EOF'
fix: GoRouter auth hydration 경쟁 조건 수정 (RouterNotifier + isHydrated)

FlutterSecureStorage 비동기 읽기를 redirect 콜백에서 제거.
RouterNotifier(ChangeNotifier)가 authProvider를 구독하고
isHydrated=false 동안 redirect를 null로 차단.
_hydrate() 완료(성공/실패/토큰없음 모두) 시 isHydrated=true 설정.

Rejected: 기존 방식(redirect 내 async 스토리지 읽기) | hydration 완료 전 /login 깜빡임
Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 오프라인 큐 dedup — UUID 로컬 ID 기반으로 교체

**파일:**
- Modify: `frontend/lib/shared/models/chat_message.dart:137` (effectiveId 변경 없음, localId 필드 추가)
- Modify: `frontend/lib/features/chat/chat_provider.dart:765-806`

**문제**: `_flushOfflineQueue`에서 로컬 메시지를 `timestamp+username+content.hashCode` 해시 조합으로 식별. 동일 내용 반복 메시지나 해시 충돌 시 엉뚱한 메시지가 제거될 수 있음.

**해결**: 로컬 메시지 생성 시 UUID를 `localId` 필드로 할당하고, 큐 map에 `_localId` 키로 저장. flush 시 `localId`로 정확히 매칭.

- [ ] **Step 1: ChatMessage에 localId 필드 추가**

`frontend/lib/shared/models/chat_message.dart`:

`final Map<String, List<String>> reactions;` 아래, `final MessageDeliveryStatus deliveryStatus;` 위에 추가:
```dart
  /// 오프라인 큐 dedup을 위한 클라이언트 전용 로컬 ID (서버에 전송되지 않음)
  final String? localId;
```

생성자에 추가:
```dart
  ChatMessage({
    // ... 기존 필드들 ...
    this.reactions = const {},
    this.localId,                        // ← 추가
    this.deliveryStatus = MessageDeliveryStatus.sent,
  });
```

`copyWith`에 추가 (copyWith가 있는 경우):
```dart
  // copyWith 메서드 내 localId 처리 추가
  String? localId,
  // ...
  localId: localId ?? this.localId,
```

**주의**: `fromJson`에는 추가하지 않음 — 서버에서 오는 메시지에는 localId 없음.

`effectiveId`는 변경 없음 (서버 ID 우선, fallback 해시는 유지).

- [ ] **Step 2: sendMessage에서 localId 생성 및 큐에 저장**

`frontend/lib/features/chat/chat_provider.dart`에 uuid 패키지 import 추가 (파일 상단):
```dart
import 'package:uuid/uuid.dart';
```

(uuid 패키지가 없으면 `pubspec.yaml`에 `uuid: ^4.0.0` 추가 후 `flutter pub get`)

`sendMessage` 메서드 수정 (765-791라인):
```dart
  void sendMessage({required String roomId, required String content, String priority = 'ROUTINE'}) {
    final reply = state.replyTarget;
    final localId = const Uuid().v4();         // ← 추가: 고유 로컬 ID 생성
    final msg = {
      'chatRoomId': roomId,
      'userId': _userId,
      'username': _username,
      'content': content,
      'type': 'CHAT',
      'priority': priority,
      'timestamp': DateTime.now().toIso8601String(),
      if (reply != null) 'parentMessageId': reply.effectiveId,
      '_localId': localId,               // ← 추가: 큐 식별용 (서버 무시)
    };
    final localMsg = ChatMessage(
      chatRoomId: roomId, userId: _userId, username: _username,
      content: content, type: 'CHAT', priority: priority,
      timestamp: msg['timestamp']!,
      parentMessageId: reply?.effectiveId,
      localId: localId,                  // ← 추가
      deliveryStatus: MessageDeliveryStatus.sending,
    );
    state = state.copyWith(messages: [...state.messages, localMsg]);
    if (_stompService.isConnected) {
      _stompService.sendMessage(msg);
    } else {
      _offlineQueue.add(msg);
    }
    if (reply != null) clearReplyTarget();
  }
```

- [ ] **Step 3: _flushOfflineQueue에서 localId 기반 dedup**

`_flushOfflineQueue` 교체 (794-806라인):
```dart
  void _flushOfflineQueue() {
    if (_offlineQueue.isEmpty) return;
    final queued = List<Map<String, dynamic>>.from(_offlineQueue);
    _offlineQueue.clear();
    // localId로 정확히 매칭하여 로컬 optimistic 메시지 제거
    final queuedLocalIds = queued
        .map((m) => m['_localId']?.toString())
        .whereType<String>()
        .toSet();
    final cleaned = state.messages
        .where((m) => m.localId == null || !queuedLocalIds.contains(m.localId))
        .toList();
    state = state.copyWith(messages: cleaned);
    for (final msg in queued) {
      _stompService.sendMessage(msg);
    }
  }
```

- [ ] **Step 4: pubspec.yaml 확인 및 uuid 패키지 추가**

```bash
grep "uuid" /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend/pubspec.yaml
```

없으면:
```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend
flutter pub add uuid
```

Expected: `pubspec.yaml`에 `uuid:` 추가됨

- [ ] **Step 5: analyze 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow/frontend
flutter analyze --no-fatal-infos 2>&1 | grep -E "^(error|Error)" | head -20
```

Expected: 에러 없음

- [ ] **Step 6: 커밋**

```bash
git add \
  frontend/lib/shared/models/chat_message.dart \
  frontend/lib/features/chat/chat_provider.dart \
  frontend/pubspec.yaml \
  frontend/pubspec.lock
git commit -m "$(cat <<'EOF'
fix: 오프라인 큐 dedup을 해시 기반에서 UUID localId 기반으로 교체

동일 내용 반복 메시지 또는 content.hashCode 충돌 시
_flushOfflineQueue가 무관한 메시지를 제거하던 버그 수정.
localId(UUID v4)를 ChatMessage에 추가하고 큐 map에 _localId로 저장.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ChatMessageRepository batch DELETE — PostgreSQL ORDER BY 추가

**파일:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java:27-28`

**문제**: 현재 native query:
```sql
DELETE FROM chat_messages WHERE id IN (
  SELECT id FROM chat_messages WHERE timestamp < :cutoff LIMIT :batchSize
)
```
PostgreSQL에서 서브쿼리의 LIMIT 전 ORDER BY 없으면 삭제 대상이 비결정적. 특히 복잡한 실행 계획에서 예상과 다른 행이 삭제될 수 있음.

- [ ] **Step 1: 쿼리 수정**

`ChatMessageRepository.java` 27-28라인:

현재:
```java
    @Query(value = "DELETE FROM chat_messages WHERE id IN (SELECT id FROM chat_messages WHERE timestamp < :cutoff LIMIT :batchSize)", nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
```

수정 후 (ORDER BY timestamp 추가):
```java
    @Query(value = "DELETE FROM chat_messages WHERE id IN (SELECT id FROM chat_messages WHERE timestamp < :cutoff ORDER BY timestamp LIMIT :batchSize)", nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
```

- [ ] **Step 2: 빌드 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add chat-service/src/main/java/com/chatflow/chat/repository/ChatMessageRepository.java
git commit -m "$(cat <<'EOF'
fix: batch DELETE 서브쿼리에 ORDER BY timestamp 추가 (PostgreSQL 결정적 삭제)

LIMIT 전 ORDER BY 없으면 삭제 대상 비결정적.
가장 오래된 메시지부터 batchSize개 삭제하도록 ORDER BY timestamp 추가.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ChatRoomService.findOrCreateAvailableRoom — N+1 쿼리 최적화

**파일:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java` (새 쿼리 추가)
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:213-239`

**문제**: `findOrCreateAvailableRoom`이 `findAllByOrderByCreatedAtDesc()`로 모든 방을 메모리 로드 후 스트림 2번 순회. 방이 많을수록 DB→메모리 부하 증가.

**해결**: 쿼리를 DB로 내려 이름 패턴 필터링 + 카운트를 한 번에 처리.

- [ ] **Step 1: ChatRoomRepository에 최적화 쿼리 추가**

`ChatRoomRepository.java` 파일 확인:
```bash
cat /Users/seungwon-kwon/IdeaProjects/chat_flow/chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java
```

아래 두 쿼리를 인터페이스에 추가:
```java
    @Query("SELECT r FROM ChatRoom r WHERE r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%') ORDER BY r.createdAt ASC")
    List<ChatRoom> findByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);

    @Query("SELECT r FROM ChatRoom r WHERE (r.name = :baseName OR r.name LIKE CONCAT(:escapedPattern, '-%')) AND r.participantCount < r.maxParticipants ORDER BY r.createdAt ASC")
    List<ChatRoom> findAvailableByBaseName(@Param("baseName") String baseName, @Param("escapedPattern") String escapedPattern);
```

- [ ] **Step 2: findOrCreateAvailableRoom 최적화**

`ChatRoomService.java` 213-239 라인 교체:

현재:
```java
    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        List<ChatRoom> rooms = chatRoomRepository.findAllByOrderByCreatedAtDesc();

        Optional<ChatRoom> available = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(java.util.regex.Pattern.quote(baseName) + "-\\d+"))
                .filter(r -> !r.isFull())
                .findFirst();

        if (available.isPresent()) {
            return available.get();
        }

        long count = rooms.stream()
                .filter(r -> r.getName().equals(baseName) || r.getName().matches(java.util.regex.Pattern.quote(baseName) + "-\\d+"))
                .count();
```

수정 후:
```java
    public ChatRoom findOrCreateAvailableRoom(String baseName) {
        String escapedPattern = baseName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<ChatRoom> available = chatRoomRepository.findAvailableByBaseName(baseName, escapedPattern);

        if (!available.isEmpty()) {
            return available.get(0);
        }

        long count = chatRoomRepository.findByBaseName(baseName, escapedPattern).size();
```

나머지 부분(229~239)은 그대로 유지.

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  chat-service/src/main/java/com/chatflow/chat/repository/ChatRoomRepository.java \
  chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java
git commit -m "$(cat <<'EOF'
perf: findOrCreateAvailableRoom 전체 방 로드 제거 — DB 필터 쿼리로 최적화

findAllByOrderByCreatedAtDesc() + 메모리 스트림 2회 순회를
이름 패턴 기반 DB 쿼리 2개(findAvailableByBaseName, findByBaseName)로 교체.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: ChatRoomService.setParticipantCount — 불필요한 명시적 save() 제거

**파일:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java:194-201`

**문제**: `@Transactional` 메서드 내에서 JPA dirty checking이 자동으로 변경사항을 감지해 flush. 명시적 `save()` 호출은 중복 UPDATE 쿼리 발생 가능.

- [ ] **Step 1: 명시적 save() 제거**

현재 (194-201라인):
```java
    @Transactional
    public void setParticipantCount(String roomId, int count) {
        chatRoomRepository.findById(roomId).ifPresent(room -> {
            room.setParticipantCount(count);
            chatRoomRepository.save(room);
        });
        evictRoomCaches(roomId);
    }
```

수정 후:
```java
    @Transactional
    public void setParticipantCount(String roomId, int count) {
        chatRoomRepository.findById(roomId).ifPresent(room ->
            room.setParticipantCount(count)
        );
        evictRoomCaches(roomId);
    }
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/ChatRoomService.java
git commit -m "$(cat <<'EOF'
refactor: setParticipantCount 불필요한 명시적 save() 제거

@Transactional 내 dirty checking이 자동 flush하므로
chatRoomRepository.save() 명시 호출 불필요. 중복 UPDATE 제거.

Confidence: high
Scope-risk: narrow

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

### Spec 커버리지 체크
| 발견된 이슈 | Task |
|------------|------|
| StompService 토큰 잔존 | Task 1 ✓ |
| GoRouter async hydration 경쟁 | Task 2 ✓ |
| Offline queue 해시 dedup | Task 3 ✓ |
| Batch DELETE ORDER BY 누락 | Task 4 ✓ |
| findOrCreateAvailableRoom N+1 | Task 5 ✓ |
| setParticipantCount 중복 save | Task 6 ✓ |
| 미커밋 변경사항 | Task 0 ✓ |

### Placeholder 검사
- 모든 코드 블록에 실제 구현 코드 포함 ✓
- 모든 명령어에 기대 출력 포함 ✓
- "TBD" 없음 ✓

### 타입 일관성 검사
- `AuthState.isHydrated` — Task 2 Step 1에서 정의, Step 2에서 사용 ✓
- `ChatMessage.localId` — Task 3 Step 1에서 정의, Step 2에서 `localId:` 파라미터로 전달 ✓
- `findByBaseName`, `findAvailableByBaseName` — Task 5 Step 1에서 정의, Step 2에서 호출 ✓

### 독립성 검사
- Task 1, 2, 3는 Frontend 전용 — 서로 독립 (병렬 실행 가능)
- Task 4, 5, 6는 Backend 전용 — 서로 독립 (병렬 실행 가능)
- Task 0은 반드시 먼저 실행
