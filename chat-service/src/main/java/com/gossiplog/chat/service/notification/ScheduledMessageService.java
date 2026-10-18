package com.gossiplog.chat.service.notification;

import com.gossiplog.chat.exception.QuotaExceededException;
import com.gossiplog.chat.service.RoomPermissionService;

import com.gossiplog.chat.entity.RoomRole;
import com.gossiplog.chat.entity.ScheduledMessageEntity;
import com.gossiplog.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.gossiplog.chat.repository.ScheduledMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledMessageService {

    private final ScheduledMessageRepository repository;
    private final ScheduledMessageDeliverer deliverer;
    private final RoomPermissionService roomPermissionService;

    /**
     * Per-user cap on PENDING scheduled messages. Defends against a user
     * scheduling millions of rows. Cancel/delivery prunes the count.
     */
    private static final int MAX_PENDING_PER_USER = 100;

    /**
     * Hard cap on scheduled message content length. The TEXT column allows
     * megabytes; this keeps each row bounded in memory and on the wire.
     */
    private static final int MAX_CONTENT_LENGTH = 4000;

    /**
     * @param scheduledAt the absolute delivery instant; stored as UTC wall-clock time
     *                    so the poller can compare it with a UTC clock regardless of
     *                    the JVM's or the client's time zone.
     */
    @Transactional
    public ScheduledMessageEntity schedule(
            String chatRoomId, String userId, String username,
            String content, Instant scheduledAt) {
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "content too long (max " + MAX_CONTENT_LENGTH + " chars)");
        }
        if (scheduledAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("scheduledAt must be in the future");
        }
        // Membership check — non-members must NOT be able to schedule into
        // a room they don't belong to (long-fuse abuse vector).
        roomPermissionService.requireRole(chatRoomId, userId,
                RoomRole.OWNER, RoomRole.MODERATOR, RoomRole.MEMBER);
        long pending = repository.countByUserIdAndStatus(
                userId, ScheduledMessageStatus.PENDING);
        if (pending >= MAX_PENDING_PER_USER) {
            throw new QuotaExceededException(
                    "scheduled message limit reached (max " + MAX_PENDING_PER_USER + ")");
        }
        ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                .chatRoomId(chatRoomId)
                .userId(userId)
                .username(username)
                .content(content)
                .scheduledAt(LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC))
                .status(ScheduledMessageStatus.PENDING)
                .build();
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ScheduledMessageEntity> listMine(String userId) {
        return repository.findByUserIdAndStatusOrderByScheduledAtDesc(
                userId, ScheduledMessageStatus.PENDING);
    }

    @Transactional
    public ScheduledMessageEntity cancel(Long id, String userId) {
        ScheduledMessageEntity entity = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled message not found or not owned: id=" + id));
        if (entity.getStatus() != ScheduledMessageStatus.PENDING) {
            log.info("Cancel no-op on {} (status={})", id, entity.getStatus());
            return entity;
        }
        entity.setStatus(ScheduledMessageStatus.CANCELED);
        return repository.save(entity);
    }

    /**
     * Polled every 30s. Picks PENDING rows whose UTC scheduledAt has arrived and hands
     * each one to {@link ScheduledMessageDeliverer#deliver} — its own REQUIRES_NEW
     * transaction. Deliberately NOT transactional itself: one row's rollback (a version
     * conflict, a rollback-only mark from the persistence layer) must not undo the rows
     * already delivered or leave the whole batch PENDING to be re-sent every 30s.
     *
     * <p>Multi-replica safety: the deliverer claims each row with a conditional
     * {@code UPDATE ... WHERE status = 'PENDING'}; a replica that loses the race gets 0
     * rows and skips it.
     */
    @Scheduled(fixedDelay = 30_000L)
    public void deliverDue() {
        List<ScheduledMessageEntity> due = repository.findDueForSending(LocalDateTime.now(ZoneOffset.UTC));
        if (due.isEmpty()) return;
        log.info("Delivering {} scheduled message(s)", due.size());

        for (ScheduledMessageEntity row : due) {
            Long id = row.getId();
            try {
                deliverer.deliver(id);
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("Scheduled delivery failed for id={}: {}", id, reason, e);
                try {
                    deliverer.markFailed(id, reason);
                } catch (Exception markError) {
                    log.error("Could not mark scheduled message id={} FAILED: {}", id, markError.getMessage());
                }
            }
        }
    }
}
