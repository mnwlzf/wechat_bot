package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 关键词 / 正则自动回复规则。命中后直接返回固定话术，不调用大模型。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "auto_reply_rule")
public class AutoReplyRule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    /** EXACT / CONTAINS / REGEX */
    @Column(name = "match_type", nullable = false, length = 20)
    private String matchType = "CONTAINS";

    @Column(nullable = false, length = 500)
    private String pattern;

    @Column(nullable = false, columnDefinition = "text")
    private String reply;

    /** ALL / PRIVATE / GROUP */
    @Column(nullable = false, length = 20)
    private String scope = "ALL";

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;

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
