package com.gossiplog.chat.service.notification;

import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.entity.ScheduledMessageEntity;
import com.gossiplog.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.gossiplog.chat.repository.ScheduledMessageRepository;
import com.gossiplog.chat.service.message.MessageSenderService;
import com.gossiplog.chat.service.room.RoomBanService;
import com.gossiplog.chat.service.room.RoomMembershipChecker;
import com.gossiplog.chat.service.room.RoomMembershipChecker.MembershipResult;
import com.gossiplog.common.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledMessageDelivererTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";

    @Mock private ScheduledMessageRepository repository;
    @Mock private MessageSenderService messageSenderService;
    @Mock private RoomMembershipChecker membershipChecker;
    @Mock private RoomBanService roomBanService;

    private ScheduledMessageDeliverer deliverer;

    @BeforeEach
    void setUp() {
        deliverer = new ScheduledMessageDeliverer(
                repository, messageSenderService, membershipChecker, roomBanService);
    }

    private ScheduledMessageEntity row(Long id) {
        return ScheduledMessageEntity.builder()
                .id(id)
                .chatRoomId(ROOM_ID)
                .userId(USER_ID)
                .username("alice")
                .content("hello future")
                .scheduledAt(LocalDateTime.now().minusMinutes(1))
                .status(ScheduledMessageStatus.PROCESSING)
                .build();
    }

    private RoomMemberEntity member(LocalDateTime mutedUntil) {
        return RoomMemberEntity.builder()
                .roomId(ROOM_ID).userId(USER_ID).username("alice")
                .role(RoomRole.MEMBER).joinedAt(LocalDateTime.now())
                .mutedUntil(mutedUntil)
                .build();
    }

    private void claimed(ScheduledMessageEntity row) {
        when(repository.claim(eq(row.getId()), any())).thenReturn(1);
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
    }

    @Test
    void deliver_runsInItsOwnTransaction() throws Exception {
        Transactional deliver = ScheduledMessageDeliverer.class
                .getMethod("deliver", Long.class).getAnnotation(Transactional.class);
        Transactional markFailed = ScheduledMessageDeliverer.class
                .getMethod("markFailed", Long.class, String.class).getAnnotation(Transactional.class);
        assertThat(deliver.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(markFailed.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void deliverDue_rowClaimedByOtherReplica_skipped() {
        when(repository.claim(eq(5L), any())).thenReturn(0);

        ScheduledMessageDeliverer.Outcome outcome = deliverer.deliver(5L);

        assertThat(outcome).isEqualTo(ScheduledMessageDeliverer.Outcome.SKIPPED);
        verify(repository, never()).findById(anyLong());
        verifyNoInteractions(messageSenderService);
    }

    @Test
    void deliverDue_authorKicked_markedFailed() {
        ScheduledMessageEntity row = row(7L);
        claimed(row);
        when(membershipChecker.findMember(ROOM_ID, USER_ID)).thenReturn(Optional.empty());

        ScheduledMessageDeliverer.Outcome outcome = deliverer.deliver(7L);

        assertThat(outcome).isEqualTo(ScheduledMessageDeliverer.Outcome.REJECTED);
        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(row.getErrorMessage()).contains("no longer a member");
        verify(repository).save(row);
        verifyNoInteractions(messageSenderService);
    }

    @Test
    void deliver_authorBanned_markedFailed() {
        ScheduledMessageEntity row = row(8L);
        claimed(row);
        when(membershipChecker.findMember(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(new MembershipResult(member(null))));
        when(roomBanService.isBanned(ROOM_ID, USER_ID)).thenReturn(true);

        deliverer.deliver(8L);

        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(row.getErrorMessage()).contains("banned");
        verifyNoInteractions(messageSenderService);
    }

    @Test
    void deliver_authorMuted_markedFailed() {
        ScheduledMessageEntity row = row(9L);
        claimed(row);
        when(membershipChecker.findMember(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(new MembershipResult(member(LocalDateTime.now().plusMinutes(10)))));

        deliverer.deliver(9L);

        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.FAILED);
        assertThat(row.getErrorMessage()).contains("muted");
        verifyNoInteractions(messageSenderService);
    }

    @Test
    void deliver_eligibleAuthor_sendsAndMarksSent() {
        ScheduledMessageEntity row = row(11L);
        claimed(row);
        RoomMemberEntity author = member(LocalDateTime.now().minusMinutes(1)); // mute expired
        when(membershipChecker.findMember(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(new MembershipResult(author)));
        // Mirror MessageSenderService.send()'s contract: it assigns messageId on entry.
        doAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setMessageId("fake-server-uuid");
            return null;
        }).when(messageSenderService).send(any(ChatMessage.class), eq(author));

        ScheduledMessageDeliverer.Outcome outcome = deliverer.deliver(11L);

        ArgumentCaptor<ChatMessage> sent = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageSenderService).send(sent.capture(), eq(author));
        assertThat(sent.getValue().getContent()).isEqualTo("hello future");
        assertThat(sent.getValue().getChatRoomId()).isEqualTo(ROOM_ID);
        assertThat(sent.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(sent.getValue().getUsername()).isEqualTo("alice");
        assertThat(sent.getValue().getType()).isEqualTo(ChatMessage.MessageType.CHAT);

        assertThat(outcome).isEqualTo(ScheduledMessageDeliverer.Outcome.SENT);
        assertThat(row.getStatus()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(row.getSentMessageId()).isEqualTo("fake-server-uuid");
    }

    @Test
    void deliver_senderSilentlyDropsMessage_throwsSoRowIsMarkedFailed() {
        ScheduledMessageEntity row = row(12L);
        claimed(row);
        when(membershipChecker.findMember(ROOM_ID, USER_ID))
                .thenReturn(Optional.of(new MembershipResult(member(null))));
        // send() returns without assigning a messageId (one of its early-return gates).

        assertThatThrownBy(() -> deliverer.deliver(12L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row.getStatus()).isNotEqualTo(ScheduledMessageStatus.SENT);
    }

    @Test
    void markFailed_isConditionalUpdateWithReason() {
        deliverer.markFailed(3L, "kafka down");
        verify(repository).markFailed(eq(3L), eq("kafka down"), any());
    }
}
