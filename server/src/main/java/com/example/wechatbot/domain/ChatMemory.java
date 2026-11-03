package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 会话（群）共享的长期记忆。
 *
 * <p>同一个群里的所有成员共用一份记忆：{@link MemoryKind#SUMMARY} 由模型把超出上下文窗口的
 * 历史滚动压缩而来，{@link MemoryKind#NOTE} 是管理端手工维护的要点（约定、偏好、禁忌等）。
 * 每次生成回复时会把它们拼进系统提示词，避免长群聊里「前面说过的事后面就忘了」。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_memory")
public class ChatMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chat_id", nullable = false, length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemoryKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemorySource source = MemorySource.MANUAL;

    /** 关掉的记忆条目不会进入提示词，但保留内容方便随时恢复。 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 摘要覆盖过的消息条数（人工条目恒为 0）。 */
    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    /** 摘要已经压缩到的消息时间；下次只处理比它更新的消息。 */
    @Column(name = "covered_until")
    private OffsetDateTime coveredUntil;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
