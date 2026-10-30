package com.example.wechatbot.repository;

import com.example.wechatbot.domain.OutboxMessage;
import com.example.wechatbot.domain.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from OutboxMessage o
            where (o.status = :pending and o.nextAttemptAt <= :now)
               or (o.status = :processing and o.leaseUntil < :now)
            order by o.createdAt asc
            """)
    List<OutboxMessage> findReadyForDelivery(
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing,
            @Param("now") OffsetDateTime now,
            Pageable pageable);

    @Query("""
            select distinct o.chatId from OutboxMessage o
            where o.status = :status and o.leaseUntil >= :now
            """)
    List<String> findActiveLeaseChatIds(@Param("status") OutboxStatus status,
                                        @Param("now") OffsetDateTime now);

    long countByStatus(OutboxStatus status);

    List<OutboxMessage> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);
}
