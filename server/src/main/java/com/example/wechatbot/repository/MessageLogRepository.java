package com.example.wechatbot.repository;

import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MessageLogRepository extends JpaRepository<MessageLog, UUID> {
    Page<MessageLog> findByConversationId(UUID conversationId, Pageable pageable);
    List<MessageLog> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);
    long countByCreatedAtGreaterThanEqual(OffsetDateTime time);
    long countByDirectionAndCreatedAtGreaterThanEqual(MessageDirection direction, OffsetDateTime time);
    Page<MessageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
