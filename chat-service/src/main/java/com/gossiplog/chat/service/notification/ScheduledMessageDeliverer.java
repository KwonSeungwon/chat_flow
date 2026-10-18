package com.gossiplog.chat.service.notification;

import com.gossiplog.chat.entity.RoomMemberEntity;
import com.gossiplog.chat.entity.ScheduledMessageEntity;
import com.gossiplog.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.gossiplog.chat.repository.ScheduledMessageRepository;
import com.gossiplog.chat.service.message.MessageSenderService;
import com.gossiplog.chat.service.room.RoomBanService;
import com.gossiplog.chat.service.room.RoomMembershipChecker;
import com.gossiplog.common.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Delivers one scheduled message per transaction.
 *
 * <p>A separate bean from {@link ScheduledMessageService} so the
 * {@code REQUIRES_NEW} boundary goes through the Spring proxy: each row commits or
 * rolls back on its own, and a failure in one row (including a rollback-only mark set
 * deep inside {@code ChatPersistenceService}) can never undo or block the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledMessageDeliverer {

    public enum Outcome {
        /** Delivered and marked SENT. */
        SENT,
        /** Author no longer allowed to post — marked FAILED with the reason. */
        REJECTED,
        /** Row was not PENDING any more (other replica, or canceled). */
        SKIPPED
    }

    private final ScheduledMessageRepository repository;
    private final MessageSenderService messageSenderService;
    private final RoomMembershipChecker membershipChecker;
    private final RoomBanService roomBanService;

    /**
     * Claims the row (conditional PENDING → PROCESSING), re-checks that the author may
     * still post, then sends. Claim, message persistence and the status change share
     * this one transaction: if anything throws, everything rolls back and the row is
     * PENDING again — the caller then marks it FAILED via {@link #markFailed}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome deliver(Long id) {
        if (repository.claim(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
            log.info("Scheduled message id={} already claimed or no longer pending — skipped", id);
            return Outcome.SKIPPED;
        }
        Optional<ScheduledMessageEntity> found = repository.findById(id);
        if (found.isEmpty()) return Outcome.SKIPPED;
        ScheduledMessageEntity row = found.get();

        Optional<RoomMemberEntity> author = membershipChecker
                .findMember(row.getChatRoomId(), row.getUserId())
                .map(RoomMembershipChecker.MembershipResult::entity);
        String rejection = rejectionReason(row, author.orElse(null));
        if (rejection != null) {
            log.info("Scheduled message id={} not delivered: {}", id, rejection);
            row.setStatus(ScheduledMessageStatus.FAILED);
            row.setErrorMessage(rejection);
            repository.save(row);
            return Outcome.REJECTED;
        }

        ChatMessage msg = new ChatMessage();
        // send() owns messageId + timestamp — it assigns both on entry.
        msg.setChatRoomId(row.getChatRoomId());
        msg.setUserId(row.getUserId());
        msg.setUsername(row.getUsername());
        msg.setContent(row.getContent());
        msg.setType(ChatMessage.MessageType.CHAT);

        messageSenderService.send(msg, author.get());
        if (msg.getMessageId() == null) {
            // send() returned through one of its early-exit gates without persisting.
            throw new IllegalStateException("message was not accepted by the sender");
        }

        row.setStatus(ScheduledMessageStatus.SENT);
        row.setSentMessageId(msg.getMessageId());
        repository.save(row);
        return Outcome.SENT;
    }

    /** Marks a row FAILED after its delivery transaction rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String reason) {
        repository.markFailed(id, reason, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Membership, ban and mute are re-checked at delivery time: the author may have
     * been kicked, banned or muted after scheduling. {@code mutedUntil} is written in
     * server-local time by MemberManagementService, so it is compared the same way.
     */
    private String rejectionReason(ScheduledMessageEntity row, RoomMemberEntity author) {
        if (author == null) {
            return "author is no longer a member of the room";
        }
        if (roomBanService.isBanned(row.getChatRoomId(), row.getUserId())) {
            return "author is banned from the room";
        }
        if (author.getMutedUntil() != null && author.getMutedUntil().isAfter(LocalDateTime.now())) {
            return "author is muted until " + author.getMutedUntil();
        }
        return null;
    }
}
