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
@Table(name = "bot_config")
public class BotConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "model_provider_id")
    private UUID modelProviderId;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "reply_private", nullable = false)
    private boolean replyPrivate = true;

    @Column(name = "reply_group", nullable = false)
    private boolean replyGroup = false;

    @Column(name = "group_wake_words", nullable = false, length = 500)
    private String groupWakeWords = "@机器人";

    /**
     * 带多少条历史消息进上下文。默认 48：20 条只够十几轮对话，一个活跃私聊半天就滚完了，
     * 表现就是「上午说的事下午就忘」；48 大约能覆盖一两个小时的连续聊天。
     */
    @Column(name = "context_window", nullable = false)
    private int contextWindow = 48;

    @Column(nullable = false)
    private double temperature = 0.7;

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens = 800;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute = 20;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
