package com.example.wechatbot.domain;

public enum ScheduledStatus {
    /** 等下一次发送（提醒在等回执期间也停在这里）。 */
    PENDING,
    /** 已完成：一次性消息发完，或周期消息到了截止时间。 */
    SENT,
    /** 对方回话了，提醒提前结束。 */
    ACKED,
    /** 次数用完 / 到截止时间，提醒放弃。 */
    EXPIRED,
    /** 手动取消。 */
    CANCELLED,
    FAILED
}
