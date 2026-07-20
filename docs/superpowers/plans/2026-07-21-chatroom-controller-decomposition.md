# ChatRoomController Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Extract the three message-history/message-send endpoints out of the 389-line grab-bag `ChatRoomController` into a focused `RoomMessageController`, so `ChatRoomController` is left with cohesive room-lifecycle/membership concerns. Pure structural refactor — ZERO behavior change.

**Architecture:** `ChatRoomController` currently mixes room lifecycle (list/get/create/get-or-create/delete/settings/dm/hide/verify/participants/leave) with message-history reads and a REST message-send fallback. Move `getMessages` (GET `/{roomId}/messages`), `getMessagesByCursor` (GET `/{roomId}/messages/cursor`), and `sendMessage` (POST `/{roomId}/messages`) — plus the `SendMessageRequest` record — into a new `RoomMessageController` mapped at the same `/api/chat/rooms` base (no path collision, since these three mappings leave `ChatRoomController`). The dependencies used only by these handlers (`MessageReadService`, `ChatMessageResponseMapper`, `MessageSenderService`) move with them; `AuditService` is injected into BOTH (it is also used by `hideRoom`).

**Tech Stack:** Spring Boot 3.2 / Java 17 / Spring MVC / JUnit5 + standalone MockMvc + Mockito.

**Constraints / decisions:**
- **ZERO behavior change**: identical URLs, HTTP methods, request/response bodies, validation, auth annotations (`@RequireMember` on all three), `size` clamping (`Math.min(size, 100)`), audit call (`MESSAGE_READ` in getMessages), and the cursor result map shape (`messages`/`nextCursor`/`hasMore`). Copy the handler bodies verbatim.
- Keep the same base path `@RequestMapping("/api/chat/rooms")` on the new controller. After extraction `ChatRoomController` no longer maps `GET /{roomId}/messages`, `GET /{roomId}/messages/cursor`, or `POST /{roomId}/messages`, so there is no ambiguous-mapping collision.
- `SendMessageRequest` record moves into `RoomMessageController` (it is only used by `sendMessage`). `GetOrCreateRequest`/`CreateRoomRequest`/`VerifyPasswordRequest`/`CreateDmRequest`/`UpdateSettingsRequest` stay in `ChatRoomController`.
- Remove from `ChatRoomController` the three fields now unused by it: `messageReadService`, `chatMessageResponseMapper`, `messageSenderService`. KEEP `auditService` (used by `hideRoom`).
- The existing controller test is standalone MockMvc (`@ExtendWith(MockitoExtension.class)` + `@InjectMocks ChatRoomController` + `MockMvcBuilders.standaloneSetup(controller)`), NOT a Spring slice. So the moved endpoints' tests must move to a new `RoomMessageControllerTest` that stands up `RoomMessageController`.

---

## Task 1: Create RoomMessageController

**Files:**
- Create: `chat-service/src/main/java/com/chatflow/chat/controller/RoomMessageController.java`
- Modify: `chat-service/src/main/java/com/chatflow/chat/controller/ChatRoomController.java`

- [ ] **Step 1: Create `RoomMessageController`** with the three handlers copied verbatim from `ChatRoomController` (getMessages :130-142, getMessagesByCursor :148-167, sendMessage :310-338) and the `SendMessageRequest` record (:378-388). Inject exactly the deps these use:

```java
package com.chatflow.chat.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireMember;
import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.mapper.ChatMessageResponseMapper;
import com.chatflow.chat.service.message.MessageSenderService;
import com.chatflow.chat.service.moderation.AuditService;
import com.chatflow.chat.service.read.MessageReadService;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.common.dto.AuditEvent;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.ChatMessageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message history reads + REST message-send fallback for a room.
 * Split out of ChatRoomController (which retains room lifecycle/membership).
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class RoomMessageController {

    private final MessageReadService messageReadService;
    private final ChatMessageResponseMapper chatMessageResponseMapper;
    private final MessageSenderService messageSenderService;
    private final AuditService auditService;

    @RequireMember
    @GetMapping("/{roomId}/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        size = Math.min(size, 100);
        Page<ChatMessageResponse> messages = messageReadService.getMessages(roomId, PageRequest.of(page, size))
                .map(chatMessageResponseMapper::toResponse);
        auditService.logAccess(userId, username, roomId, AuditEvent.MESSAGE_READ);
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    /**
     * 커서 기반 페이징 — 무한 스크롤에 최적화.
     * before 파라미터 없으면 최신 메시지부터 반환.
     */
    @RequireMember
    @GetMapping("/{roomId}/messages/cursor")
    public ResponseEntity<?> getMessagesByCursor(
            @PathVariable String roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticatedUser String userId) {
        size = Math.min(size, 100);
        List<ChatMessageEntity> entities = messageReadService.getMessagesByCursor(roomId, before, size);

        LocalDateTime nextCursor = entities.isEmpty() ? null
                : entities.get(entities.size() - 1).getTimestamp();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", chatMessageResponseMapper.toResponseList(entities));
        result.put("nextCursor", nextCursor);
        result.put("hasMore", entities.size() == size);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * REST fallback for sending a message when STOMP is disconnected.
     * Also used for forwarded messages with forwardedFrom metadata.
     */
    @RequireMember
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<?> sendMessage(
            @PathVariable String roomId,
            @Valid @RequestBody SendMessageRequest body,
            @AuthenticatedUser String userId,
            @RequestHeader(value = "X-Username", required = false) String username) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(roomId);
        msg.setUserId(userId);
        msg.setUsername(username != null ? username : userId);
        msg.setContent(body.content());
        msg.setType(ChatMessage.MessageType.CHAT);
        msg.setPriority(body.priority() != null && !body.priority().isBlank()
                ? body.priority() : "ROUTINE");
        if (body.parentMessageId() != null && !body.parentMessageId().isBlank()) {
            msg.setParentMessageId(body.parentMessageId());
        }
        if (body.forwardedFrom() != null && !body.forwardedFrom().isBlank()) {
            msg.setForwardedFrom(body.forwardedFrom());
        }
        if (body.fileUrl() != null && !body.fileUrl().isBlank()) {
            msg.setFileUrl(body.fileUrl());
            msg.setFileName(body.fileName());
            msg.setFileContentType(body.fileContentType());
        }
        messageSenderService.send(msg);
        return ResponseEntity.ok(ApiResponse.ok(null, "메시지를 전송했습니다."));
    }

    public record SendMessageRequest(
            @NotBlank(message = "content는 필수입니다")
            String content,

            String priority,
            String parentMessageId,
            String forwardedFrom,
            String fileUrl,
            String fileName,
            String fileContentType
    ) {}
}
```

- [ ] **Step 2: Remove the moved code from `ChatRoomController`.** Delete the three handler methods (`getMessages`, `getMessagesByCursor`, `sendMessage`) and the `SendMessageRequest` record. Remove the three now-unused fields: `messageReadService`, `chatMessageResponseMapper`, `messageSenderService`. KEEP `auditService` (used by `hideRoom`). Remove now-unused imports (`ChatMessageEntity`, `ChatMessageResponse`, `MessageReadService`, `MessageSenderService`, `ChatMessageResponseMapper`, `PageRequest`, `DateTimeFormat`, and `AuditEvent` ONLY if `hideRoom` no longer references it — it DOES via `ROOM_HIDDEN`/`ROOM_HIDE_DENIED`, so KEEP `AuditEvent`; `Page`/`LinkedHashMap`/`LocalDateTime` — keep only if still used elsewhere in the class). Let the compiler guide unused-import removal.

- [ ] **Step 3: Compile** `./gradlew :chat-service:compileJava` → BUILD SUCCESSFUL, and confirm no ambiguous-mapping error at context load (verified by the test task in Task 2).

- [ ] **Step 4: Commit** `refactor(chat): extract RoomMessageController from ChatRoomController`.

## Task 2: Move the message-endpoint tests

**Files:**
- Create: `chat-service/src/test/java/com/chatflow/chat/controller/RoomMessageControllerTest.java`
- Modify: `chat-service/src/test/java/com/chatflow/chat/controller/ChatRoomControllerTest.java`

- [ ] **Step 1:** Read `ChatRoomControllerTest` (the `@DisplayName("POST /api/chat/rooms/{roomId}/messages")` block, ~line 542+, and any GET `/messages` / cursor tests). Create `RoomMessageControllerTest` mirroring `ChatRoomControllerTest`'s harness: `@ExtendWith(MockitoExtension.class)`, `@Mock` the four deps (`MessageReadService`, `ChatMessageResponseMapper`, `MessageSenderService`, `AuditService`), `@InjectMocks RoomMessageController controller`, and `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())` plus whatever argument resolvers / custom `@AuthenticatedUser` + `@RequireMember` handling the original setup uses (copy the `standaloneSetup(...)` builder chain verbatim from `ChatRoomControllerTest`, since `@AuthenticatedUser`/`@RequireMember` need the same `HandlerMethodArgumentResolver`/interceptor wiring). Move the message-endpoint test methods into it unchanged (same URLs/assertions).

- [ ] **Step 2:** In `ChatRoomControllerTest`, delete the moved message-endpoint test block(s) and the `@Mock` fields no longer needed by `ChatRoomController` (`messageReadService`, `messageSenderService`, `chatMessageResponseMapper` if present). Keep `auditService` (still used by `hideRoom` tests).

- [ ] **Step 3:** `./gradlew :chat-service:test` → all green (both `ChatRoomControllerTest` and the new `RoomMessageControllerTest`, plus the rest of the suite). The message-endpoint behavior is unchanged; the tests prove identical responses from the new controller.

- [ ] **Step 4: Commit** `test(chat): move message-endpoint tests to RoomMessageControllerTest`.

## Task 3: Whole-branch review + merge
- [ ] Full `./gradlew test` green.
- [ ] Confirm no behavior drift: diff the moved handler bodies against the originals (must be verbatim); confirm `@RequireMember`/`@AuthenticatedUser`/validation/`size` clamp/audit call all preserved; confirm no ambiguous Spring mapping (context loads).
- [ ] Whole-branch review (fresh reviewer).
- [ ] Merge to develop (`--no-ff`) + push.

## Self-review notes
- The three endpoints move verbatim; the only real risk is the standalone-MockMvc test harness needing the same argument-resolver wiring for `@AuthenticatedUser`/`@RequireMember` — copy the builder chain exactly.
- `auditService` intentionally lives in BOTH controllers after the split (getMessages logs MESSAGE_READ; hideRoom logs ROOM_HIDDEN/ROOM_HIDE_DENIED). That is not duplication of logic, just a shared collaborator.
- No frontend/Dio change: the URLs and payloads are identical, so the Flutter client is unaffected.
