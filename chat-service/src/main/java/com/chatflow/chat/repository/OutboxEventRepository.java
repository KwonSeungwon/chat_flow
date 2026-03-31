package com.chatflow.chat.repository;

import com.chatflow.chat.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxEvent.OutboxStatus status);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSED', e.processedAt = :now WHERE e.id IN :ids AND e.status = 'PENDING'")
    int markProcessed(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PROCESSED' AND e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") LocalDateTime before);
}
