package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 可复用的系统提示词模板，按会话绑定不同“人格”。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 默认人设：会话没有单独绑定时回退到它。全局最多一条。 */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** 内置人设的稳定标识（例如 {@code da-fei-yu}）；用户自建的人设为 null。 */
    @Column(name = "builtin_key", length = 80)
    private String builtinKey;

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
