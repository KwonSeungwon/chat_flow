package com.chatflow.chat.service.presence;

import com.chatflow.chat.event.RoomMemberRemovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomSubscriptionRevokerTest {

    @Mock SimpUserRegistry userRegistry;
    @Mock MessageChannel brokerChannel;
    @Mock ParticipantRegistryService participantRegistry;

    private RoomSubscriptionRevoker revoker;

    @BeforeEach
    void setUp() {
        revoker = new RoomSubscriptionRevoker(userRegistry, brokerChannel, participantRegistry);
    }

    private static SimpSubscription sub(String id, String destination) {
        SimpSubscription s = mock(SimpSubscription.class);
        when(s.getId()).thenReturn(id);
        when(s.getDestination()).thenReturn(destination);
        return s;
    }

    @Test
    void kicked_unsubscribesOnlyThatRoomsTopics_andRemovesParticipant() {
        SimpSubscription main = sub("sub-0", "/topic/chat/room-1");
        SimpSubscription typing = sub("sub-1", "/topic/chat/room-1/typing");
        SimpSubscription other = mock(SimpSubscription.class);
        when(other.getDestination()).thenReturn("/topic/chat/room-10");
        SimpSubscription queue = mock(SimpSubscription.class);
        when(queue.getDestination()).thenReturn("/user/queue/kicked");
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn("sess-A");
        when(session.getSubscriptions()).thenReturn(Set.of(main, typing, other, queue));
        SimpUser user = mock(SimpUser.class);
        when(user.getSessions()).thenReturn(Set.of(session));
        when(userRegistry.getUser("u1")).thenReturn(user);

        revoker.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-1", "u1", RoomMemberRemovedEvent.Reason.KICKED));

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(brokerChannel, times(2)).send(captor.capture());
        List<String> ids = captor.getAllValues().stream().map(m -> {
            SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.wrap(m);
            assertThat(h.getMessageType()).isEqualTo(SimpMessageType.UNSUBSCRIBE);
            assertThat(h.getSessionId()).isEqualTo("sess-A");
            return h.getSubscriptionId();
        }).toList();
        assertThat(ids).containsExactlyInAnyOrder("sub-0", "sub-1");
        verify(participantRegistry).removeUser("room-1", "u1");
    }

    @Test
    void banned_userNotConnected_stillRemovesParticipant() {
        when(userRegistry.getUser("u1")).thenReturn(null);

        revoker.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-1", "u1", RoomMemberRemovedEvent.Reason.BANNED));

        verify(brokerChannel, never()).send(any());
        verify(participantRegistry).removeUser("room-1", "u1");
    }

    @Test
    void voluntaryLeave_isLeftToTheClient() {
        revoker.onRoomMemberRemoved(new RoomMemberRemovedEvent(
                "room-1", "u1", RoomMemberRemovedEvent.Reason.LEFT));

        verifyNoInteractions(userRegistry, brokerChannel, participantRegistry);
    }

    @Test
    void unsubscribeMessage_isHonouredByTheSimpleBroker() {
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel broker = new ExecutorSubscribableChannel();
        List<Message<?>> delivered = new ArrayList<>();
        outbound.subscribe(delivered::add);
        SimpleBrokerMessageHandler handler =
                new SimpleBrokerMessageHandler(inbound, outbound, broker, List.of("/topic", "/queue"));
        handler.start();

        SimpMessageHeaderAccessor subscribe = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        subscribe.setSessionId("sess-A");
        subscribe.setSubscriptionId("sub-0");
        subscribe.setDestination("/topic/chat/room-1");
        inbound.send(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));

        SimpSubscription main = sub("sub-0", "/topic/chat/room-1");
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn("sess-A");
        when(session.getSubscriptions()).thenReturn(Set.of(main));
        SimpUser user = mock(SimpUser.class);
        when(user.getSessions()).thenReturn(Set.of(session));
        when(userRegistry.getUser("u1")).thenReturn(user);
        new RoomSubscriptionRevoker(userRegistry, broker, participantRegistry)
                .onRoomMemberRemoved(new RoomMemberRemovedEvent(
                        "room-1", "u1", RoomMemberRemovedEvent.Reason.KICKED));

        SimpMessageHeaderAccessor publish = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        publish.setDestination("/topic/chat/room-1");
        broker.send(MessageBuilder.createMessage("hi".getBytes(), publish.getMessageHeaders()));

        assertThat(delivered).isEmpty();
        handler.stop();
    }

    @Test
    void runsAfterCommit() throws Exception {
        TransactionalEventListener ann = RoomSubscriptionRevoker.class
                .getMethod("onRoomMemberRemoved", RoomMemberRemovedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(ann).isNotNull();
        assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
