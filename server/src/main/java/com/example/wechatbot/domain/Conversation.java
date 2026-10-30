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
@Table(name = "conversation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversation_bot_chat", columnNames = {"bot_id", "chat_id"})
})
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "chat_id", nullable = false, length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_type", nullable = false, length = 20)
    private ChatType chatType;

    @Column(name = "last_message", nullable = false, length = 500)
    private String lastMessage = "";

    @Column(name = "last_message_at", nullable = false)
    private OffsetDateTime lastMessageAt;

    /** 人工接管：接管期间收到的消息只记录不自动回复。 */
    @Column(name = "bot_paused", nullable = false)
    private boolean botPaused = false;

    /** 该会话使用的提示词模板；为空时使用机器人默认系统提示词。 */
    @Column(name = "prompt_template_id")
    private UUID promptTemplateId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        if (lastMessageAt == null) {
            lastMessageAt = now;
        }
    }
}

