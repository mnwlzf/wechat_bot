package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ScheduledMessage;
import com.example.wechatbot.domain.ScheduledStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, UUID> {
    Page<ScheduledMessage> findAllByOrderByScheduledAtDesc(Pageable pageable);

    List<ScheduledMessage> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledStatus status, OffsetDateTime now, Pageable pageable);

    long countByStatus(ScheduledStatus status);
}
