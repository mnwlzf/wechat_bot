package com.example.wechatbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 回复策略：控制「这个群要不要回、什么条件下回」。
 *
 * <p>{@code chatId == null} 表示全局默认策略，最多一条；带 {@code chatId} 表示某个群的覆盖策略。
 * 字段都可空，空值表示「沿用下一层」，因此一个群只改模式、唤醒词或概率中的一项即可。</p>
 */
@Entity
@Table(name = "reply_policy")
@Getter
@Setter
public class ReplyPolicy {

    @Id
    private UUID id;

    /** 为空表示全局默认策略；否则是该群/会话的 chat_id。 */
    @Column(name = "chat_id", length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName = "";

    /** 只对按群策略有意义：临时停用后该群立即回退到默认策略。 */
    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReplyMode mode;

    @Column(name = "wake_words", length = 500)
    private String wakeWords;

    /** 随机回复的命中概率，0~1。 */
    @Column(name = "random_rate")
    private Double randomRate;

    /**
     * 拟人模式下两次「要不要回」判定的最小间隔（秒）。
     *
     * <p>群里可能几秒钟就好几条消息，逐条都问一次模型既贵又不像人；冷却期内的消息只入库、
     * 不判定，等冷却结束再用最新几条对话判一次。</p>
     */
    @Column(name = "human_cooldown_seconds")
    private Integer humanCooldownSeconds;

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
