package com.chatflow.chat.service;

import com.chatflow.chat.entity.ScheduledMessageEntity;
import com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus;
import com.chatflow.chat.repository.ScheduledMessageRepository;
import com.chatflow.common.dto.ChatMessage;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledMessageService {

    private final ScheduledMessageRepository repository;
    private final MessageSenderService messageSenderService;

    @Transactional
    public ScheduledMessageEntity schedule(
            String chatRoomId, String userId, String username,
            String content, LocalDateTime scheduledAt) {
        if (scheduledAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("scheduledAt must be in the future");
        }
        ScheduledMessageEntity entity = ScheduledMessageEntity.builder()
                .chatRoomId(chatRoomId)
                .userId(userId)
                .username(username)
                .content(content)
                .scheduledAt(scheduledAt)
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
     * Polled every 30s. Picks PENDING rows whose scheduledAt has arrived
     * and delivers them via MessageSenderService. Per-row exceptions are
     * isolated: a failure on one row does not abort the rest of the batch.
     *
     * Multi-replica safety: @Version on the entity (V7 column) makes
     * concurrent updates from competing replicas throw
     * ObjectOptimisticLockingFailureException — we treat that as a
     * benign "lost race" (the winning replica is already handling it).
     */
    @Scheduled(fixedDelay = 30_000L)
    @Transactional
    public void deliverDue() {
        List<ScheduledMessageEntity> due = repository.findDueForSending(LocalDateTime.now());
        if (due.isEmpty()) return;
        log.info("Delivering {} scheduled message(s)", due.size());

        for (ScheduledMessageEntity row : due) {
            try {
                ChatMessage msg = new ChatMessage();
                // NOTE: messageSenderService.send() owns messageId + timestamp — it
                // assigns both on entry. Setting them here would be dead writes; relying
                // on send()'s contract keeps that ownership in one place.
                msg.setChatRoomId(row.getChatRoomId());
                msg.setUserId(row.getUserId());
                msg.setUsername(row.getUsername());
                msg.setContent(row.getContent());
                msg.setType(ChatMessage.MessageType.CHAT);

                messageSenderService.send(msg);

                row.setStatus(ScheduledMessageStatus.SENT);
                row.setSentMessageId(msg.getMessageId());
                repository.save(row);
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                // Another replica's poller won the race. Benign — skip silently.
                log.info("Lost race on scheduled message id={} to another replica", row.getId());
            } catch (Exception e) {
                log.error("Scheduled delivery failed for id={}: {}", row.getId(), e.getMessage(), e);
                row.setStatus(ScheduledMessageStatus.FAILED);
                row.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                repository.save(row);
            }
        }
    }
}
