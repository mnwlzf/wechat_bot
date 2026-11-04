package com.example.wechatbot.domain;

/** 任务是谁建的：管理端，还是聊天里用一句话让机器人建的。 */
public enum ScheduleSource {
    ADMIN,
    CHAT;

    public static ScheduleSource parse(String value) {
        if (value == null || value.isBlank()) {
            return ADMIN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ADMIN;
        }
    }
}
