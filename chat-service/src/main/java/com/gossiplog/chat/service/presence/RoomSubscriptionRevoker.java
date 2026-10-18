package com.gossiplog.chat.service.presence;

import com.gossiplog.chat.event.RoomMemberRemovedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kick / ban: drops the removed user's live broker subscriptions to the room's
 * topics and its presence entries, so a client that ignores {@code /queue/kicked}
 * stops receiving the room. Voluntary leave is left to the client.
 */
@Slf4j
@Component
public class RoomSubscriptionRevoker {

    private final SimpUserRegistry userRegistry;
    private final MessageChannel brokerChannel;
    private final ParticipantRegistryService participantRegistry;

    public RoomSubscriptionRevoker(SimpUserRegistry userRegistry,
                                   @Qualifier("brokerChannel") MessageChannel brokerChannel,
                                   ParticipantRegistryService participantRegistry) {
        this.userRegistry = userRegistry;
        this.brokerChannel = brokerChannel;
        this.participantRegistry = participantRegistry;
    }

    // REQUIRES_NEW: after commit the removing transaction is finished, so the
    // participant_count write needs its own transaction to be flushed.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomMemberRemoved(RoomMemberRemovedEvent event) {
        if (event.reason() == RoomMemberRemovedEvent.Reason.LEFT) return;
        String roomTopic = "/topic/chat/" + event.roomId();
        SimpUser user = userRegistry.getUser(event.userId());
        if (user != null) {
            for (SimpSession session : user.getSessions()) {
                for (SimpSubscription sub : session.getSubscriptions()) {
                    String dest = sub.getDestination();
                    if (dest.equals(roomTopic) || dest.startsWith(roomTopic + "/")) {
                        unsubscribe(session.getId(), sub.getId(), dest);
                    }
                }
            }
        }
        participantRegistry.removeUser(event.roomId(), event.userId());
        log.info("Revoked room subscriptions: room={} user={} reason={}",
                event.roomId(), event.userId(), event.reason());
    }

    private void unsubscribe(String sessionId, String subscriptionId, String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination(destination);
        headers.setLeaveMutable(true);
        brokerChannel.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }
}
