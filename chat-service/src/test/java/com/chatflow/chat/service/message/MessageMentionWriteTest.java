package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.MessageMentionEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.chat.entity.RoomRole;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import com.chatflow.chat.service.notification.FcmNotificationService;
import com.chatflow.chat.service.outbox.ChatPersistenceService;
import com.chatflow.chat.service.room.ChatRoomService;
import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that mention rows are recorded at send time, member-scoped,
 * and that FCM mention pushes only target resolved room members.
 */
@ExtendWith(MockitoExtension.class)
class MessageMentionWriteTest {

    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private FcmNotificationService fcmNotificationService;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private RoomMemberRepository roomMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private MessageSenderService service;

    private static final String ROOM_ID = "room-1";
    private static final String SENDER_USER_ID = "sender-id";
    private static final String SENDER_USERNAME = "alice";

    @BeforeEach
    void setUp() {
        service = new MessageSenderService(
                chatPersistenceService, chatRoomService, fcmNotificationService,
                chatMessageRepository, roomMemberRepository, messagingTemplate,
                new SimpleMeterRegistry());
    }

    private ChatMessage chatMessage(String content) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(SENDER_USER_ID);
        msg.setUsername(SENDER_USERNAME);
        msg.setType(MessageType.CHAT);
        msg.setContent(content);
        return msg;
    }

    private ChatMessage fileMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(SENDER_USER_ID);
        msg.setUsername(SENDER_USERNAME);
        msg.setType(MessageType.FILE);
        msg.setContent("file.pdf");
        msg.setFileName("file.pdf");
        return msg;
    }

    private RoomMemberEntity member(String userId, String username) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID)
                .userId(userId)
                .username(username)
                .role(RoomRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // ── CHAT with valid mention ────────────────────────────────

    @Nested
    class ChatWithValidMention {

        @Test
        @SuppressWarnings("unchecked")
        void records_mention_row_for_member_bob() {
            // given: bob IS a room member
            RoomMemberEntity bob = member("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            // when
            ChatMessage msg = chatMessage("hey @bob");
            service.send(msg, member(SENDER_USER_ID, SENDER_USERNAME));

            // then: persist called with exactly one mention
            ArgumentCaptor<List<MessageMentionEntity>> mentionsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), eq(KafkaTopics.CHAT_MESSAGES),
                    eq("MESSAGE_SENT"), any(), mentionsCaptor.capture());

            List<MessageMentionEntity> mentions = mentionsCaptor.getValue();
            assertThat(mentions).hasSize(1);
            MessageMentionEntity mention = mentions.get(0);
            assertThat(mention.getMessageId()).isEqualTo(msg.getMessageId());
            assertThat(mention.getRoomId()).isEqualTo(ROOM_ID);
            assertThat(mention.getMentionedUserId()).isEqualTo("bob-id");
            assertThat(mention.getMentionedUsername()).isEqualTo("bob");
            assertThat(mention.getFromUsername()).isEqualTo(SENDER_USERNAME);
            assertThat(mention.isRead()).isFalse();
        }
    }

    // ── Non-member mention ─────────────────────────────────────

    @Nested
    class NonMemberMention {

        @Test
        @SuppressWarnings("unchecked")
        void stranger_produces_empty_mention_list() {
            // given: finder returns empty (stranger not a member)
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of());
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            // when
            service.send(chatMessage("hey @stranger"), member(SENDER_USER_ID, SENDER_USERNAME));

            // then
            ArgumentCaptor<List<MessageMentionEntity>> mentionsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(),
                    mentionsCaptor.capture());
            assertThat(mentionsCaptor.getValue()).isEmpty();
        }
    }

    // ── Self-mention filtered out ──────────────────────────────

    @Nested
    class SelfMention {

        @Test
        @SuppressWarnings("unchecked")
        void sender_self_mention_filtered_out() {
            // given: finder returns the sender's own member row
            RoomMemberEntity senderMember = member(SENDER_USER_ID, SENDER_USERNAME);
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(senderMember));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            // when
            service.send(chatMessage("@" + SENDER_USERNAME), member(SENDER_USER_ID, SENDER_USERNAME));

            // then: empty mentions
            ArgumentCaptor<List<MessageMentionEntity>> mentionsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(),
                    mentionsCaptor.capture());
            assertThat(mentionsCaptor.getValue()).isEmpty();
        }
    }

    // ── FILE type → no mention processing ──────────────────────

    @Nested
    class FileType {

        @Test
        void file_message_skips_mention_lookup() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage(), null);

            // finder never called
            verify(roomMemberRepository, never()).findByRoomIdAndUsernameIn(anyString(), anyCollection());
            // persist called with empty mentions
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), eq(KafkaTopics.CHAT_MESSAGES),
                    eq("MESSAGE_SENT"), any(), eq(List.of()));
        }
    }

    // ── FCM mention pushes use resolved members only ───────────

    @Nested
    class FcmMentionPush {

        @Test
        void fcm_fired_for_resolved_member_not_for_stranger() {
            // given: @bob is a member, @stranger is not
            RoomMemberEntity bob = member("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            // when
            service.send(chatMessage("hey @bob and @stranger"),
                    member(SENDER_USER_ID, SENDER_USERNAME));

            // then: FCM for room + FCM for bob (mention), but NOT for stranger
            // room-level notification
            verify(fcmNotificationService).sendMessageNotification(
                    eq(ROOM_ID), eq(SENDER_USERNAME), anyString());
            // mention notification for bob
            verify(fcmNotificationService).sendMessageNotification(
                    eq("mention-bob"), eq(SENDER_USERNAME), anyString());
            // NO mention notification for stranger
            verify(fcmNotificationService, never()).sendMessageNotification(
                    eq("mention-stranger"), anyString(), anyString());
            // total: exactly 2 calls
            verify(fcmNotificationService, times(2)).sendMessageNotification(
                    anyString(), anyString(), anyString());
        }

        @Test
        void fcm_mention_not_fired_for_self_mention() {
            RoomMemberEntity senderMember = member(SENDER_USER_ID, SENDER_USERNAME);
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(senderMember));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(chatMessage("@" + SENDER_USERNAME),
                    member(SENDER_USER_ID, SENDER_USERNAME));

            // Only room-level FCM, no mention FCM
            verify(fcmNotificationService, times(1)).sendMessageNotification(
                    anyString(), anyString(), anyString());
            verify(fcmNotificationService, never()).sendMessageNotification(
                    startsWith("mention-"), anyString(), anyString());
        }
    }

    // ── Single-arg overload also records mentions ──────────────

    @Nested
    class SingleArgOverload {

        @Test
        @SuppressWarnings("unchecked")
        void single_arg_send_also_resolves_and_records_mentions() {
            // given: sender is a member (single-arg fetches it)
            RoomMemberEntity senderMember = member(SENDER_USER_ID, SENDER_USERNAME);
            when(roomMemberRepository.findByRoomIdAndUserId(ROOM_ID, SENDER_USER_ID))
                    .thenReturn(Optional.of(senderMember));

            RoomMemberEntity bob = member("bob-id", "bob");
            when(roomMemberRepository.findByRoomIdAndUsernameIn(eq(ROOM_ID), anyCollection()))
                    .thenReturn(List.of(bob));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            // when: single-arg send
            service.send(chatMessage("hey @bob"));

            // then: mentions recorded
            ArgumentCaptor<List<MessageMentionEntity>> mentionsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(),
                    mentionsCaptor.capture());
            assertThat(mentionsCaptor.getValue()).hasSize(1);
            assertThat(mentionsCaptor.getValue().get(0).getMentionedUserId()).isEqualTo("bob-id");
        }
    }
}
