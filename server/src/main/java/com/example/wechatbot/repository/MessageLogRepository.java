package com.example.wechatbot.repository;

import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MessageLogRepository extends JpaRepository<MessageLog, UUID> {
    Page<MessageLog> findByConversationId(UUID conversationId, Pageable pageable);
    List<MessageLog> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);
    long countByCreatedAtGreaterThanEqual(OffsetDateTime time);
    long countByDirectionAndCreatedAtGreaterThanEqual(MessageDirection direction, OffsetDateTime time);
    long countByStatusAndCreatedAtGreaterThanEqual(MessageStatus status, OffsetDateTime time);
    long countByDirectionAndStatusAndCreatedAtGreaterThanEqual(MessageDirection direction,
                                                               MessageStatus status,
                                                               OffsetDateTime time);
    Page<MessageLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 用量统计的原始行：时间、方向、状态、token、耗时、模型与所属会话。
     *
     * <p>刻意不做数据库端聚合：{@code date_trunc} 属于 PostgreSQL 方言，测试用的 H2 无法执行，
     * 而且个人机器人量级下把区间内的行取回内存聚合更可控，时区口径也能与 JVM 保持一致。</p>
     */
    @Query("""
            SELECT m.createdAt, m.direction, m.status, m.promptTokens, m.completionTokens,
                   m.latencyMs, m.providerName, m.model, m.conversationId
            FROM MessageLog m
            WHERE m.createdAt >= :from
            ORDER BY m.createdAt
            """)
    List<Object[]> usageRows(@Param("from") OffsetDateTime from);
}
