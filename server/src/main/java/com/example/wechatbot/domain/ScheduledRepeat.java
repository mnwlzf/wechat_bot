package com.example.wechatbot.domain;

import java.time.OffsetDateTime;

/**
 * 定时消息的重复规则。
 *
 * <p>重复不额外存时间表：每次发完就把 {@code scheduledAt} 往后推一个周期，
 * 因此「每天 9:00」「每周一 9:00」都由首次填写的时间点决定。</p>
 */
public enum ScheduledRepeat {
    NONE,
    DAILY,
    WEEKLY;

    /** 下一次发送时间；{@link #NONE} 返回 null。 */
    public OffsetDateTime next(OffsetDateTime current) {
        if (current == null) {
            return null;
        }
        return switch (this) {
            case DAILY -> current.plusDays(1);
            case WEEKLY -> current.plusWeeks(1);
            case NONE -> null;
        };
    }

    public static ScheduledRepeat parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}
