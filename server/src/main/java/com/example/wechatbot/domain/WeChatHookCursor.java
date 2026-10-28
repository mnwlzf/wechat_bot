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
@Table(name = "wechat_hook_cursor",
        uniqueConstraints = @UniqueConstraint(name = "uk_wechat_hook_cursor_account_chat", columnNames = {"account", "chat_id"}))
public class WeChatHookCursor {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 180)
    private String account;

    @Column(name = "chat_id", nullable = false, length = 180)
    private String chatId;

    @Column(nullable = false, length = 30)
    private String anchor;

    @Column(name = "cursor_value", nullable = false)
    private long cursorValue;

    @Column(nullable = false)
    private boolean initialized;

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
