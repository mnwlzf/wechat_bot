package com.example.wechatbot.repository;

import com.example.wechatbot.domain.ScheduleSource;
import com.example.wechatbot.domain.ScheduledKind;
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

    Page<ScheduledMessage> findByKindOrderByScheduledAtDesc(ScheduledKind kind, Pageable pageable);

    List<ScheduledMessage> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledStatus status, OffsetDateTime now, Pageable pageable);

    long countByStatus(ScheduledStatus status);

    /** 某个会话里还在等（PENDING）的提醒：回执、配额判断、列表都用它。 */
    List<ScheduledMessage> findByChatIdAndKindAndStatusOrderByScheduledAtAsc(
            String chatId, ScheduledKind kind, ScheduledStatus status);

    long countByChatIdAndKindAndStatus(String chatId, ScheduledKind kind, ScheduledStatus status);

    /** 某个会话今天新建了多少个提醒（防滥用）。 */
    long countByChatIdAndKindAndCreatedAtAfter(String chatId, ScheduledKind kind, OffsetDateTime after);

    /** 某个会话里最近发出过的那条提醒（自然语言回执默认停它）。 */
    List<ScheduledMessage> findByChatIdAndKindAndStatusAndSentCountGreaterThanOrderBySentAtDesc(
            String chatId, ScheduledKind kind, ScheduledStatus status, int sentCount, Pageable pageable);

    /** 管理端按来源过滤：看看哪些任务是聊天里让人设自己建的。 */
    Page<ScheduledMessage> findBySourceOrderByScheduledAtDesc(ScheduleSource source, Pageable pageable);
}
