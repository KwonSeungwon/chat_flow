package com.chatflow.chat.service;

import com.chatflow.chat.dto.ReportDto;
import com.chatflow.chat.entity.*;
import com.chatflow.chat.exception.MessageNotFoundException;
import com.chatflow.chat.exception.PermissionDeniedException;
import com.chatflow.chat.exception.ReportRateLimitException;
import com.chatflow.chat.exception.RoomTypeNotSupportedException;
import com.chatflow.chat.exception.SelfReportNotAllowedException;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.ChatRoomRepository;
import com.chatflow.chat.repository.MessageReportRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageReportServiceTest {

    @Mock private MessageReportRepository messageReportRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private ChatRoomRepository chatRoomRepository;

    private RoomPermissionService roomPermissionService;
    private MessageReportService messageReportService;

    private static final String ROOM_ID = "room-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String AUTHOR_USER_ID = "author-1";
    private static final String REPORTER_USER_ID = "reporter-1";
    private static final String OWNER_ID = "owner-1";
    private static final String MOD_ID = "mod-1";
    private static final String MEMBER_ID = "member-1";

    @BeforeEach
    void setUp() {
        roomPermissionService = new RoomPermissionService(roomMemberRepository, chatRoomRepository);
        messageReportService = new MessageReportService(
                messageReportRepository, chatMessageRepository,
                roomMemberRepository, roomPermissionService);
    }

    private ChatMessageEntity message(String messageId, String chatRoomId,
                                       String userId, String username, String content) {
        return ChatMessageEntity.builder()
                .messageId(messageId)
                .chatRoomId(chatRoomId)
                .userId(userId)
                .username(username)
                .content(content)
                .timestamp(LocalDateTime.now())
                .type("CHAT")
                .build();
    }

    private RoomMemberEntity member(String userId, String username, RoomRole role) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(userId)
                .username(username)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private MessageReportEntity report(Long id, String messageId, String roomId,
                                        String reportedBy, ReportReason reason) {
        return MessageReportEntity.builder()
                .id(id)
                .messageId(messageId)
                .roomId(roomId)
                .reportedBy(reportedBy)
                .reason(reason)
                .status(ReportStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void stubGeneralRoom() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.GENERAL).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    private void stubDmRoom() {
        ChatRoom room = ChatRoom.builder().id(ROOM_ID).roomType(RoomType.DIRECT).build();
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    }

    // ── submitReport ────────────────────────────────────────────

    @Nested
    class SubmitReportTests {

        @Test
        void submitReport_happyPath_returnsIdAndPersists() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "bad content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.empty());
            when(messageReportRepository.countByReportedByAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                    .thenReturn(0L);

            MessageReportEntity saved = report(42L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);
            when(messageReportRepository.save(any(MessageReportEntity.class))).thenReturn(saved);

            Long reportId = messageReportService.submitReport(
                    MESSAGE_ID, REPORTER_USER_ID, ReportReason.SPAM, "spamming links");

            assertEquals(42L, reportId);

            ArgumentCaptor<MessageReportEntity> captor = ArgumentCaptor.forClass(MessageReportEntity.class);
            verify(messageReportRepository).save(captor.capture());
            MessageReportEntity captured = captor.getValue();
            assertEquals(MESSAGE_ID, captured.getMessageId());
            assertEquals(ROOM_ID, captured.getRoomId());
            assertEquals(REPORTER_USER_ID, captured.getReportedBy());
            assertEquals(ReportReason.SPAM, captured.getReason());
            assertEquals("spamming links", captured.getComment());
            assertEquals(ReportStatus.PENDING, captured.getStatus());
        }

        @Test
        void submitReport_messageNotFound_throwsMessageNotFoundException() {
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.empty());

            assertThrows(MessageNotFoundException.class,
                    () -> messageReportService.submitReport(
                            MESSAGE_ID, REPORTER_USER_ID, ReportReason.SPAM, null));
        }

        @Test
        void submitReport_selfReport_throwsSelfReportNotAllowed() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, "reporter", "my message");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));

            assertThrows(SelfReportNotAllowedException.class,
                    () -> messageReportService.submitReport(
                            MESSAGE_ID, REPORTER_USER_ID, ReportReason.SPAM, null));

            verify(messageReportRepository, never()).save(any());
        }

        @Test
        void submitReport_idempotent_returnsSameIdWithoutNewSave() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));

            MessageReportEntity existing = report(99L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.HARASSMENT);
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.of(existing));

            Long reportId = messageReportService.submitReport(
                    MESSAGE_ID, REPORTER_USER_ID, ReportReason.HARASSMENT, "duplicate");

            assertEquals(99L, reportId);
            verify(messageReportRepository, never()).save(any());
        }

        @Test
        void submitReport_rateLimitExceeded_throwsReportRateLimitException() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.empty());
            when(messageReportRepository.countByReportedByAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                    .thenReturn(5L);

            assertThrows(ReportRateLimitException.class,
                    () -> messageReportService.submitReport(
                            MESSAGE_ID, REPORTER_USER_ID, ReportReason.OTHER, null));

            verify(messageReportRepository, never()).save(any());
        }

        @Test
        void submitReport_rateLimitAtBoundary_fourReportsAllowed() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.empty());
            when(messageReportRepository.countByReportedByAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                    .thenReturn(4L);

            MessageReportEntity saved = report(50L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);
            when(messageReportRepository.save(any(MessageReportEntity.class))).thenReturn(saved);

            Long reportId = messageReportService.submitReport(
                    MESSAGE_ID, REPORTER_USER_ID, ReportReason.SPAM, null);

            assertEquals(50L, reportId);
            verify(messageReportRepository).save(any());
        }

        @Test
        void submitReport_withNullComment_persistsSuccessfully() {
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.empty());
            when(messageReportRepository.countByReportedByAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                    .thenReturn(0L);

            MessageReportEntity saved = report(1L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.INAPPROPRIATE);
            when(messageReportRepository.save(any(MessageReportEntity.class))).thenReturn(saved);

            Long reportId = messageReportService.submitReport(
                    MESSAGE_ID, REPORTER_USER_ID, ReportReason.INAPPROPRIATE, null);

            assertEquals(1L, reportId);
            ArgumentCaptor<MessageReportEntity> captor = ArgumentCaptor.forClass(MessageReportEntity.class);
            verify(messageReportRepository).save(captor.capture());
            assertNull(captor.getValue().getComment());
        }

        @Test
        void submitReport_concurrentDuplicate_returnsExistingId() {
            // Scenario: two threads pass the findByMessageIdAndReportedBy check simultaneously.
            // The first thread inserts successfully. The second thread hits the UNIQUE constraint.
            // The service should catch DataIntegrityViolationException and return the existing id.
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));

            // First call (idempotency check): not found yet — the other thread hasn't committed
            when(messageReportRepository.findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.empty())      // first call during idempotency check
                    .thenReturn(Optional.of(           // second call during catch recovery
                            report(77L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM)));

            when(messageReportRepository.countByReportedByAndCreatedAtAfter(eq(REPORTER_USER_ID), any()))
                    .thenReturn(0L);

            // save throws DataIntegrityViolationException (UNIQUE constraint violation)
            when(messageReportRepository.save(any(MessageReportEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            Long reportId = messageReportService.submitReport(
                    MESSAGE_ID, REPORTER_USER_ID, ReportReason.SPAM, "concurrent");

            assertEquals(77L, reportId);
            verify(messageReportRepository).save(any(MessageReportEntity.class));
            // findByMessageIdAndReportedBy called twice: once for idempotency check, once for recovery
            verify(messageReportRepository, times(2))
                    .findByMessageIdAndReportedBy(MESSAGE_ID, REPORTER_USER_ID);
        }
    }

    // ── listPendingReports ──────────────────────────────────────

    @Nested
    class ListPendingReportsTests {

        @Test
        void listPendingReports_ownerAccess_returnsDtoList() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "short content");
            MessageReportEntity reportEntity = report(1L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);

            when(messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING))
                    .thenReturn(List.of(reportEntity));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));

            RoomMemberEntity reporter = member(REPORTER_USER_ID, "reporter-name", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.of(reporter));

            List<ReportDto> result = messageReportService.listPendingReports(ROOM_ID, OWNER_ID);

            assertEquals(1, result.size());
            ReportDto dto = result.get(0);
            assertEquals(1L, dto.id());
            assertEquals(MESSAGE_ID, dto.messageId());
            assertEquals("short content", dto.messageContent());
            assertEquals("author", dto.messageAuthor());
            assertEquals("reporter-name", dto.reportedBy());
            assertEquals(REPORTER_USER_ID, dto.reportedByUserId());
            assertEquals(ReportReason.SPAM, dto.reason());
            assertEquals(ReportStatus.PENDING, dto.status());
        }

        @Test
        void listPendingReports_modAccess_succeeds() {
            stubGeneralRoom();
            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));

            when(messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING))
                    .thenReturn(List.of());

            List<ReportDto> result = messageReportService.listPendingReports(ROOM_ID, MOD_ID);

            assertTrue(result.isEmpty());
        }

        @Test
        void listPendingReports_memberAccess_throwsPermissionDenied() {
            RoomMemberEntity normalMember = member(MEMBER_ID, "member", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MEMBER_ID))
                    .thenReturn(Optional.of(normalMember));

            assertThrows(PermissionDeniedException.class,
                    () -> messageReportService.listPendingReports(ROOM_ID, MEMBER_ID));
        }

        @Test
        void listPendingReports_dmRoom_throwsRoomTypeNotSupported() {
            stubDmRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            assertThrows(RoomTypeNotSupportedException.class,
                    () -> messageReportService.listPendingReports(ROOM_ID, OWNER_ID));
        }

        @Test
        void listPendingReports_deletedMessage_showsPlaceholder() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            MessageReportEntity reportEntity = report(2L, "deleted-msg", ROOM_ID, REPORTER_USER_ID, ReportReason.HARASSMENT);

            when(messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING))
                    .thenReturn(List.of(reportEntity));
            // Message not found (deleted from DB)
            when(chatMessageRepository.findById("deleted-msg")).thenReturn(Optional.empty());

            RoomMemberEntity reporter = member(REPORTER_USER_ID, "reporter-name", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.of(reporter));

            List<ReportDto> result = messageReportService.listPendingReports(ROOM_ID, OWNER_ID);

            assertEquals(1, result.size());
            ReportDto dto = result.get(0);
            assertEquals("[삭제된 메시지]", dto.messageContent());
            assertEquals("(unknown)", dto.messageAuthor());
        }

        @Test
        void listPendingReports_longMessage_truncatedTo200Chars() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            String longContent = "A".repeat(300);
            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", longContent);
            MessageReportEntity reportEntity = report(3L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);

            when(messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING))
                    .thenReturn(List.of(reportEntity));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));

            RoomMemberEntity reporter = member(REPORTER_USER_ID, "reporter-name", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, REPORTER_USER_ID))
                    .thenReturn(Optional.of(reporter));

            List<ReportDto> result = messageReportService.listPendingReports(ROOM_ID, OWNER_ID);

            assertEquals(1, result.size());
            ReportDto dto = result.get(0);
            assertEquals(203, dto.messageContent().length()); // 200 chars + "..."
            assertTrue(dto.messageContent().endsWith("..."));
        }

        @Test
        void listPendingReports_reporterLeftRoom_showsUnknownUsername() {
            stubGeneralRoom();
            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            ChatMessageEntity msg = message(MESSAGE_ID, ROOM_ID, AUTHOR_USER_ID, "author", "content");
            MessageReportEntity reportEntity = report(4L, MESSAGE_ID, ROOM_ID, "left-user-id", ReportReason.OTHER);

            when(messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(ROOM_ID, ReportStatus.PENDING))
                    .thenReturn(List.of(reportEntity));
            when(chatMessageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(msg));
            // Reporter left the room — not found
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, "left-user-id"))
                    .thenReturn(Optional.empty());

            List<ReportDto> result = messageReportService.listPendingReports(ROOM_ID, OWNER_ID);

            assertEquals(1, result.size());
            assertEquals("(unknown)", result.get(0).reportedBy());
        }
    }

    // ── updateStatus ────────────────────────────────────────────

    @Nested
    class UpdateStatusTests {

        @Test
        void updateStatus_toResolved_setsResolvedByAndResolvedAt() {
            MessageReportEntity reportEntity = report(10L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);
            when(messageReportRepository.findById(10L)).thenReturn(Optional.of(reportEntity));

            RoomMemberEntity owner = member(OWNER_ID, "owner", RoomRole.OWNER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, OWNER_ID))
                    .thenReturn(Optional.of(owner));

            LocalDateTime before = LocalDateTime.now();
            messageReportService.updateStatus(10L, OWNER_ID, ReportStatus.RESOLVED);

            ArgumentCaptor<MessageReportEntity> captor = ArgumentCaptor.forClass(MessageReportEntity.class);
            verify(messageReportRepository).save(captor.capture());
            MessageReportEntity updated = captor.getValue();
            assertEquals(ReportStatus.RESOLVED, updated.getStatus());
            assertEquals(OWNER_ID, updated.getResolvedBy());
            assertNotNull(updated.getResolvedAt());
            assertFalse(updated.getResolvedAt().isBefore(before));
        }

        @Test
        void updateStatus_toDismissed_setsResolvedByAndResolvedAt() {
            MessageReportEntity reportEntity = report(11L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.HARASSMENT);
            when(messageReportRepository.findById(11L)).thenReturn(Optional.of(reportEntity));

            RoomMemberEntity mod = member(MOD_ID, "mod", RoomRole.MODERATOR);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MOD_ID))
                    .thenReturn(Optional.of(mod));

            messageReportService.updateStatus(11L, MOD_ID, ReportStatus.DISMISSED);

            ArgumentCaptor<MessageReportEntity> captor = ArgumentCaptor.forClass(MessageReportEntity.class);
            verify(messageReportRepository).save(captor.capture());
            MessageReportEntity updated = captor.getValue();
            assertEquals(ReportStatus.DISMISSED, updated.getStatus());
            assertEquals(MOD_ID, updated.getResolvedBy());
            assertNotNull(updated.getResolvedAt());
        }

        @Test
        void updateStatus_toPending_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> messageReportService.updateStatus(10L, OWNER_ID, ReportStatus.PENDING));

            verify(messageReportRepository, never()).findById(anyLong());
            verify(messageReportRepository, never()).save(any());
        }

        @Test
        void updateStatus_reportNotFound_throwsIllegalArgument() {
            when(messageReportRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> messageReportService.updateStatus(999L, OWNER_ID, ReportStatus.RESOLVED));
        }

        @Test
        void updateStatus_memberCannotResolve_throwsPermissionDenied() {
            MessageReportEntity reportEntity = report(10L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);
            when(messageReportRepository.findById(10L)).thenReturn(Optional.of(reportEntity));

            RoomMemberEntity normalMember = member(MEMBER_ID, "member", RoomRole.MEMBER);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, MEMBER_ID))
                    .thenReturn(Optional.of(normalMember));

            assertThrows(PermissionDeniedException.class,
                    () -> messageReportService.updateStatus(10L, MEMBER_ID, ReportStatus.RESOLVED));

            verify(messageReportRepository, never()).save(any());
        }

        @Test
        void updateStatus_nonMember_throwsPermissionDenied() {
            MessageReportEntity reportEntity = report(10L, MESSAGE_ID, ROOM_ID, REPORTER_USER_ID, ReportReason.SPAM);
            when(messageReportRepository.findById(10L)).thenReturn(Optional.of(reportEntity));

            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, "stranger-id"))
                    .thenReturn(Optional.empty());

            assertThrows(PermissionDeniedException.class,
                    () -> messageReportService.updateStatus(10L, "stranger-id", ReportStatus.RESOLVED));
        }
    }
}
