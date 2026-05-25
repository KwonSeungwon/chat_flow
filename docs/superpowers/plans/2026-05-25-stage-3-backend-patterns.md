# Stage 3 — Backend Design Patterns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 16+ duplicated `userId == null` controller checks with annotation-driven auth, convert boolean-returning non-CRUD services to a sealed `Result<T, ChatErrorCode>`, and decompose `UserPresenceService` (254 LOC) into 4 focused services.

**Architecture:**
- **PR-3A**: `@RequireAuth` / `@RequireMember` / `@AuthenticatedUser` annotations + `AuthInterceptor` (HandlerInterceptor) + `AuthenticatedUserResolver` (HandlerMethodArgumentResolver). `RoomMembershipGuard` becomes an internal collaborator that throws `UnauthorizedException` / `ForbiddenException`. `GlobalExceptionHandler` maps both to 401/403.
- **PR-3B**: Sealed `Result<T, E>` in-repo (no Vavr), `ChatErrorCode` enum, `ErrorResponses` mapper. `MessageEditService`, `MessagePinService`, `MessageReactionService`, `RoomMembershipService.leaveRoom`, `InviteLinkService.resolveToken`, `LinkPreviewService.fetch` migrate from `boolean` / nullable to `Result<…, ChatErrorCode>`. Controllers translate `Result` → HTTP via `ErrorResponses.from(…)`.
- **PR-3C**: Extract `BanCheckService`, `RoomFullnessService`, `ParticipantRegistryService`, `PresenceBroadcastService` from `UserPresenceService`. Orchestrator drops to ≤ 100 LOC, helpers each ≤ 120 LOC, existing tests stay green by going through the orchestrator's public API.

**Tech Stack:** Spring Boot 3.2, Spring MVC (`HandlerInterceptor`, `HandlerMethodArgumentResolver`, `@RestControllerAdvice`), JUnit 5, Mockito, AssertJ, MockMvc.

**Exit criteria (all 3 PRs):**
- `grep -rn "userId == null" chat-service/src/main/java/com/chatflow/chat/controller/` returns 0 matches in REST controllers (STOMP `ChatController` stays as-is — different lifecycle).
- Every service listed in PR-3B section returns `Result<…, ChatErrorCode>` or throws a registered domain exception (no silent `boolean false`).
- `UserPresenceService` ≤ 100 LOC, each extracted helper ≤ 120 LOC.
- `./gradlew :chat-service:test` PASS with no regressions on existing 381-test baseline.

**Non-goals:**
- STOMP `ChatController.isMember` stays in place (its `convertAndSendToUser(userId, "/queue/errors", …)` lifecycle is different — converting would expand scope).
- `MessageReportService` and `ScheduledMessageService` already throw registered exceptions for failure — leave them; converting would invert a working pattern.
- Owner-only checks (`deleteRoom`, `updateRoomSettings`) keep their service-level owner check; the annotation only covers the auth gate.

---

## File Structure

### New files

| Path | Purpose | LOC budget |
|------|---------|-----------|
| `chat-service/src/main/java/com/chatflow/chat/auth/RequireAuth.java` | `@RequireAuth` annotation (controller method) | ≤ 25 |
| `chat-service/src/main/java/com/chatflow/chat/auth/RequireMember.java` | `@RequireMember(pathVar="roomId")` annotation | ≤ 30 |
| `chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUser.java` | `@AuthenticatedUser(required=true)` param annotation | ≤ 25 |
| `chat-service/src/main/java/com/chatflow/chat/auth/AuthInterceptor.java` | `HandlerInterceptor` enforcing both annotations | ≤ 110 |
| `chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUserResolver.java` | `HandlerMethodArgumentResolver` for `@AuthenticatedUser` | ≤ 60 |
| `chat-service/src/main/java/com/chatflow/chat/exception/UnauthorizedException.java` | Maps to 401 | ≤ 15 |
| `chat-service/src/main/java/com/chatflow/chat/exception/ForbiddenException.java` | Maps to 403 | ≤ 15 |
| `chat-service/src/main/java/com/chatflow/chat/config/WebMvcConfig.java` | Registers interceptor + resolver | ≤ 40 |
| `chat-service/src/main/java/com/chatflow/chat/result/Result.java` | Sealed `Result<T, E>` | ≤ 110 |
| `chat-service/src/main/java/com/chatflow/chat/result/ChatErrorCode.java` | Enum (NOT_FOUND, FORBIDDEN, MUTED, DELETED, ROOM_FULL, INVALID_INPUT, GONE) | ≤ 35 |
| `chat-service/src/main/java/com/chatflow/chat/result/ErrorResponses.java` | `Result` → `ResponseEntity` mapper | ≤ 65 |
| `chat-service/src/main/java/com/chatflow/chat/service/presence/BanCheckService.java` | extracted from `UserPresenceService.checkBanGate` | ≤ 60 |
| `chat-service/src/main/java/com/chatflow/chat/service/presence/RoomFullnessService.java` | extracted from `handleRoomFullIfNeeded` | ≤ 120 |
| `chat-service/src/main/java/com/chatflow/chat/service/presence/ParticipantRegistryService.java` | extracted from `registerParticipant` + `getRoomParticipantUserIds` + Redis-level leave | ≤ 120 |
| `chat-service/src/main/java/com/chatflow/chat/service/presence/PresenceBroadcastService.java` | extracted from `broadcastJoin` + new `broadcastLeave` | ≤ 100 |

### Test files (created)

| Path | Purpose |
|------|---------|
| `chat-service/src/test/java/com/chatflow/chat/auth/AuthenticatedUserResolverTest.java` | resolver unit tests |
| `chat-service/src/test/java/com/chatflow/chat/auth/AuthInterceptorTest.java` | interceptor unit tests |
| `chat-service/src/test/java/com/chatflow/chat/result/ResultTest.java` | sealed type contract |
| `chat-service/src/test/java/com/chatflow/chat/result/ErrorResponsesTest.java` | code → HTTP mapping |
| `chat-service/src/test/java/com/chatflow/chat/service/presence/BanCheckServiceTest.java` | ban gate |
| `chat-service/src/test/java/com/chatflow/chat/service/presence/RoomFullnessServiceTest.java` | DM full + redirect |
| `chat-service/src/test/java/com/chatflow/chat/service/presence/ParticipantRegistryServiceTest.java` | Redis SET + room_members backfill |
| `chat-service/src/test/java/com/chatflow/chat/service/presence/PresenceBroadcastServiceTest.java` | STOMP broadcast |

### Files modified (high-impact)

- `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java` — 5 null-checks + 5 `guard.requireMember` calls migrated to annotations.
- `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java` — drops private `requireMember`, 3 null-checks + 5 guard calls migrated; controllers translate `Result` via `ErrorResponses.from(...)`.
- `chat-service/src/main/java/com/chatflow/chat/controller/RoomReadStateController.java` — 2 null-checks + 2 guard calls migrated; optional-auth endpoints use `@AuthenticatedUser(required = false)`.
- `chat-service/src/main/java/com/chatflow/chat/controller/RoomInviteController.java` — 1 null-check + 1 guard call migrated; `inviteLinkService.resolveToken` consumer updated for `Result`.
- `chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java` — 1 null-check migrated.
- `chat-service/src/main/java/com/chatflow/chat/controller/RoomMembershipGuard.java` — `requireMember(roomId, userId)` returns `void` and throws `UnauthorizedException` / `ForbiddenException`. (Keep legacy bridge logic.) Existing test will need updated assertions.
- `chat-service/src/main/java/com/chatflow/chat/exception/GlobalExceptionHandler.java` — add handlers for `UnauthorizedException` and `ForbiddenException`.
- `chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java` — `deleteMessage` / `editMessage` return `Result<Void, ChatErrorCode>`.
- `chat-service/src/main/java/com/chatflow/chat/service/MessagePinService.java` — `pinMessage` / `unpinMessage` return `Result<Void, ChatErrorCode>`.
- `chat-service/src/main/java/com/chatflow/chat/service/MessageReactionService.java` — `toggleReaction` returns `Result<Boolean, ChatErrorCode>` (true=added, false=removed).
- `chat-service/src/main/java/com/chatflow/chat/service/RoomMembershipService.java` — `leaveRoom` returns `Result<Void, ChatErrorCode>`.
- `chat-service/src/main/java/com/chatflow/chat/service/InviteLinkService.java` — `resolveToken` returns `Result<String, ChatErrorCode>`.
- `chat-service/src/main/java/com/chatflow/chat/service/LinkPreviewService.java` — `fetch` returns `Result<Map<String,String>, ChatErrorCode>`.
- `chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java` — drops to orchestrator (~80 LOC).

---

## Branching strategy

Each PR branches off the latest `develop` and merges back through reviewer approval:
- PR-3A → `refactor/stage-3a-auth-interceptor`
- PR-3B → `refactor/stage-3b-result-type` (branches off `develop` after 3A merges)
- PR-3C → `refactor/stage-3c-presence-decomp` (branches off `develop` after 3B merges)

Sequential — not parallel — because 3B touches controllers that 3A just refactored, and 3C needs a stable baseline.

---

---

# PR-3A — `@RequireAuth` / `@RequireMember` annotation interceptor

**Scope:** Eliminate 16 `userId == null || userId.isBlank()` duplications and 13 `guard.requireMember(...)` call-sites by moving the gate into a `HandlerInterceptor`. `RoomMembershipGuard` becomes the implementation, not the public surface.

**Behavioral contract preserved:**
- 401 body: `{success: false, message: "인증이 필요합니다."}`
- 403 body: `{success: false, message: "방 멤버가 아닙니다."}`
- Legacy bridge (creator-as-member backfill) survives intact.

---

### Task 1: Create branch

- [ ] **Step 1: Create branch off develop**

```bash
git checkout develop && git pull --ff-only
git checkout -b refactor/stage-3a-auth-interceptor
```

- [ ] **Step 2: Verify clean tree**

Run: `git status`
Expected: `nothing to commit, working tree clean`

---

### Task 2: `@RequireAuth` annotation

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/auth/RequireAuth.java`

- [ ] **Step 1: Write the annotation**

```java
package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring an authenticated caller — the
 * AuthInterceptor short-circuits with 401 if the X-User-Id header is
 * missing or blank.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {
}
```

- [ ] **Step 2: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/auth/RequireAuth.java
git commit -m "feat(chat-service): add @RequireAuth annotation"
```

---

### Task 3: `@RequireMember` annotation

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/auth/RequireMember.java`

- [ ] **Step 1: Write the annotation**

```java
package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring the caller to be a member of the
 * room identified by {@link #pathVar()} (default "roomId"). The
 * AuthInterceptor first enforces @RequireAuth semantics (401 if no userId),
 * then delegates to RoomMembershipGuard which throws 403 if not a member.
 *
 * The pathVar's value is read from the request URI's path-variable map.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireMember {
    String pathVar() default "roomId";
}
```

- [ ] **Step 2: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/auth/RequireMember.java
git commit -m "feat(chat-service): add @RequireMember annotation"
```

---

### Task 4: `@AuthenticatedUser` parameter annotation

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUser.java`

- [ ] **Step 1: Write the annotation**

```java
package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller-method parameter annotation. Resolves to the value of the
 * X-User-Id request header. If {@link #required()} is true (default) and
 * the header is missing/blank, the AuthenticatedUserResolver throws
 * UnauthorizedException. If false, resolves to null — for endpoints that
 * support anonymous callers (e.g. lobby room list).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUser {
    boolean required() default true;
}
```

- [ ] **Step 2: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUser.java
git commit -m "feat(chat-service): add @AuthenticatedUser param annotation"
```

---

### Task 5: `UnauthorizedException` + `ForbiddenException`

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/exception/UnauthorizedException.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/exception/ForbiddenException.java`

- [ ] **Step 1: Write UnauthorizedException**

```java
package com.chatflow.chat.exception;

/**
 * 401 — caller is not authenticated (missing/blank X-User-Id).
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write ForbiddenException**

```java
package com.chatflow.chat.exception;

/**
 * 403 — caller is authenticated but does not have access (not a room member).
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/exception/UnauthorizedException.java \
        chat-service/src/main/java/com/chatflow/chat/exception/ForbiddenException.java
git commit -m "feat(chat-service): add Unauthorized + Forbidden exceptions"
```

---

### Task 6: GlobalExceptionHandler — register new handlers

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Add imports + two handlers**

Add these import statements (after existing imports):

```java
import com.chatflow.common.dto.ApiResponse;
```

Add these two handler methods inside `GlobalExceptionHandler` (after `handleNotFound`):

```java
@ExceptionHandler(UnauthorizedException.class)
public ResponseEntity<ApiResponse<?>> handleUnauthorized(UnauthorizedException e) {
    log.warn("Unauthorized: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.error(e.getMessage()));
}

@ExceptionHandler(ForbiddenException.class)
public ResponseEntity<ApiResponse<?>> handleForbidden(ForbiddenException e) {
    log.warn("Forbidden: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error(e.getMessage()));
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/exception/GlobalExceptionHandler.java
git commit -m "feat(chat-service): map Unauthorized/Forbidden to 401/403"
```

---

### Task 7: `AuthenticatedUserResolver` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/auth/AuthenticatedUserResolverTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUserResolver.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.auth;

import com.chatflow.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserResolverTest {

    private final AuthenticatedUserResolver resolver = new AuthenticatedUserResolver();

    static class Sample {
        @SuppressWarnings("unused")
        public void required(@AuthenticatedUser String userId) {}
        @SuppressWarnings("unused")
        public void optional(@AuthenticatedUser(required = false) String userId) {}
        @SuppressWarnings("unused")
        public void noAnno(String userId) {}
    }

    private MethodParameter param(String methodName) throws NoSuchMethodException {
        Method m = Sample.class.getMethod(methodName, String.class);
        return new MethodParameter(m, 0);
    }

    @Test
    void supportsParameter_returns_true_only_for_annotated_String() throws Exception {
        assertThat(resolver.supportsParameter(param("required"))).isTrue();
        assertThat(resolver.supportsParameter(param("optional"))).isTrue();
        assertThat(resolver.supportsParameter(param("noAnno"))).isFalse();
    }

    @Test
    void resolves_header_value_when_present() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-42");
        Object value = resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isEqualTo("user-42");
    }

    @Test
    void throws_Unauthorized_when_header_missing_and_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("인증이 필요합니다.");
    }

    @Test
    void returns_null_when_header_missing_and_not_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Object value = resolver.resolveArgument(param("optional"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isNull();
    }

    @Test
    void throws_Unauthorized_when_header_blank_and_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "   ");
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile (no resolver yet)**

Run: `./gradlew :chat-service:test --tests AuthenticatedUserResolverTest`
Expected: compile failure (`AuthenticatedUserResolver` does not exist).

- [ ] **Step 3: Implement the resolver**

```java
package com.chatflow.chat.auth;

import com.chatflow.chat.exception.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class AuthenticatedUserResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER_NAME = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedUser.class)
                && parameter.getParameterType().equals(String.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                   ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest,
                                   WebDataBinderFactory binderFactory) {
        AuthenticatedUser anno = parameter.getParameterAnnotation(AuthenticatedUser.class);
        String value = webRequest.getHeader(HEADER_NAME);
        boolean blank = value == null || value.isBlank();
        if (blank) {
            if (anno != null && !anno.required()) return null;
            throw new UnauthorizedException("인증이 필요합니다.");
        }
        return value;
    }
}
```

- [ ] **Step 4: Run the test — verify PASS**

Run: `./gradlew :chat-service:test --tests AuthenticatedUserResolverTest`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/auth/AuthenticatedUserResolver.java \
        chat-service/src/test/java/com/chatflow/chat/auth/AuthenticatedUserResolverTest.java
git commit -m "feat(chat-service): AuthenticatedUserResolver with required-flag"
```

---

### Task 8: Refactor `RoomMembershipGuard` to throw

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/RoomMembershipGuard.java`

The old `requireMember(roomId, userId)` returned `ResponseEntity<ApiResponse<?>>` (null on success, error response on failure). The new contract throws `UnauthorizedException` / `ForbiddenException` so the interceptor + GlobalExceptionHandler can handle uniformly.

- [ ] **Step 1: Replace the method body**

Replace the entire `requireMember` method (lines 31–48) with:

```java
/**
 * Asserts the caller is authenticated AND a member of the room.
 * Throws UnauthorizedException (401) if userId is missing/blank,
 * ForbiddenException (403) if the caller is not a member.
 *
 * Legacy bridge: room.createdBy == userId is accepted as membership, and
 * the missing room_members row is backfilled as OWNER to preserve
 * moderation features for pre-seed creators.
 */
public void requireMember(String roomId, String userId) {
    if (userId == null || userId.isBlank()) {
        throw new UnauthorizedException("인증이 필요합니다.");
    }
    if (roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) return;
    ChatRoom legacy = chatRoomService.getRoom(roomId).orElse(null);
    if (legacy != null && userId.equals(legacy.getCreatedBy())) {
        roomMembershipService.addMemberIfAbsent(roomId, userId, null, RoomRole.OWNER);
        return;
    }
    throw new ForbiddenException("방 멤버가 아닙니다.");
}
```

- [ ] **Step 2: Add imports**

```java
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
```

Remove these now-unused imports:

```java
import com.chatflow.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
```

- [ ] **Step 3: Compile — expect failures in callers**

Run: `./gradlew :chat-service:compileJava`
Expected: COMPILE ERRORS in `ChatRoomController`, `MessageInteractionController`, `RoomReadStateController`, `RoomInviteController` (they call `guard.requireMember(...)` expecting a ResponseEntity return). These are fixed in Tasks 13–17 below — leave them broken for now.

> **Implementer note:** Do NOT commit yet. The next steps (Tasks 9–12) build the interceptor + config in a state that still compiles standalone, but the controllers will only compile after Task 13. Commit after Task 17 when everything green.

---

### Task 9: `AuthInterceptor` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/auth/AuthInterceptorTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/auth/AuthInterceptor.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.auth;

import com.chatflow.chat.controller.RoomMembershipGuard;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock RoomMembershipGuard membershipGuard;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(membershipGuard);
    }

    @SuppressWarnings("unused")
    static class Sample {
        @RequireAuth
        public void authOnly() {}
        @RequireMember(pathVar = "roomId")
        public void roomGated() {}
        public void noAnno() {}
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method m = Sample.class.getMethod(methodName);
        return new HandlerMethod(new Sample(), m);
    }

    @Test
    void no_annotation_passes_through() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("noAnno")));
        verifyNoInteractions(membershipGuard);
    }

    @Test
    void requireAuth_throws_when_header_missing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, res, handler("authOnly")));
    }

    @Test
    void requireAuth_passes_when_header_present() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("authOnly")));
    }

    @Test
    void requireMember_delegates_to_guard() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("roomGated")));
        verify(membershipGuard).requireMember("room-42", "user-1");
    }

    @Test
    void requireMember_propagates_Forbidden_from_guard() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                .when(membershipGuard).requireMember("room-42", "user-1");
        assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
    }

    @Test
    void requireMember_throws_Unauthorized_when_header_missing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
        verifyNoInteractions(membershipGuard);
    }
}
```

- [ ] **Step 2: Implement the interceptor**

```java
package com.chatflow.chat.auth;

import com.chatflow.chat.controller.RoomMembershipGuard;
import com.chatflow.chat.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-User-Id";

    private final RoomMembershipGuard membershipGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;

        RequireMember member = hm.getMethodAnnotation(RequireMember.class);
        RequireAuth auth = hm.getMethodAnnotation(RequireAuth.class);

        if (member == null && auth == null) return true;

        String userId = request.getHeader(HEADER_NAME);
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("인증이 필요합니다.");
        }

        if (member != null) {
            String roomId = pathVar(request, member.pathVar());
            membershipGuard.requireMember(roomId, userId);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String pathVar(HttpServletRequest request, String name) {
        Object raw = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (raw instanceof Map<?, ?> map) {
            Object v = ((Map<String, String>) map).get(name);
            return v == null ? null : v.toString();
        }
        return null;
    }
}
```

- [ ] **Step 3: Run the test — verify PASS**

Run: `./gradlew :chat-service:test --tests AuthInterceptorTest`
Expected: 6 tests PASS.

> Do not commit yet — full repo still doesn't compile until controllers migrate.

---

### Task 10: `WebMvcConfig` — register interceptor + resolver

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/config/WebMvcConfig.java`

- [ ] **Step 1: Write the config**

```java
package com.chatflow.chat.config;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.controller.RoomMembershipGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RoomMembershipGuard membershipGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(membershipGuard))
                .addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedUserResolver());
    }
}
```

> **Note:** Path pattern `/api/**` covers all REST endpoints. Static resources and STOMP endpoints (`/ws-native/**`) are unaffected because they don't match.

---

### Task 11: Migrate `ChatController` — leave STOMP `isMember` as-is

**Decision:** STOMP `@MessageMapping` methods do not go through `HandlerInterceptor` (different message dispatch path). `ChatController.isMember` stays untouched in PR-3A. This is a documented non-goal.

- [ ] **Step 1: No code change.** Verify by `grep`:

```bash
grep -c "isMember" chat-service/src/main/java/com/chatflow/chat/controller/ChatController.java
```

Expected: still 5 (declaration + 4 callers).

---

### Task 12: Migrate `FcmController`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java`

- [ ] **Step 1: Read current state**

```bash
grep -n "userId == null" chat-service/src/main/java/com/chatflow/chat/controller/FcmController.java
```

Expected: 1 match around line 39.

- [ ] **Step 2: Add import**

```java
import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
```

- [ ] **Step 3: Convert the method**

Find the method around line 39 with `userId == null || userId.isBlank()`. Replace:

```java
@RequestHeader(value = "X-User-Id", required = false) String userId
```
with
```java
@AuthenticatedUser String userId
```

Add `@RequireAuth` above the method declaration. Delete the null-check block and its `return 401` body. Method body now uses `userId` directly.

- [ ] **Step 4: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: only outstanding compile errors are in `ChatRoomController`, `MessageInteractionController`, `RoomReadStateController`, `RoomInviteController` (still pre-Task 13).

---

### Task 13: Migrate `ChatRoomController`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java`

This controller has the largest migration surface: 5 null-checks + 5 `guard.requireMember(...)` call-sites.

- [ ] **Step 1: Add imports**

```java
import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
```

- [ ] **Step 2: Convert `getAllRooms` (lines 53–68) — optional auth**

Change parameter:
```java
@RequestHeader(value = "X-User-Id", required = false) String userId
```
to
```java
@AuthenticatedUser(required = false) String userId
```

No method-level annotation (auth is optional). Body unchanged.

- [ ] **Step 3: Convert `getRoom` (lines 70–80)**

Change parameter to `@AuthenticatedUser String userId`. Add `@RequireMember(pathVar = "id")` above method (pathVar name is `id`, not `roomId`, in this endpoint!). Delete the `gate` lines (3 lines).

- [ ] **Step 4: Convert `createRoom` (lines 82–93)**

Change `creatorId` parameter to `@AuthenticatedUser String creatorId`. Add `@RequireAuth`. Delete the null-check block.

- [ ] **Step 5: `getOrCreateRoom` — no change (no auth required)**

- [ ] **Step 6: Convert `getMessages` (lines 104–117)**

Change `userId` parameter to `@AuthenticatedUser String userId`. Add `@RequireMember`. Delete gate lines.

- [ ] **Step 7: Convert `getMessagesByCursor` (lines 123–143)**

Change parameter, add `@RequireMember`, delete gate.

- [ ] **Step 8: `verifyPassword` — optional userId**

Change to `@AuthenticatedUser(required = false) String userId`. No method annotation.

- [ ] **Step 9: `getParticipants` — no change**

- [ ] **Step 10: Convert `deleteRoom` (lines 193–213) — owner check stays**

Change parameter to `@AuthenticatedUser String userId`, add `@RequireAuth`. Delete null-check block. Keep the room-not-found and owner check (those are service-domain, not auth).

- [ ] **Step 11: Convert `leaveRoom` (lines 215–229)**

Change parameter to `@AuthenticatedUser String userId`, add `@RequireAuth`. Delete null-check. Keep the `username` null check (validation, not auth).

- [ ] **Step 12: Convert `hideRoom` (lines 236–260)**

Change to `@AuthenticatedUser String userId` + `@RequireAuth`. Delete the auth null-check including the audit log inside it.

> **Behavioral change:** The audit `ROOM_HIDE_DENIED` log on unauthenticated calls disappears (interceptor short-circuits before controller body runs). This is acceptable — unauthenticated callers cannot be meaningfully audited (userId="unknown"). Document in commit message.

- [ ] **Step 13: Convert `createDm` (lines 262–278)**

Change to `@AuthenticatedUser String userId` + `@RequireAuth`. Simplify the input validation — replace the combined check with separate validation for `targetUserId` and `targetUsername`:

```java
if (targetUserId == null || targetUsername == null) {
    return ResponseEntity.badRequest()
            .body(ApiResponse.error("targetUserId, targetUsername이 필요합니다."));
}
```

> **Semantic shift:** Old behavior returned 400 when userId was missing too. New behavior returns 401 for missing userId, 400 for missing target. Acceptable per spec (correct status semantics).

- [ ] **Step 14: Convert `updateRoomSettings` (lines 280–301)**

Change to `@AuthenticatedUser String userId` + `@RequireAuth`. Delete null-check, keep owner check.

- [ ] **Step 15: Convert `sendMessage` (lines 307–344)**

Change to `@AuthenticatedUser String userId` + `@RequireMember`. Delete the gate.

- [ ] **Step 16: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: now only `MessageInteractionController`, `RoomReadStateController`, `RoomInviteController` still failing.

---

### Task 14: Migrate `MessageInteractionController`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

- [ ] **Step 1: Add imports**

```java
import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
```

- [ ] **Step 2: Delete the private `requireMember` method (lines 39–54)**

The interceptor now owns this. Also delete the unused imports `ChatRoom`, `ChatRoomRepository`, `RoomMemberRepository` if no other method references them — `grep -n "chatRoomRepository\|roomMemberRepository" MessageInteractionController.java` to confirm before removing.

(They're still used by the link preview path? `linkPreviewService.fetch` doesn't touch them. So they ARE now unused — delete.)

- [ ] **Step 3: Convert `deleteMessage` (lines 56–71)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireAuth`. Delete null-check.

- [ ] **Step 4: Convert `editMessage` (lines 73–96)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireAuth`. Delete null-check.

- [ ] **Step 5: Convert `toggleReaction` (lines 98–110)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate lines.

- [ ] **Step 6: Convert `getEditHistory` (lines 116–126)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate.

- [ ] **Step 7: Convert `getReplies` (lines 128–137)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate.

- [ ] **Step 8: Convert `pinMessage` (lines 139–149)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate.

- [ ] **Step 9: Convert `unpinMessage` (lines 151–158)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate.

- [ ] **Step 10: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: now only `RoomReadStateController`, `RoomInviteController` left.

---

### Task 15: Migrate `RoomReadStateController`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/RoomReadStateController.java`

- [ ] **Step 1: Add imports**

```java
import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireMember;
```

- [ ] **Step 2: Convert `getUnreadCounts` (lines 36–46) — optional auth**

Parameter → `@AuthenticatedUser(required = false) String userId`. No method annotation. Delete the null-check (keep the `if (userId == null …) return Map.of()` short-circuit but use `userId == null` only — resolver returns null for missing, not blank string).

```java
if (userId == null) {
    return ResponseEntity.ok(ApiResponse.ok(Map.of()));
}
```

- [ ] **Step 3: Convert `getRoomReaders` (lines 48–56)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate.

- [ ] **Step 4: Convert `getLastRead` (lines 58–69) — optional auth**

Parameter → `@AuthenticatedUser(required = false) String userId`. No method annotation. Change the null/blank check:

```java
if (userId == null) {
    return ResponseEntity.ok(ApiResponse.ok(Map.of("lastReadMessageId", "")));
}
```

- [ ] **Step 5: Convert `updateLastRead` (lines 71–87)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate. Leave `username` as `@RequestHeader(value = "X-Username", required = false)` — that's a separate header, not the auth subject.

- [ ] **Step 6: Compile**

Run: `./gradlew :chat-service:compileJava`
Expected: only `RoomInviteController` left.

---

### Task 16: Migrate `RoomInviteController`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/RoomInviteController.java`

- [ ] **Step 1: Add imports**

```java
import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.auth.RequireMember;
```

- [ ] **Step 2: `inviteUser` (lines 38–77) — no userId check today**

Today the method uses `inviterId` and `inviterName` but never gates on userId. Add `@RequireAuth` + `@AuthenticatedUser String inviterId`. The room-not-found / disabled checks stay.

> **Behavioral change:** Previously an unauthenticated call to `POST /api/chat/rooms/{id}/invite` succeeded as long as `inviterName` was present (the inviter name would be `null`, producing a `null님이 초대` system message). Now it returns 401. This is a security tightening — flag in commit message.

- [ ] **Step 3: Convert `createInviteLink` (lines 83–107)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireMember`. Delete gate lines.

- [ ] **Step 4: Convert `joinByInvite` (lines 114–148)**

Parameter → `@AuthenticatedUser String userId`. Annotation → `@RequireAuth` (member check happens via token resolution, not URL path). Delete null-check.

- [ ] **Step 5: Compile + run all tests**

Run: `./gradlew :chat-service:test`
Expected: BUILD SUCCESSFUL, 381+ tests PASS. New tests from Tasks 7 + 9 contribute 11 more (total ~392).

> If tests fail, expected failures are in `RoomMembershipGuardTest` (if exists) and any MockMvc-based controller test that hardcoded `null` returns from `guard.requireMember`. Update those test assertions to use `verify(guard).requireMember(roomId, userId)` plus exception-throwing matchers.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(chat-service): migrate all REST controllers to @RequireAuth/@RequireMember

Drops 16 'userId == null' duplications and 13 RoomMembershipGuard call-sites
across ChatRoomController, MessageInteractionController, RoomReadStateController,
RoomInviteController, FcmController. AuthInterceptor delegates to
RoomMembershipGuard which now throws Unauthorized/Forbidden instead of returning
a ResponseEntity. GlobalExceptionHandler maps both to 401/403 with the legacy
error message bodies preserved.

Behavioral changes documented:
- POST /api/chat/rooms/{id}/invite now returns 401 for unauthenticated calls
  (previously: succeeded with null inviter name).
- POST /api/chat/rooms/dm returns 401 for missing userId (previously: 400).
- ROOM_HIDE_DENIED audit log for unauthenticated /hide call is dropped
  (interceptor short-circuits before the controller body runs).

STOMP ChatController.isMember stays in place — different dispatch path."
```

---

### Task 17: Verification — grep for residual null-checks

- [ ] **Step 1: Confirm zero residual `userId == null` in REST controllers**

```bash
grep -rn "userId == null" chat-service/src/main/java/com/chatflow/chat/controller/ \
    --include='*.java' | grep -v ChatController.java
```

Expected: zero matches.

- [ ] **Step 2: Confirm RoomMembershipGuard has zero external call-sites besides interceptor + config**

```bash
grep -rn "membershipGuard\." chat-service/src/main/java/com/chatflow/chat/ --include='*.java'
```

Expected: only `AuthInterceptor` and `WebMvcConfig` references (and `RoomMembershipGuard` itself).

- [ ] **Step 3: Final test run**

Run: `./gradlew :chat-service:test`
Expected: BUILD SUCCESSFUL, ≥ 392 tests PASS.

- [ ] **Step 4: Dispatch superpowers:code-reviewer**

Per project policy. Address feedback in additional commits on this branch.

- [ ] **Step 5: Merge into develop** (after reviewer approves)

```bash
git checkout develop && git pull --ff-only
git merge --no-ff refactor/stage-3a-auth-interceptor
git push origin develop
```

---

# PR-3B — `Result<T, ChatErrorCode>` for non-CRUD services

**Scope:** Replace `boolean` and nullable return contracts that hide the *reason* for failure. Caller cannot distinguish "not found" from "forbidden" from "muted" today. After this PR, each controller can map failure→HTTP precisely.

**Convert (6 services):**
- `MessageEditService.deleteMessage`, `MessageEditService.editMessage` (boolean → `Result<Void, ChatErrorCode>`)
- `MessagePinService.pinMessage`, `MessagePinService.unpinMessage` (boolean → `Result<Void, ChatErrorCode>`)
- `MessageReactionService.toggleReaction` (boolean → `Result<Boolean, ChatErrorCode>` where the wrapped Boolean indicates added vs removed)
- `RoomMembershipService.leaveRoom` (void+throws → `Result<Void, ChatErrorCode>`)
- `InviteLinkService.resolveToken` (nullable String → `Result<String, ChatErrorCode>`)
- `LinkPreviewService.fetch` (empty map on fail → `Result<Map<String,String>, ChatErrorCode>`)

**Defer (already explicit-failure):** `MessageReportService`, `ScheduledMessageService` — both throw registered domain exceptions via `GlobalExceptionHandler`. Converting would invert a working pattern.

**Branch:**

```bash
git checkout develop && git pull --ff-only
git checkout -b refactor/stage-3b-result-type
```

---

### Task 18: `Result<T, E>` sealed type — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/result/ResultTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/result/Result.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTest {

    enum E { FOO, BAR }

    @Test
    void ok_holds_value_and_isSuccess() {
        Result<String, E> r = Result.ok("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
        assertThat(r.value()).isEqualTo("hello");
    }

    @Test
    void err_holds_code_and_message() {
        Result<String, E> r = Result.err(E.FOO, "bad");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isFailure()).isTrue();
        assertThat(r.error()).isEqualTo(E.FOO);
        assertThat(r.message()).isEqualTo("bad");
    }

    @Test
    void value_on_failure_throws() {
        Result<String, E> r = Result.err(E.FOO, "bad");
        assertThatThrownBy(r::value).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void error_on_success_throws() {
        Result<String, E> r = Result.ok("hello");
        assertThatThrownBy(r::error).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ok_void_factory() {
        Result<Void, E> r = Result.ok();
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.value()).isNull();
    }
}
```

- [ ] **Step 2: Run — verify it fails to compile**

Run: `./gradlew :chat-service:test --tests ResultTest`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement Result**

```java
package com.chatflow.chat.result;

/**
 * Sealed Result&lt;T, E&gt; — explicit success/failure outcome instead of
 * boolean + side-channel exception. Stored in-repo (no Vavr footprint).
 *
 * Use {@link #ok(Object)} / {@link #ok()} / {@link #err(Object, String)} to
 * construct. Use {@link #isSuccess()} / {@link #isFailure()} to branch,
 * then {@link #value()} / {@link #error()} + {@link #message()} to extract.
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * @return the success value
     * @throws IllegalStateException if this is a Failure
     */
    T value();

    /**
     * @return the error code
     * @throws IllegalStateException if this is a Success
     */
    E error();

    /**
     * @return the failure message (null if this is a Success)
     */
    String message();

    static <T, E> Result<T, E> ok(T value) {
        return new Success<>(value);
    }

    @SuppressWarnings("unchecked")
    static <E> Result<Void, E> ok() {
        return (Result<Void, E>) Success.VOID;
    }

    static <T, E> Result<T, E> err(E error, String message) {
        return new Failure<>(error, message);
    }

    record Success<T, E>(T value) implements Result<T, E> {
        static final Success<Void, ?> VOID = new Success<>(null);

        @Override public boolean isSuccess() { return true; }
        @Override public E error() {
            throw new IllegalStateException("Cannot call error() on Success");
        }
        @Override public String message() { return null; }
    }

    record Failure<T, E>(E error, String message) implements Result<T, E> {
        @Override public boolean isSuccess() { return false; }
        @Override public T value() {
            throw new IllegalStateException("Cannot call value() on Failure: " + error + " — " + message);
        }
    }
}
```

- [ ] **Step 4: Run the test — verify PASS**

Run: `./gradlew :chat-service:test --tests ResultTest`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/result/Result.java \
        chat-service/src/test/java/com/chatflow/chat/result/ResultTest.java
git commit -m "feat(chat-service): add sealed Result<T,E> type"
```

---

### Task 19: `ChatErrorCode` enum

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/result/ChatErrorCode.java`

- [ ] **Step 1: Write the enum**

```java
package com.chatflow.chat.result;

import org.springframework.http.HttpStatus;

/**
 * Single source-of-truth for failure modes returned by Result-bearing
 * services. The HTTP status here is the default response code — controllers
 * can override on a per-endpoint basis (e.g. invite token returns 410 GONE
 * instead of the default 404 NOT_FOUND).
 */
public enum ChatErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    MUTED(HttpStatus.LOCKED),
    DELETED(HttpStatus.GONE),
    ROOM_FULL(HttpStatus.BAD_REQUEST),
    INVALID_INPUT(HttpStatus.BAD_REQUEST),
    GONE(HttpStatus.GONE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus defaultHttpStatus;

    ChatErrorCode(HttpStatus defaultHttpStatus) {
        this.defaultHttpStatus = defaultHttpStatus;
    }

    public HttpStatus defaultHttpStatus() {
        return defaultHttpStatus;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/result/ChatErrorCode.java
git commit -m "feat(chat-service): add ChatErrorCode enum with HTTP defaults"
```

---

### Task 20: `ErrorResponses` mapper — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/result/ErrorResponsesTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/result/ErrorResponses.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.result;

import com.chatflow.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponsesTest {

    @Test
    void from_maps_NOT_FOUND_to_404() {
        Result<String, ChatErrorCode> r = Result.err(ChatErrorCode.NOT_FOUND, "no such room");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().isSuccess()).isFalse();
        assertThat(resp.getBody().getMessage()).isEqualTo("no such room");
    }

    @Test
    void from_maps_FORBIDDEN_to_403() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.FORBIDDEN, "not author");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void from_maps_MUTED_to_423() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.MUTED, "muted");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void from_maps_GONE_to_410() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.GONE, "expired");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.chatflow.chat.result;

import com.chatflow.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;

/**
 * Converts a failed Result<…, ChatErrorCode> into the project's
 * ApiResponse error envelope at the appropriate HTTP status. Success
 * responses are the caller's responsibility — Result.value() is the
 * payload they wrap into ApiResponse.ok(...).
 */
public final class ErrorResponses {

    private ErrorResponses() {}

    public static ResponseEntity<ApiResponse<?>> from(Result<?, ChatErrorCode> result) {
        if (result.isSuccess()) {
            throw new IllegalArgumentException("ErrorResponses.from called on Success");
        }
        return ResponseEntity.status(result.error().defaultHttpStatus())
                .body(ApiResponse.error(result.message()));
    }
}
```

- [ ] **Step 3: Run + commit**

Run: `./gradlew :chat-service:test --tests ErrorResponsesTest`
Expected: 4 tests PASS.

```bash
git add chat-service/src/main/java/com/chatflow/chat/result/ErrorResponses.java \
        chat-service/src/test/java/com/chatflow/chat/result/ErrorResponsesTest.java
git commit -m "feat(chat-service): add ErrorResponses Result→HTTP mapper"
```

---

### Task 21: Migrate `MessageEditService.deleteMessage` → `Result<Void, ChatErrorCode>`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/MessageEditServiceTest.java` (the Stage 1 test added in PR-1 — adjust assertions)
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

- [ ] **Step 1: Update service signature**

In `MessageEditService.java`, change `deleteMessage` signature and body:

```java
@Transactional
public Result<Void, ChatErrorCode> deleteMessage(String messageId, String requestingUserId) {
    return chatMessageRepository.findById(messageId).<Result<Void, ChatErrorCode>>map(entity -> {
        if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
            return Result.err(ChatErrorCode.FORBIDDEN, "삭제 권한이 없습니다.");
        }
        entity.setDeleted(true);
        entity.setContent("삭제된 메시지입니다.");
        chatMessageRepository.save(entity);
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("type", "MESSAGE_DELETED");
        broadcast.put("messageId", messageId);
        broadcast.put("chatRoomId", entity.getChatRoomId());
        broadcast.put("content", "삭제된 메시지입니다.");
        broadcast.put("username", entity.getUsername());
        broadcast.put("timestamp", entity.getTimestamp().toString());
        messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
        log.info("Message deleted: {} by user {}", messageId, requestingUserId);
        return Result.ok();
    }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
}
```

Add imports:

```java
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
```

- [ ] **Step 2: Update controller**

In `MessageInteractionController.deleteMessage`, replace:

```java
boolean deleted = messageEditService.deleteMessage(messageId, userId);
if (!deleted) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("삭제 권한이 없거나 메시지를 찾을 수 없습니다."));
}
return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 삭제되었습니다."));
```
with
```java
Result<Void, ChatErrorCode> result = messageEditService.deleteMessage(messageId, userId);
if (result.isFailure()) {
    return ErrorResponses.from(result);
}
return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 삭제되었습니다."));
```

> **Behavioral change:** "not found" now correctly returns 404 (was 403). "forbidden" stays 403. Document in commit.

Add imports:

```java
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.ErrorResponses;
import com.chatflow.chat.result.Result;
```

- [ ] **Step 3: Update Stage 1 test**

The Stage 1 `MessageEditServiceTest` asserted boolean return. Find and update assertions to `result.isFailure()` / `result.error() == ChatErrorCode.FORBIDDEN` etc.

Run: `./gradlew :chat-service:test --tests MessageEditServiceTest`
Expected: PASS after assertion update.

- [ ] **Step 4: Commit**

```bash
git add chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java \
        chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java \
        chat-service/src/test/java/com/chatflow/chat/service/MessageEditServiceTest.java
git commit -m "refactor(chat-service): MessageEditService.deleteMessage returns Result<Void,ChatErrorCode>

Distinguishes NOT_FOUND (404) from FORBIDDEN (403) — previously both
collapsed to 403."
```

---

### Task 22: Migrate `MessageEditService.editMessage` → `Result<Void, ChatErrorCode>`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/MessageEditServiceTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

- [ ] **Step 1: Update editMessage signature**

In `MessageEditService.java`, replace the entire `editMessage` method body's failure-branches:

```java
@Transactional
public Result<Void, ChatErrorCode> editMessage(String messageId, String requestingUserId, String newContent) {
    return chatMessageRepository.findById(messageId).<Result<Void, ChatErrorCode>>map(entity -> {
        if (entity.getUserId() == null || !entity.getUserId().equals(requestingUserId)) {
            return Result.err(ChatErrorCode.FORBIDDEN, "수정 권한이 없습니다.");
        }
        if (entity.isDeleted()) {
            return Result.err(ChatErrorCode.DELETED, "삭제된 메시지는 수정할 수 없습니다.");
        }
        RoomMemberEntity member = roomMemberRepository
                .findByRoomIdAndUserId(entity.getChatRoomId(), requestingUserId)
                .orElse(null);
        if (member != null && member.getMutedUntil() != null
                && member.getMutedUntil().isAfter(LocalDateTime.now())) {
            log.warn("Muted user {} tried to edit message {} in room {}",
                    requestingUserId, messageId, entity.getChatRoomId());
            return Result.err(ChatErrorCode.MUTED, "음소거 상태입니다.");
        }
        // ... rest of body unchanged through the broadcast
        // (record history → encrypt → save → broadcast)
        String previousPlain = messageEncryptor.isEnabled()
                ? messageEncryptor.decrypt(entity.getContent())
                : entity.getContent();
        editHistoryRepository.save(MessageEditHistoryEntity.builder()
                .messageId(messageId)
                .previousContent(previousPlain)
                .editedAt(LocalDateTime.now())
                .editedBy(requestingUserId)
                .build());

        entity.setContent(messageEncryptor.isEnabled() ? messageEncryptor.encrypt(newContent) : newContent);
        entity.setEdited(true);
        entity.setEditedAt(LocalDateTime.now());
        chatMessageRepository.save(entity);

        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("type", "MESSAGE_EDITED");
        broadcast.put("messageId", messageId);
        broadcast.put("chatRoomId", entity.getChatRoomId());
        broadcast.put("content", newContent);
        broadcast.put("username", entity.getUsername());
        broadcast.put("timestamp", entity.getTimestamp().toString());
        broadcast.put("editedAt", entity.getEditedAt().toString());
        messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
        log.info("Message edited: {} by user {}", messageId, requestingUserId);
        return Result.ok();
    }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
}
```

- [ ] **Step 2: Update controller**

In `MessageInteractionController.editMessage`, replace:

```java
boolean edited = messageEditService.editMessage(messageId, userId, newContent.trim());
if (!edited) { ... }
return ResponseEntity.ok(...);
```
with
```java
Result<Void, ChatErrorCode> result = messageEditService.editMessage(messageId, userId, newContent.trim());
if (result.isFailure()) {
    return ErrorResponses.from(result);
}
return ResponseEntity.ok(ApiResponse.ok(null, "메시지가 수정되었습니다."));
```

- [ ] **Step 3: Update existing test, run, commit**

```bash
./gradlew :chat-service:test --tests MessageEditServiceTest
git add -A
git commit -m "refactor(chat-service): MessageEditService.editMessage returns Result<Void,ChatErrorCode>

NOT_FOUND→404, FORBIDDEN→403, DELETED→410, MUTED→423 — previously all
collapsed to 403."
```

---

### Task 23: Migrate `MessagePinService` (both methods)

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessagePinService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/MessagePinServiceTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

- [ ] **Step 1: Update both methods**

```java
@Transactional
public Result<Void, ChatErrorCode> pinMessage(String roomId, String messageId) {
    return chatRoomRepository.findById(roomId).<Result<Void, ChatErrorCode>>map(room -> {
        var msgOpt = chatMessageRepository.findById(messageId)
                .filter(m -> roomId.equals(m.getChatRoomId()) && !m.isDeleted());
        if (msgOpt.isEmpty()) {
            return Result.err(ChatErrorCode.NOT_FOUND, "고정할 메시지를 찾을 수 없습니다.");
        }
        room.setPinnedMessageId(messageId);
        chatRoomRepository.save(room);
        msgOpt.ifPresent(msg -> {
            msg.setPinned(true);
            chatMessageRepository.save(msg);
        });
        roomCacheEvictor.evict(roomId);
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("type", "MESSAGE_PINNED");
        broadcast.put("messageId", messageId);
        broadcast.put("chatRoomId", roomId);
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
        return Result.<Void, ChatErrorCode>ok();
    }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
}

@Transactional
public Result<Void, ChatErrorCode> unpinMessage(String roomId) {
    return chatRoomRepository.findById(roomId).<Result<Void, ChatErrorCode>>map(room -> {
        String oldPin = room.getPinnedMessageId();
        room.setPinnedMessageId(null);
        chatRoomRepository.save(room);
        if (oldPin != null) {
            chatMessageRepository.findById(oldPin).ifPresent(msg -> {
                msg.setPinned(false);
                chatMessageRepository.save(msg);
            });
        }
        roomCacheEvictor.evict(roomId);
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("type", "MESSAGE_UNPINNED");
        broadcast.put("chatRoomId", roomId);
        messagingTemplate.convertAndSend("/topic/chat/" + roomId, broadcast);
        return Result.<Void, ChatErrorCode>ok();
    }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
}
```

Add imports:

```java
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
```

- [ ] **Step 2: Update controller callsites**

In `MessageInteractionController.pinMessage` and `unpinMessage`:

```java
@PutMapping("/{roomId}/pin")
@RequireMember
public ResponseEntity<?> pinMessage(
        @PathVariable String roomId,
        @RequestBody Map<String, String> body,
        @AuthenticatedUser String userId) {
    String messageId = body.get("messageId");
    if (messageId == null) return ResponseEntity.badRequest().body(ApiResponse.error("messageId가 필요합니다."));
    Result<Void, ChatErrorCode> result = messagePinService.pinMessage(roomId, messageId);
    if (result.isFailure()) return ErrorResponses.from(result);
    return ResponseEntity.ok(ApiResponse.ok(true));
}

@DeleteMapping("/{roomId}/pin")
@RequireMember
public ResponseEntity<?> unpinMessage(
        @PathVariable String roomId,
        @AuthenticatedUser String userId) {
    Result<Void, ChatErrorCode> result = messagePinService.unpinMessage(roomId);
    if (result.isFailure()) return ErrorResponses.from(result);
    return ResponseEntity.ok(ApiResponse.ok(true));
}
```

- [ ] **Step 3: Update test + commit**

```bash
./gradlew :chat-service:test --tests MessagePinServiceTest
git add -A
git commit -m "refactor(chat-service): MessagePinService methods return Result<Void,ChatErrorCode>

pinMessage now returns NOT_FOUND when message doesn't belong to room
(previously: silent false → frontend showed generic error)."
```

---

### Task 24: Migrate `MessageReactionService.toggleReaction`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/MessageReactionService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/MessageReactionServiceTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

The current method returns `boolean` where `true` means the toggle succeeded (either added OR removed). The new contract: `Result<Boolean, ChatErrorCode>` where `Boolean` indicates "added" (true) or "removed" (false), and the failure cases are explicit.

- [ ] **Step 1: Update service signature**

```java
@Transactional
public Result<Boolean, ChatErrorCode> toggleReaction(String messageId, String emoji, String userId) {
    return chatMessageRepository.findById(messageId).<Result<Boolean, ChatErrorCode>>map(entity -> {
        Map<String, List<String>> map;
        try {
            map = entity.getReactions() != null
                    ? objectMapper.readValue(entity.getReactions(), new TypeReference<>() {})
                    : new LinkedHashMap<>();
        } catch (Exception e) {
            map = new LinkedHashMap<>();
        }
        List<String> users = map.computeIfAbsent(emoji, k -> new java.util.ArrayList<>());
        boolean added;
        if (users.contains(userId)) {
            users.remove(userId);
            if (users.isEmpty()) map.remove(emoji);
            added = false;
        } else {
            users.add(userId);
            added = true;
        }
        try {
            entity.setReactions(map.isEmpty() ? null : objectMapper.writeValueAsString(map));
        } catch (Exception e) {
            return Result.<Boolean, ChatErrorCode>err(ChatErrorCode.INTERNAL_ERROR, "리액션 직렬화 실패");
        }
        chatMessageRepository.save(entity);
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("type", "REACTION_UPDATED");
        broadcast.put("messageId", messageId);
        broadcast.put("reactions", map);
        messagingTemplate.convertAndSend("/topic/chat/" + entity.getChatRoomId(), broadcast);
        return Result.<Boolean, ChatErrorCode>ok(added);
    }).orElse(Result.err(ChatErrorCode.NOT_FOUND, "메시지를 찾을 수 없습니다."));
}
```

- [ ] **Step 2: Update controller**

```java
@PostMapping("/{roomId}/messages/{messageId}/reactions")
@RequireMember
public ResponseEntity<?> toggleReaction(
        @PathVariable String roomId,
        @PathVariable String messageId,
        @RequestBody Map<String, String> body,
        @AuthenticatedUser String userId) {
    String emoji = body.get("emoji");
    if (emoji == null) return ResponseEntity.badRequest().body(ApiResponse.error("emoji가 필요합니다."));
    Result<Boolean, ChatErrorCode> result = messageReactionService.toggleReaction(messageId, emoji, userId);
    if (result.isFailure()) return ErrorResponses.from(result);
    return ResponseEntity.ok(ApiResponse.ok(result.value()));
}
```

- [ ] **Step 3: Update test + commit**

```bash
./gradlew :chat-service:test --tests MessageReactionServiceTest
git add -A
git commit -m "refactor(chat-service): MessageReactionService returns Result<Boolean,ChatErrorCode>

Wrapped Boolean indicates added (true) vs removed (false). NOT_FOUND
now returns 404 instead of silent boolean false."
```

---

### Task 25: Migrate `InviteLinkService.resolveToken`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/InviteLinkService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/InviteLinkServiceTest.java` (if exists; skip if it doesn't)
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/RoomInviteController.java`

Currently `resolveToken(String token)` returns `String roomId` or `null` for expired/invalid. New contract: `Result<String, ChatErrorCode>` with `GONE` (410) for expired tokens.

- [ ] **Step 1: Read current implementation**

```bash
sed -n '1,80p' chat-service/src/main/java/com/chatflow/chat/service/InviteLinkService.java
```

Identify the existing `resolveToken` body — likely a simple `redisTemplate.opsForValue().get(...)` lookup.

- [ ] **Step 2: Change the signature and return**

Find `public String resolveToken(String token)`. Change to:

```java
public Result<String, ChatErrorCode> resolveToken(String token) {
    String key = "chatflow:invite:" + token;  // confirm actual key in current code
    String roomId = redisTemplate.opsForValue().get(key);
    if (roomId == null || roomId.isBlank()) {
        return Result.err(ChatErrorCode.GONE, "초대 링크가 만료되었거나 유효하지 않습니다.");
    }
    return Result.ok(roomId);
}
```

> If the actual key prefix differs, KEEP the existing prefix — do NOT change the Redis key.

Add imports:

```java
import com.chatflow.chat.result.ChatErrorCode;
import com.chatflow.chat.result.Result;
```

- [ ] **Step 3: Update controller `joinByInvite`**

```java
Result<String, ChatErrorCode> resolved = inviteLinkService.resolveToken(token);
if (resolved.isFailure()) {
    return ErrorResponses.from(resolved);   // returns 410 GONE
}
String roomId = resolved.value();
// ... rest of method unchanged from here
```

- [ ] **Step 4: Run all tests + commit**

```bash
./gradlew :chat-service:test
git add -A
git commit -m "refactor(chat-service): InviteLinkService.resolveToken returns Result<String,ChatErrorCode>

Expired/invalid tokens now flow through ErrorResponses.from → 410 GONE
with the original message body. Previously the controller had a
nullable-String check + handcrafted 410 response."
```

---

### Task 26: Migrate `LinkPreviewService.fetch`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/LinkPreviewService.java`
- Modify: existing test if present
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/MessageInteractionController.java`

Currently returns empty `Map<String,String>` on fetch failure. New contract: `Result<Map<String,String>, ChatErrorCode>` so the frontend can show "preview unavailable" vs "URL invalid" appropriately.

- [ ] **Step 1: Update service**

```java
public Result<Map<String, String>, ChatErrorCode> fetch(String url) {
    if (url == null || url.isBlank()) {
        return Result.err(ChatErrorCode.INVALID_INPUT, "url이 필요합니다.");
    }
    try {
        Map<String, String> preview = doFetch(url);  // rename current body of fetch()
        return Result.ok(preview);
    } catch (Exception e) {
        log.warn("Link preview fetch failed for {}: {}", url, e.getMessage());
        return Result.err(ChatErrorCode.INTERNAL_ERROR, "링크 미리보기를 불러올 수 없습니다.");
    }
}

private Map<String, String> doFetch(String url) {
    // ... move the existing fetch body here (Jsoup parse etc.)
}
```

- [ ] **Step 2: Update controller**

```java
@GetMapping("/link-preview")
public ResponseEntity<?> linkPreview(@RequestParam String url) {
    Result<Map<String, String>, ChatErrorCode> result = linkPreviewService.fetch(url);
    if (result.isFailure()) return ErrorResponses.from(result);
    return ResponseEntity.ok(ApiResponse.ok(result.value()));
}
```

Remove the `if (url == null || url.isBlank())` block from controller — the service owns that validation now.

- [ ] **Step 3: Commit**

```bash
./gradlew :chat-service:test --tests LinkPreviewServiceTest
git add -A
git commit -m "refactor(chat-service): LinkPreviewService.fetch returns Result<Map,ChatErrorCode>"
```

---

### Task 27: Migrate `RoomMembershipService.leaveRoom`

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/RoomMembershipService.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/service/RoomMembershipServiceTest.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java`

Currently `leaveRoom(roomId, userId, username)` returns `void` and may throw. New contract: `Result<Void, ChatErrorCode>` so the controller can map "not a member" → 404.

- [ ] **Step 1: Read current implementation**

```bash
grep -n "leaveRoom" chat-service/src/main/java/com/chatflow/chat/service/RoomMembershipService.java
```

- [ ] **Step 2: Change signature**

```java
public Result<Void, ChatErrorCode> leaveRoom(String roomId, String userId, String username) {
    if (!roomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
        return Result.err(ChatErrorCode.NOT_FOUND, "방 멤버가 아닙니다.");
    }
    // ... existing leave body (delete row + broadcast + clean Redis)
    return Result.ok();
}
```

- [ ] **Step 3: Update controller**

In `ChatRoomController.leaveRoom`:

```java
@DeleteMapping("/{roomId}/members/me")
@RequireAuth
public ResponseEntity<ApiResponse<?>> leaveRoom(
        @PathVariable String roomId,
        @AuthenticatedUser String userId,
        @RequestHeader(value = "X-Username", required = false) String username) {
    if (username == null || username.isBlank()) {
        return ResponseEntity.badRequest().body(ApiResponse.error("username이 필요합니다."));
    }
    Result<Void, ChatErrorCode> result = roomMembershipService.leaveRoom(roomId, userId, username);
    if (result.isFailure()) return ErrorResponses.from(result);
    return ResponseEntity.ok(ApiResponse.ok(null, username + "님이 채팅방을 나갔습니다."));
}
```

> Note: change return type from `ResponseEntity<ApiResponse<Void>>` to `ResponseEntity<ApiResponse<?>>` — the success branch returns ApiResponse&lt;Void&gt; but the failure branch via ErrorResponses returns ApiResponse&lt;?&gt;. The wildcard accepts both.

- [ ] **Step 4: Run + commit**

```bash
./gradlew :chat-service:test
git add -A
git commit -m "refactor(chat-service): RoomMembershipService.leaveRoom returns Result<Void,ChatErrorCode>

Non-member leave now returns 404 NOT_FOUND instead of silently succeeding."
```

---

### Task 28: PR-3B verification + merge

- [ ] **Step 1: Grep for residual boolean returns in target services**

```bash
grep -rn "public boolean " \
    chat-service/src/main/java/com/chatflow/chat/service/MessageEditService.java \
    chat-service/src/main/java/com/chatflow/chat/service/MessagePinService.java \
    chat-service/src/main/java/com/chatflow/chat/service/MessageReactionService.java
```

Expected: zero matches.

- [ ] **Step 2: Run full test suite**

```bash
./gradlew :chat-service:test
```

Expected: BUILD SUCCESSFUL. New tests from Tasks 18 + 20 add ~9 more (total ~401).

- [ ] **Step 3: Dispatch superpowers:code-reviewer + merge after approval**

```bash
git checkout develop && git pull --ff-only
git merge --no-ff refactor/stage-3b-result-type
git push origin develop
```

---

# PR-3C — `UserPresenceService` decomposition

**Scope:** Split the 254-LOC `UserPresenceService` into 4 focused services (≤ 120 LOC each) with the orchestrator dropping to ≤ 100 LOC. Public API (`join(msg, sessionId)`, `join(msg)`, `leave(roomId, username, sessionId)`, `leave(roomId, username)`, `getRoomParticipantUserIds(roomId)`) is preserved — existing `UserPresenceServiceBanGateTest`, `UserPresenceServiceRoomFullTest`, `UserPresenceServiceMembershipFailureTest` must stay green.

**Extraction map:**

| New service | Source methods | LOC budget |
|-------------|---------------|-----------|
| `BanCheckService` | `checkBanGate` | ≤ 60 |
| `RoomFullnessService` | `handleRoomFullIfNeeded` (preserves `message.chatRoomId` side-effect) | ≤ 120 |
| `ParticipantRegistryService` | `registerParticipant` + `getRoomParticipantUserIds` + Redis-level removal (the `redisTemplate.opsForSet().remove` loop in `leave`) | ≤ 120 |
| `PresenceBroadcastService` | `broadcastJoin` + new `broadcastLeave` (existing broadcast block inside `leave`) | ≤ 100 |
| `UserPresenceService` (orchestrator) | `join(msg, sessionId)`, `join(msg)`, `leave(...)`, delegates to the four extracted services + `chatPersistenceService` + `participantService.syncParticipantCount` | ≤ 100 |

**Branch:**

```bash
git checkout develop && git pull --ff-only
git checkout -b refactor/stage-3c-presence-decomp
```

---

### Task 29: `BanCheckService` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/presence/BanCheckServiceTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/service/presence/BanCheckService.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.RoomBanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BanCheckServiceTest {

    @Mock RoomBanService roomBanService;
    @Mock SimpMessagingTemplate messagingTemplate;

    private BanCheckService banCheckService;

    @BeforeEach
    void setUp() {
        banCheckService = new BanCheckService(roomBanService, messagingTemplate);
    }

    @Test
    void empty_userId_passes_gate() {
        assertThat(banCheckService.checkBanGate("", "room-1", "anon")).isFalse();
        verifyNoInteractions(roomBanService);
    }

    @Test
    void non_banned_user_passes_gate() {
        when(roomBanService.isBanned("room-1", "user-1")).thenReturn(false);
        assertThat(banCheckService.checkBanGate("user-1", "room-1", "alice")).isFalse();
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void banned_user_is_blocked_and_error_broadcast() {
        when(roomBanService.isBanned("room-1", "user-1")).thenReturn(true);
        assertThat(banCheckService.checkBanGate("user-1", "room-1", "alice")).isTrue();
        verify(messagingTemplate).convertAndSend(
                eq("/topic/chat/room-1/errors"),
                eq(Map.of("type", "ROOM_BANNED", "roomId", "room-1")));
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.RoomBanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Pre-join ban gate. Extracted from UserPresenceService.checkBanGate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanCheckService {

    private final RoomBanService roomBanService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @return true if the user is banned and join should be aborted.
     *         As a side effect, broadcasts a ROOM_BANNED error to the room
     *         topic so the client can render the rejection.
     */
    public boolean checkBanGate(String userId, String chatRoomId, String username) {
        if (!userId.isEmpty() && roomBanService.isBanned(chatRoomId, userId)) {
            log.warn("User {} attempted to join banned room {}", username, chatRoomId);
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + chatRoomId + "/errors",
                    Map.of("type", "ROOM_BANNED", "roomId", chatRoomId));
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :chat-service:test --tests BanCheckServiceTest
git add -A
git commit -m "feat(chat-service): extract BanCheckService from UserPresenceService"
```

---

### Task 30: `PresenceBroadcastService` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/presence/PresenceBroadcastServiceTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/service/presence/PresenceBroadcastService.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceBroadcastServiceTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock ChatPersistenceService chatPersistenceService;

    private PresenceBroadcastService broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new PresenceBroadcastService(messagingTemplate, chatPersistenceService);
    }

    @Test
    void broadcastJoin_sends_presence_topic_and_outbox_event() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUsername("alice");

        broadcaster.broadcastJoin(msg, 3);

        assertThat(msg.getType()).isEqualTo(ChatMessage.MessageType.JOIN);
        assertThat(msg.getContent()).isEqualTo("alice님이 입장하셨습니다.");
        assertThat(msg.getMessageId()).isNotBlank();

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/presence"), body.capture());
        assertThat(body.getValue())
                .containsEntry("type", "JOIN")
                .containsEntry("username", "alice")
                .containsEntry("participantCount", 3);

        verify(chatPersistenceService).saveOutboxEventAndPublish(
                eq(msg), eq(KafkaTopics.CHAT_MESSAGES), eq("USER_JOINED"));
    }

    @Test
    void broadcastLeave_sends_leave_topic_when_user_has_no_remaining_sessions() {
        broadcaster.broadcastLeave("room-1", "alice", 2);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/presence"), body.capture());
        assertThat(body.getValue())
                .containsEntry("type", "LEAVE")
                .containsEntry("username", "alice")
                .containsEntry("participantCount", 2);
    }

    @Test
    void persistLeaveEvent_writes_outbox_event() {
        broadcaster.persistLeaveEvent("room-1", "alice");
        verify(chatPersistenceService).saveOutboxEventAndPublish(
                any(ChatMessage.class), eq(KafkaTopics.CHAT_MESSAGES), eq("USER_LEFT"));
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.service.ChatPersistenceService;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP presence broadcasts (JOIN / LEAVE topics) and the corresponding
 * outbox events for downstream consumers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatPersistenceService chatPersistenceService;

    public void broadcastJoin(ChatMessage message, int participantCount) {
        message.setType(ChatMessage.MessageType.JOIN);
        message.setTimestamp(LocalDateTime.now());
        message.setMessageId(UUID.randomUUID().toString());
        message.setContent(message.getUsername() + "님이 입장하셨습니다.");

        log.info("User {} joined chat room {}", message.getUsername(), message.getChatRoomId());

        messagingTemplate.convertAndSend("/topic/chat/" + message.getChatRoomId() + "/presence",
                Map.of("type", "JOIN",
                        "roomId", message.getChatRoomId(),
                        "username", message.getUsername(),
                        "participantCount", participantCount,
                        "timestamp", LocalDateTime.now().toString()));

        chatPersistenceService.saveOutboxEventAndPublish(message, KafkaTopics.CHAT_MESSAGES, "USER_JOINED");
    }

    public void broadcastLeave(String roomId, String username, int participantCount) {
        messagingTemplate.convertAndSend("/topic/chat/" + roomId + "/presence",
                Map.of("type", "LEAVE",
                        "roomId", roomId,
                        "username", username,
                        "participantCount", participantCount,
                        "timestamp", LocalDateTime.now().toString()));
    }

    public void persistLeaveEvent(String roomId, String username) {
        ChatMessage leaveMessage = new ChatMessage();
        leaveMessage.setChatRoomId(roomId);
        leaveMessage.setUsername(username);
        leaveMessage.setType(ChatMessage.MessageType.LEAVE);
        leaveMessage.setTimestamp(LocalDateTime.now());
        leaveMessage.setMessageId(UUID.randomUUID().toString());
        leaveMessage.setContent(username + "님이 퇴장하셨습니다.");

        chatPersistenceService.saveOutboxEventAndPublish(leaveMessage, KafkaTopics.CHAT_MESSAGES, "USER_LEFT");
        log.info("User {} left chat room {}", username, roomId);
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :chat-service:test --tests PresenceBroadcastServiceTest
git add -A
git commit -m "feat(chat-service): extract PresenceBroadcastService from UserPresenceService"
```

---

### Task 31: `ParticipantRegistryService` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/presence/ParticipantRegistryServiceTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/service/presence/ParticipantRegistryService.java`

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipantRegistryServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock SetOperations<String, String> setOperations;
    @Mock RoomMemberRepository roomMemberRepository;
    @Mock ParticipantService participantService;

    private ParticipantRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = new ParticipantRegistryService(redisTemplate, roomMemberRepository, participantService);
    }

    @Test
    void getRoomParticipantUserIds_extracts_first_segment_and_dedupes() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1"))
                .thenReturn(Set.of("u1:s1:alice", "u1:s2:alice", "u2:s3:bob"));

        Set<String> result = registry.getRoomParticipantUserIds("room-1");

        assertThat(result).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void getRoomParticipantUserIds_returns_empty_when_no_members() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1")).thenReturn(null);

        assertThat(registry.getRoomParticipantUserIds("room-1")).isEmpty();
    }

    @Test
    void register_writes_to_redis_and_backfills_room_member() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(false);

        registry.register(msg, "session-1");

        verify(setOperations).add("chatflow:room:participants:room-1", "user-1:session-1:alice");
        verify(redisTemplate).expire(eq("chatflow:room:participants:room-1"), anyLong(), any());
        verify(roomMemberRepository).save(any(RoomMemberEntity.class));
        verify(participantService).setParticipantCount(eq("room-1"), anyInt());
    }

    @Test
    void register_swallows_DataIntegrityViolation_on_concurrent_insert() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomMemberRepository.existsByRoomIdAndUserId("room-1", "user-1")).thenReturn(false);
        when(roomMemberRepository.save(any(RoomMemberEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        registry.register(msg, "session-1");  // must not throw
    }

    @Test
    void removeSession_removes_specific_session_entry() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("chatflow:room:participants:room-1"))
                .thenReturn(Set.of("u1:s1:alice", "u1:s2:alice"));

        registry.removeSession("room-1", "alice", "s1");

        verify(setOperations).remove("chatflow:room:participants:room-1", "u1:s1:alice");
        verify(setOperations, never()).remove(anyString(), eq("u1:s2:alice"));
    }
}
```

> Add `import static org.mockito.ArgumentMatchers.anyInt;` and the `any()` import already present.

- [ ] **Step 2: Implement**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis SET-backed participant registry + room_members backfill. Entry
 * format: "userId:sessionId:username".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantRegistryService {

    private static final String KEY_PREFIX = "chatflow:room:participants:";

    private final StringRedisTemplate redisTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final ParticipantService participantService;

    public void register(ChatMessage message, String sessionId) {
        String key = KEY_PREFIX + message.getChatRoomId();
        String safeUserId = message.getUserId() != null ? message.getUserId() : "anonymous";
        String safeSessionId = sessionId != null ? sessionId : "unknown";
        String entry = safeUserId + ":" + safeSessionId + ":" + message.getUsername();

        redisTemplate.opsForSet().add(key, entry);
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
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
            } catch (DataIntegrityViolationException e) {
                log.info("RoomMember already exists (concurrent insert): roomId={} userId={}",
                        message.getChatRoomId(), safeUserId);
            }
        }
    }

    /**
     * Returns the set of distinct userIds currently active in the room.
     * Multiple sessions belonging to the same userId are collapsed.
     */
    public Set<String> getRoomParticipantUserIds(String roomId) {
        Set<String> members = redisTemplate.opsForSet().members(KEY_PREFIX + roomId);
        if (members == null || members.isEmpty()) return Set.of();
        return members.stream().map(e -> e.split(":", 3)[0]).collect(Collectors.toSet());
    }

    /**
     * Removes a specific session entry (or all of a username's entries if
     * sessionId is null, as fallback for old callers).
     */
    public void removeSession(String roomId, String username, String sessionId) {
        String key = KEY_PREFIX + roomId;
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null) return;

        if (sessionId != null) {
            members.stream()
                    .filter(e -> e.contains(":" + sessionId + ":"))
                    .forEach(e -> redisTemplate.opsForSet().remove(key, e));
        } else {
            members.stream()
                    .filter(e -> e.endsWith(":" + username))
                    .forEach(e -> redisTemplate.opsForSet().remove(key, e));
        }
    }

    public boolean userStillPresent(String roomId, String username) {
        Set<String> remaining = redisTemplate.opsForSet().members(KEY_PREFIX + roomId);
        return remaining != null && remaining.stream().anyMatch(e -> e.endsWith(":" + username));
    }

    public void syncParticipantCount(String roomId) {
        Set<String> userIds = getRoomParticipantUserIds(roomId);
        participantService.setParticipantCount(roomId, userIds.size());
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :chat-service:test --tests ParticipantRegistryServiceTest
git add -A
git commit -m "feat(chat-service): extract ParticipantRegistryService from UserPresenceService"
```

---

### Task 32: `RoomFullnessService` — TDD

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/service/presence/RoomFullnessServiceTest.java`
- Create: `chat-service/src/main/java/com/chatflow/chat/service/presence/RoomFullnessService.java`

**Side effect preserved:** General-room redirect MUTATES `message.chatRoomId` to the new room ID. Tests must verify this exact behavior. Document via Javadoc + commit body.

- [ ] **Step 1: Write the failing test**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomFullnessServiceTest {

    @Mock ParticipantService participantService;
    @Mock ChatRoomService chatRoomService;
    @Mock RoomMemberRepository roomMemberRepository;
    @Mock SimpMessagingTemplate messagingTemplate;

    private RoomFullnessService fullness;

    @BeforeEach
    void setUp() {
        fullness = new RoomFullnessService(
                participantService, chatRoomService, roomMemberRepository, messagingTemplate);
    }

    @Test
    void room_not_full_returns_false() {
        when(participantService.isRoomFull("room-1")).thenReturn(false);
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
    }

    @Test
    void already_joined_returns_false_even_if_full() {
        when(participantService.isRoomFull("room-1")).thenReturn(true);
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        assertThat(fullness.handleIfFull(msg, "user-1", true)).isFalse();
    }

    @Test
    void DM_full_rejects_non_member() {
        ChatRoom dm = ChatRoom.builder().id("dm-1").name("dm").roomType(RoomType.DIRECT).build();
        when(participantService.isRoomFull("dm-1")).thenReturn(true);
        when(chatRoomService.getRoom("dm-1")).thenReturn(Optional.of(dm));
        when(roomMemberRepository.existsByRoomIdAndUserId("dm-1", "user-1")).thenReturn(false);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("dm-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isTrue();
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/dm-1/errors"), any(java.util.Map.class));
    }

    @Test
    void DM_full_allows_existing_member() {
        ChatRoom dm = ChatRoom.builder().id("dm-1").name("dm").roomType(RoomType.DIRECT).build();
        when(participantService.isRoomFull("dm-1")).thenReturn(true);
        when(chatRoomService.getRoom("dm-1")).thenReturn(Optional.of(dm));
        when(roomMemberRepository.existsByRoomIdAndUserId("dm-1", "user-1")).thenReturn(true);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("dm-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
    }

    @Test
    void general_room_full_mutates_chatRoomId_and_broadcasts_redirect() {
        ChatRoom original = ChatRoom.builder().id("room-1").name("일반-1").roomType(RoomType.PUBLIC).build();
        ChatRoom newRoom = ChatRoom.builder().id("room-2").name("일반-2").roomType(RoomType.PUBLIC).build();
        when(participantService.isRoomFull("room-1")).thenReturn(true);
        when(chatRoomService.getRoom("room-1")).thenReturn(Optional.of(original));
        when(participantService.findOrCreateAvailableRoom("일반")).thenReturn(newRoom);

        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUsername("alice");

        assertThat(fullness.handleIfFull(msg, "user-1", false)).isFalse();
        assertThat(msg.getChatRoomId()).isEqualTo("room-2");  // side effect: mutation
        verify(messagingTemplate).convertAndSend(eq("/topic/chat/room-1/errors"), any(java.util.Map.class));
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.chatflow.chat.service.presence;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.ChatRoomService;
import com.chatflow.chat.service.ParticipantService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Room-capacity gate for join attempts. DM rooms reject non-members,
 * general rooms redirect to a sibling room.
 *
 * <p><b>Side effect:</b> When a general room is full, the caller's
 * {@code ChatMessage.chatRoomId} is mutated to the redirect target. The
 * caller MUST treat the message as moved after this method returns false.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomFullnessService {

    private final ParticipantService participantService;
    private final ChatRoomService chatRoomService;
    private final RoomMemberRepository roomMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @return true if the join should be aborted (DM full, non-member).
     *         false if the caller should proceed — possibly into the
     *         redirected room (general-room redirect mutates the message).
     */
    public boolean handleIfFull(ChatMessage message, String currentUserId, boolean alreadyJoined) {
        if (!participantService.isRoomFull(message.getChatRoomId())) return false;
        if (alreadyJoined) return false;

        ChatRoom room = chatRoomService.getRoom(message.getChatRoomId()).orElse(null);

        if (room != null && room.getRoomType() == RoomType.DIRECT) {
            boolean isExistingMember = !currentUserId.isEmpty() &&
                    roomMemberRepository.existsByRoomIdAndUserId(
                            message.getChatRoomId(), currentUserId);
            if (!isExistingMember) {
                log.warn("DM room {} is full, rejecting non-member {}",
                        message.getChatRoomId(), message.getUsername());
                messagingTemplate.convertAndSend(
                        "/topic/chat/" + message.getChatRoomId() + "/errors",
                        Map.of("type", "ROOM_FULL_DM",
                                "roomId", message.getChatRoomId(),
                                "roomName", room.getName()));
                return true;
            }
            log.info("DM {} full but {} is existing member — allowing re-entry",
                    message.getChatRoomId(), message.getUsername());
            return false;
        }

        String baseName = room != null ? room.getName().replaceAll("-\\d+$", "") : "일반";
        ChatRoom newRoom = participantService.findOrCreateAvailableRoom(baseName);

        log.info("Room {} full, redirecting user {} to {}",
                message.getChatRoomId(), message.getUsername(), newRoom.getId());
        messagingTemplate.convertAndSend(
                "/topic/chat/" + message.getChatRoomId() + "/errors",
                Map.of("type", "ROOM_FULL", "redirectTo", newRoom.getId(), "roomName", newRoom.getName()));

        message.setChatRoomId(newRoom.getId());
        return false;
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :chat-service:test --tests RoomFullnessServiceTest
git add -A
git commit -m "feat(chat-service): extract RoomFullnessService from UserPresenceService

Preserves the message.chatRoomId mutation on general-room redirect —
documented via Javadoc + test."
```

---

### Task 33: Refactor `UserPresenceService` to orchestrator

**Files:**
- Modify: `chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java`

- [ ] **Step 1: Replace the entire file with the orchestrator**

```java
package com.chatflow.chat.service;

import com.chatflow.chat.service.presence.BanCheckService;
import com.chatflow.chat.service.presence.ParticipantRegistryService;
import com.chatflow.chat.service.presence.PresenceBroadcastService;
import com.chatflow.chat.service.presence.RoomFullnessService;
import com.chatflow.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Orchestrates a user's join/leave lifecycle for a chat room. Delegates
 * gate checks (ban, room-full), registry side effects (Redis SET +
 * room_members backfill), and STOMP broadcast to focused collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final BanCheckService banCheckService;
    private final RoomFullnessService roomFullnessService;
    private final ParticipantRegistryService participantRegistry;
    private final PresenceBroadcastService presenceBroadcast;

    public void join(ChatMessage message, String sessionId) {
        String currentUserId = message.getUserId() != null ? message.getUserId() : "";

        if (banCheckService.checkBanGate(currentUserId, message.getChatRoomId(), message.getUsername())) {
            return;
        }

        Set<String> existingUserIds = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId());
        boolean alreadyJoined = !currentUserId.isEmpty() && existingUserIds.contains(currentUserId);

        if (roomFullnessService.handleIfFull(message, currentUserId, alreadyJoined)) {
            return;
        }

        participantRegistry.register(message, sessionId);

        if (alreadyJoined) {
            log.debug("User {} reconnected to room {} via additional session — suppressing JOIN broadcast",
                    message.getUsername(), message.getChatRoomId());
            return;
        }

        int participantCount = participantRegistry.getRoomParticipantUserIds(message.getChatRoomId()).size();
        presenceBroadcast.broadcastJoin(message, participantCount);
    }

    public void join(ChatMessage message) {
        join(message, null);
    }

    public void leave(String roomId, String username, String sessionId) {
        participantRegistry.removeSession(roomId, username, sessionId);

        boolean userStillPresent = participantRegistry.userStillPresent(roomId, username);

        if (!userStillPresent) {
            presenceBroadcast.persistLeaveEvent(roomId, username);
        } else {
            log.info("User {} closed a tab in room {} (still has active sessions)", username, roomId);
        }

        participantRegistry.syncParticipantCount(roomId);

        if (!userStillPresent) {
            int participantCount = participantRegistry.getRoomParticipantUserIds(roomId).size();
            presenceBroadcast.broadcastLeave(roomId, username, participantCount);
        }
    }

    public void leave(String roomId, String username) {
        leave(roomId, username, null);
    }

    public Set<String> getRoomParticipantUserIds(String roomId) {
        return participantRegistry.getRoomParticipantUserIds(roomId);
    }
}
```

- [ ] **Step 2: Run the existing presence test suite**

```bash
./gradlew :chat-service:test --tests "UserPresenceService*Test"
```

> The existing `UserPresenceServiceBanGateTest`, `UserPresenceServiceRoomFullTest`, `UserPresenceServiceMembershipFailureTest` constructed `UserPresenceService` directly with its old 7-dep constructor. They will FAIL to compile after this change. Update each test's `@BeforeEach` setUp to construct with the new 4-dep constructor — the test mocks need to switch from `@Mock RoomBanService`/`@Mock StringRedisTemplate` etc. to `@Mock BanCheckService`/`@Mock RoomFullnessService`/`@Mock ParticipantRegistryService`/`@Mock PresenceBroadcastService`.

For each existing test, stub the appropriate collaborator and verify the orchestrator delegated correctly. Example for `UserPresenceServiceBanGateTest`:

```java
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceBanGateTest {
    @Mock BanCheckService banCheckService;
    @Mock RoomFullnessService roomFullnessService;
    @Mock ParticipantRegistryService participantRegistry;
    @Mock PresenceBroadcastService presenceBroadcast;
    private UserPresenceService service;

    @BeforeEach
    void setUp() {
        service = new UserPresenceService(banCheckService, roomFullnessService,
                participantRegistry, presenceBroadcast);
    }

    @Test
    void banned_user_join_is_aborted_before_registry() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId("room-1");
        msg.setUserId("user-1");
        msg.setUsername("alice");
        when(banCheckService.checkBanGate("user-1", "room-1", "alice")).thenReturn(true);

        service.join(msg, "session-1");

        verifyNoInteractions(participantRegistry);
        verifyNoInteractions(presenceBroadcast);
    }
}
```

Repeat the pattern for the other two existing tests — verify they assert at the **orchestrator level** (which collaborator gets called in which order) rather than the old inline Redis/SimpMessagingTemplate verifications.

- [ ] **Step 3: Run full chat-service test suite**

```bash
./gradlew :chat-service:test
```

Expected: BUILD SUCCESSFUL, ≥ 416 tests PASS (≈401 from PR-3B + 15 new in PR-3C tasks 29–32).

- [ ] **Step 4: Verify LOC budget**

```bash
wc -l chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java \
      chat-service/src/main/java/com/chatflow/chat/service/presence/*.java
```

Expected:
- `UserPresenceService.java` ≤ 100
- `BanCheckService.java` ≤ 60
- `RoomFullnessService.java` ≤ 120
- `ParticipantRegistryService.java` ≤ 120
- `PresenceBroadcastService.java` ≤ 100

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(chat-service): UserPresenceService becomes orchestrator (254→<=100 LOC)

Delegates to BanCheckService, RoomFullnessService, ParticipantRegistryService,
PresenceBroadcastService. Public API (join/leave/getRoomParticipantUserIds)
preserved — callers (ChatService, MessageEventListener) need no change.

Existing UserPresenceService*Test classes rewired to mock the new collaborators
and verify orchestrator-level delegation order."
```

---

### Task 34: PR-3C verification + merge

- [ ] **Step 1: Confirm LOC budget**

```bash
wc -l chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java \
      chat-service/src/main/java/com/chatflow/chat/service/presence/*.java
```

All ≤ budget.

- [ ] **Step 2: Confirm no other file imports the old internal methods**

```bash
grep -rn "checkBanGate\|handleRoomFullIfNeeded\|registerParticipant\|broadcastJoin" \
    chat-service/src/main/java/com/chatflow/chat/
```

Expected: results only inside the new `presence/` subpackage.

- [ ] **Step 3: Run all tests**

```bash
./gradlew :chat-service:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Dispatch superpowers:code-reviewer + merge after approval**

```bash
git checkout develop && git pull --ff-only
git merge --no-ff refactor/stage-3c-presence-decomp
git push origin develop
```

---

# Stage 3 Final Exit Criteria

Run all three after PR-3C merges into develop:

- [ ] `grep -rn "userId == null" chat-service/src/main/java/com/chatflow/chat/controller/ --include='*.java' | grep -v ChatController.java` → 0 matches
- [ ] `wc -l chat-service/src/main/java/com/chatflow/chat/service/UserPresenceService.java` → ≤ 100
- [ ] `./gradlew :chat-service:test` → ≥ 416 tests PASS
- [ ] `grep -rn "public boolean " chat-service/src/main/java/com/chatflow/chat/service/Message{Edit,Pin,Reaction}Service.java` → 0 matches
- [ ] K3s staging rollout via `.github/workflows/deploy-k3s.yml` (manual trigger) → smoke test on app.chatflow.ai.kr passes for: open room → send → edit → delete → react → pin → invite-link → leave

---




