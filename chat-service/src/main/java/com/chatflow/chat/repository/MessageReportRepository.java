package com.chatflow.chat.repository;

import com.chatflow.chat.entity.MessageReportEntity;
import com.chatflow.chat.entity.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageReportRepository extends JpaRepository<MessageReportEntity, Long> {

    List<MessageReportEntity> findByRoomIdAndStatusOrderByCreatedAtDesc(String roomId, ReportStatus status);

    boolean existsByMessageIdAndReportedBy(String messageId, String reportedBy);

    long countByReportedByAndCreatedAtAfter(String reportedBy, LocalDateTime after);
}
