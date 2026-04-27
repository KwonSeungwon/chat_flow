package com.chatflow.chat.service;

import com.chatflow.chat.dto.ReportDto;
import com.chatflow.chat.entity.*;
import com.chatflow.chat.exception.MessageNotFoundException;
import com.chatflow.chat.exception.ReportRateLimitException;
import com.chatflow.chat.exception.SelfReportNotAllowedException;
import com.chatflow.chat.repository.ChatMessageRepository;
import com.chatflow.chat.repository.MessageReportRepository;
import com.chatflow.chat.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReportService {

    private static final int RATE_LIMIT_PER_MINUTE = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int MESSAGE_PREVIEW_MAX_LENGTH = 200;

    private final MessageReportRepository messageReportRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomPermissionService roomPermissionService;

    /**
     * Submits a report for a message.
     * <p>
     * Guards:
     * <ul>
     *   <li>Message must exist</li>
     *   <li>Reporter cannot report their own message (userId comparison)</li>
     *   <li>Idempotent: duplicate (messageId, reporterUserId) returns existing report id</li>
     *   <li>Rate limit: max 5 reports per user per minute</li>
     * </ul>
     *
     * @return the report id (new or existing if idempotent)
     */
    @Transactional
    public Long submitReport(String messageId, String reporterUserId,
                             ReportReason reason, String comment) {
        // 1. Look up message
        ChatMessageEntity message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException(
                        "메시지를 찾을 수 없습니다. messageId=" + messageId));

        // 2. Self-report check (compare userId directly)
        if (reporterUserId.equals(message.getUserId())) {
            throw new SelfReportNotAllowedException(
                    "자신의 메시지는 신고할 수 없습니다. messageId=" + messageId);
        }

        // 3. Idempotency: if already reported by same user, return existing id
        Optional<MessageReportEntity> existing =
                messageReportRepository.findByMessageIdAndReportedBy(messageId, reporterUserId);
        if (existing.isPresent()) {
            log.info("Duplicate report ignored (idempotent): messageId={}, reporterUserId={}",
                    messageId, reporterUserId);
            return existing.get().getId();
        }

        // 4. Rate limit
        LocalDateTime windowStart = LocalDateTime.now().minus(RATE_LIMIT_WINDOW);
        long recentCount = messageReportRepository.countByReportedByAndCreatedAtAfter(
                reporterUserId, windowStart);
        if (recentCount >= RATE_LIMIT_PER_MINUTE) {
            throw new ReportRateLimitException(
                    "신고 횟수가 제한을 초과했습니다. 잠시 후 다시 시도해 주세요. (분당 최대 "
                            + RATE_LIMIT_PER_MINUTE + "건)");
        }

        // 5. Persist
        MessageReportEntity report = MessageReportEntity.builder()
                .messageId(messageId)
                .roomId(message.getChatRoomId())
                .reportedBy(reporterUserId)
                .reason(reason)
                .comment(comment)
                .status(ReportStatus.PENDING)
                .build();

        MessageReportEntity saved = messageReportRepository.save(report);
        log.info("Report submitted: id={}, messageId={}, roomId={}, reporterUserId={}, reason={}",
                saved.getId(), messageId, message.getChatRoomId(), reporterUserId, reason);

        return saved.getId();
    }

    /**
     * Lists all PENDING reports for a room. Only OWNER or MODERATOR may call this.
     */
    @Transactional(readOnly = true)
    public List<ReportDto> listPendingReports(String roomId, String actorUserId) {
        roomPermissionService.requireRole(roomId, actorUserId, RoomRole.OWNER, RoomRole.MODERATOR);
        roomPermissionService.requireNotDmRoom(roomId);

        List<MessageReportEntity> reports =
                messageReportRepository.findByRoomIdAndStatusOrderByCreatedAtDesc(
                        roomId, ReportStatus.PENDING);

        return reports.stream()
                .map(report -> {
                    ChatMessageEntity message = chatMessageRepository
                            .findById(report.getMessageId())
                            .orElse(null);

                    String reporterUsername = roomMemberRepository
                            .findByRoomIdAndUserId(roomId, report.getReportedBy())
                            .map(RoomMemberEntity::getUsername)
                            .orElse("(unknown)");

                    return ReportDto.from(report, message, reporterUsername);
                })
                .toList();
    }

    /**
     * Updates a report's status to RESOLVED or DISMISSED.
     * Only OWNER or MODERATOR of the report's room may call this.
     */
    @Transactional
    public void updateStatus(Long reportId, String actorUserId, ReportStatus newStatus) {
        if (newStatus == ReportStatus.PENDING) {
            throw new IllegalArgumentException(
                    "신고 상태를 PENDING으로 변경할 수 없습니다. RESOLVED 또는 DISMISSED만 허용됩니다.");
        }

        MessageReportEntity report = messageReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "신고를 찾을 수 없습니다. reportId=" + reportId));

        roomPermissionService.requireRole(report.getRoomId(), actorUserId,
                RoomRole.OWNER, RoomRole.MODERATOR);

        report.setStatus(newStatus);
        report.setResolvedBy(actorUserId);
        report.setResolvedAt(LocalDateTime.now());
        messageReportRepository.save(report);

        log.info("Report status updated: reportId={}, newStatus={}, resolvedBy={}",
                reportId, newStatus, actorUserId);
    }
}
