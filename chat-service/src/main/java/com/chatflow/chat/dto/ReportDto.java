package com.chatflow.chat.dto;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.MessageReportEntity;
import com.chatflow.chat.entity.ReportReason;
import com.chatflow.chat.entity.ReportStatus;

import java.time.LocalDateTime;

public record ReportDto(
        Long id,
        String messageId,
        String messageContent,
        String messageAuthor,
        String reportedBy,
        String reportedByUserId,
        ReportReason reason,
        String comment,
        ReportStatus status,
        LocalDateTime createdAt
) {

    private static final int MESSAGE_PREVIEW_MAX_LENGTH = 200;
    private static final String DELETED_MESSAGE_PLACEHOLDER = "[삭제된 메시지]";
    private static final String UNKNOWN_AUTHOR = "(unknown)";

    /**
     * Builds a ReportDto from a report entity, the associated message (may be null if deleted),
     * and the reporter's username.
     */
    public static ReportDto from(MessageReportEntity report,
                                 ChatMessageEntity message,
                                 String reporterUsername) {
        String content;
        String author;
        if (message == null || message.isDeleted()) {
            content = DELETED_MESSAGE_PLACEHOLDER;
            author = message != null ? message.getUsername() : UNKNOWN_AUTHOR;
        } else {
            content = truncate(message.getContent(), MESSAGE_PREVIEW_MAX_LENGTH);
            author = message.getUsername();
        }

        return new ReportDto(
                report.getId(),
                report.getMessageId(),
                content,
                author,
                reporterUsername,
                report.getReportedBy(),
                report.getReason(),
                report.getComment(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return DELETED_MESSAGE_PLACEHOLDER;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
