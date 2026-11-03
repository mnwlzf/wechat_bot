package com.example.wechatbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 发信策略：控制「这条回复怎么发出去」的那组参数（延迟、分条、限流、风格）。
 *
 * <p>两张用法：{@code chatId == null} 表示全局默认策略，最多一条；
 * 带 {@code chatId} 表示某个会话/群的覆盖策略。所有字段都可空，
 * 空值表示「沿用下一层」，所以只改想改的那几项即可。</p>
 */
@Entity
@Table(name = "send_policy")
@Getter
@Setter
public class SendPolicy {

    @Id
    private UUID id;

    /** 为空表示全局默认策略；否则是该会话/群的 chat_id。 */
    @Column(name = "chat_id", length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName = "";

    /** 只对按群策略有意义：临时停用后该群立即回退到默认策略。 */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "read_delay_min_ms")
    private Integer readDelayMinMs;

    @Column(name = "read_delay_max_ms")
    private Integer readDelayMaxMs;

    @Column(name = "max_delay_ms")
    private Integer maxDelayMs;

    @Column(name = "split_enabled")
    private Boolean splitEnabled;

    @Column(name = "max_parts")
    private Integer maxParts;

    @Column(name = "max_part_chars")
    private Integer maxPartChars;

    @Column(name = "per_minute")
    private Integer perMinute;

    @Column(name = "per_10_minutes")
    private Integer per10Minutes;

    @Column(name = "per_hour")
    private Integer perHour;

    @Column(name = "per_day")
    private Integer perDay;

    @Column(name = "min_gap_ms")
    private Integer minGapMs;

    @Column(name = "emoji_limit")
    private Integer emojiLimit;

    @Column(name = "drop_rate")
    private Double dropRate;

    @Column(name = "prompt_enabled")
    private Boolean promptEnabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean isDefaultPolicy() {
        return chatId == null || chatId.isBlank();
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (chatName == null) {
            chatName = "";
        }
        if (chatId != null && chatId.isBlank()) {
            chatId = null;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
