package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 管理端可实时修改的键值配置；没有记录时回落到环境变量默认值。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "app_setting")
public class AppSetting {
    @Id
    @Column(name = "setting_key", length = 120)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "text")
    private String value = "";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
