package com.example.wechatbot.domain;

/** 定时任务类型：一条消息，还是「等回执的提醒」。 */
public enum ScheduledKind {
    /** 到点发一条 / 按周期重复，不需要对方回应。 */
    MESSAGE,
    /** 提醒：每 N 分钟催一次，回话就停，最多 M 次。 */
    REMINDER;

    public static ScheduledKind parse(String value) {
        if (value == null || value.isBlank()) {
            return MESSAGE;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MESSAGE;
        }
    }
}
