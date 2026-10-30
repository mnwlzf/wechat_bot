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
@Table(name = "model_provider")
public class ModelProvider {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "text")
    private String apiKeyEncrypted = "";

    @Column(name = "api_key_backup_encrypted", nullable = false, columnDefinition = "text")
    private String apiKeyBackupEncrypted = "";


    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 100;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 60;

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

