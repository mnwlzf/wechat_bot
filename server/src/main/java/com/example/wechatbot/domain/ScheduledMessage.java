package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 定时消息：到点后复制到 outbox，沿用既有的投递与伪装链路。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "scheduled_message")
public class ScheduledMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "chat_id", nullable = false, length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName = "";

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledStatus status = ScheduledStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
