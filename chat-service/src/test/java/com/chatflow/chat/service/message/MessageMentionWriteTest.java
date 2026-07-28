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

    private ChatMessage fileMessage(String caption) {
        return fileMessage(caption, "file.pdf");
    }

    private ChatMessage fileMessage(String caption, String fileName) {
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(ROOM_ID);
        msg.setUserId(SENDER_USER_ID);
        msg.setUsername(SENDER_USERNAME);
        msg.setType(MessageType.FILE);
        msg.setContent(caption);
        msg.setFileName(fileName);
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
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(
                    List.of(member(SENDER_USER_ID, SENDER_USERNAME), member("bob-id", "bob")));
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

    // ── Names the old charset regex could not express ──────────

    /**
     * Registration never validated usernames (gateway {@code AuthService.register}
     * only checks the password), so hyphenated, spaced and over-long names exist.
     * The old {@code @([A-Za-z0-9_.가-힣]{1,30})} regex truncated them, the
     * truncated token matched no member, and the mention silently reached nobody.
     */
    @Nested
    class UnusualUsernames {

        @SuppressWarnings("unchecked")
        private List<MessageMentionEntity> mentionsFor(String content, String mentionedName) {
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(
                    List.of(member(SENDER_USER_ID, SENDER_USERNAME), member("target-id", mentionedName)));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(chatMessage(content), member(SENDER_USER_ID, SENDER_USERNAME));

            ArgumentCaptor<List<MessageMentionEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), captor.capture());
            return captor.getValue();
        }

        @Test
        void uuid_fallback_username_is_mentionable() {
            // A blank username falls back to the userId, so 36-char hyphenated
            // names are in prod right now.
            String uuid = "bd515969-a57e-4cc1-b72d-6b2508b483d0";
            assertThat(mentionsFor("@" + uuid + " 확인 부탁", uuid))
                    .extracting(MessageMentionEntity::getMentionedUsername)
                    .containsExactly(uuid);
        }

        @Test
        void username_with_a_space_is_mentionable() {
            assertThat(mentionsFor("hey @Phill Park, ping", "Phill Park"))
                    .extracting(MessageMentionEntity::getMentionedUsername)
                    .containsExactly("Phill Park");
        }

        @Test
        void username_longer_than_thirty_characters_is_mentionable() {
            String long_ = "a".repeat(45);
            assertThat(mentionsFor("@" + long_ + " hi", long_))
                    .extracting(MessageMentionEntity::getMentionedUsername)
                    .containsExactly(long_);
        }
    }

    // ── No '@' → no member lookup at all ───────────────────────

    @Nested
    class NoMentionSyntax {

        @Test
        void plain_message_never_loads_the_member_list() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(chatMessage("점심 뭐 먹지"), member(SENDER_USER_ID, SENDER_USERNAME));

            verify(roomMemberRepository, never()).findByRoomId(anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), eq(List.of()));
        }
    }

    // ── Non-member mention ─────────────────────────────────────

    @Nested
    class NonMemberMention {

        @Test
        @SuppressWarnings("unchecked")
        void stranger_produces_empty_mention_list() {
            // given: nobody in the room is called "stranger"
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(member(SENDER_USER_ID, SENDER_USERNAME)));
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
            // given: the sender is the only member, and mentions themselves
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(member(SENDER_USER_ID, SENDER_USERNAME)));
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

    // ── FILE captions mention people too ───────────────────────

    /**
     * A FILE message carries a user-typed caption (frontend
     * {@code message_send_helper.dart} sends it as the content). Skipping mention
     * resolution for FILE meant "@bob 차트 확인" attached to a file produced no
     * mention row — and a room set to {@code NotificationPolicy.mentionsOnly}
     * suppresses the unread badge entirely unless the mention is there.
     */
    @Nested
    class FileType {

        @Test
        void caption_without_an_at_sign_skips_the_member_lookup() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage("file.pdf"), null);

            verify(roomMemberRepository, never()).findByRoomId(anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), eq(KafkaTopics.CHAT_MESSAGES),
                    eq("MESSAGE_SENT"), any(), eq(List.of()));
        }

        @Test
        void null_caption_is_safe() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage(null), null);

            verify(roomMemberRepository, never()).findByRoomId(anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), eq(List.of()));
        }

        @Test
        @SuppressWarnings("unchecked")
        void caption_mention_records_a_row_and_pushes_fcm() {
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(
                    List.of(member(SENDER_USER_ID, SENDER_USERNAME), member("bob-id", "bob")));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage("@bob 차트 확인 부탁"), null);

            ArgumentCaptor<List<MessageMentionEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), captor.capture());
            assertThat(captor.getValue())
                    .extracting(MessageMentionEntity::getMentionedUserId)
                    .containsExactly("bob-id");

            // 파일 알림 + bob 멘션 알림, 정확히 둘
            verify(fcmNotificationService).sendMessageNotification(
                    eq("mention-bob"), eq(SENDER_USERNAME), anyString());
            verify(fcmNotificationService, times(2)).sendMessageNotification(
                    anyString(), anyString(), anyString());
        }

        /**
         * 캡션을 비우고 올리면 프론트가 본문을 "[파일] &lt;파일명&gt;"으로 채운다.
         * 파일명 안의 @는 사용자가 이 방에서 부른 이름이 아니다 — 파일 이름일 뿐이라
         * 그걸로 푸시를 쏘면 아무도 부르지 않은 사람이 호출당한다.
         */
        @Test
        void the_auto_generated_caption_never_mentions_anyone() {
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage("[파일] @bob-review.pdf", "@bob-review.pdf"), null);

            verify(roomMemberRepository, never()).findByRoomId(anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), eq(List.of()));
            verify(fcmNotificationService, never()).sendMessageNotification(
                    startsWith("mention-"), anyString(), anyString());
        }

        /**
         * 같은 파일이라도 사용자가 캡션에 한 글자라도 보탰으면 그건 사용자가 친 문장이다.
         * 접두사만 보고 잘라내면 이 경우를 조용히 삼킨다.
         */
        @Test
        @SuppressWarnings("unchecked")
        void a_caption_the_user_extended_still_mentions() {
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(
                    List.of(member(SENDER_USER_ID, SENDER_USERNAME), member("bob-id", "bob")));
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(fileMessage("[파일] @bob 이거 봐줘", "chart.pdf"), null);

            ArgumentCaptor<List<MessageMentionEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), captor.capture());
            assertThat(captor.getValue())
                    .extracting(MessageMentionEntity::getMentionedUserId)
                    .containsExactly("bob-id");
        }
    }

    // ── Server-generated text never mentions ───────────────────

    @Nested
    class SystemText {

        @Test
        void system_message_does_not_resolve_mentions() {
            ChatMessage msg = new ChatMessage();
            msg.setChatRoomId(ROOM_ID);
            msg.setUserId(SENDER_USER_ID);
            msg.setUsername(SENDER_USERNAME);
            msg.setType(MessageType.SYSTEM);
            msg.setContent("@bob 님이 입장했습니다");
            when(chatRoomService.getRoom(ROOM_ID)).thenReturn(Optional.empty());

            service.send(msg, null);

            verify(roomMemberRepository, never()).findByRoomId(anyString());
            verify(chatPersistenceService).persistMessageAndPublish(
                    any(ChatMessage.class), anyString(), anyString(), any(), eq(List.of()));
            verifyNoInteractions(fcmNotificationService);
        }
    }

    // ── FCM mention pushes use resolved members only ───────────

    @Nested
    class FcmMentionPush {

        @Test
        void fcm_fired_for_resolved_member_not_for_stranger() {
            // given: @bob is a member, @stranger is not
            when(roomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(
                    List.of(member(SENDER_USER_ID, SENDER_USERNAME), member("bob-id", "bob")));
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
            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(member(SENDER_USER_ID, SENDER_USERNAME)));
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

            when(roomMemberRepository.findByRoomId(ROOM_ID))
                    .thenReturn(List.of(senderMember, member("bob-id", "bob")));
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
