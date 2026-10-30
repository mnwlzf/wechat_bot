package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "message_log")
public class MessageLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageDirection direction;

    @Column(name = "sender_id", length = 180)
    private String senderId;

    @Column(name = "sender_name", length = 180)
    private String senderName;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "provider_name", length = 120)
    private String providerName;

    @Column(length = 120)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MessageStatus status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", length = 20)
    private MediaKind mediaKind;

    @Column(name = "media_name", length = 255)
    private String mediaName;

    @Column(name = "media_size")
    private Long mediaSize;

    @Column(name = "media_url", length = 1000)
    private String mediaUrl;

    @Column(name = "raw_type")
    private Integer rawType;

    @Column(name = "media_md5", length = 64)
    private String mediaMd5;

    @Column(name = "media_aeskey", length = 64)
    private String mediaAeskey;

    /** 引用消息里被引用的发送者。 */
    @Column(name = "quote_author", length = 180)
    private String quoteAuthor;

    /** 引用消息里被引用的原文。 */
    @Column(name = "quote_text", length = 1000)
    private String quoteText;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
