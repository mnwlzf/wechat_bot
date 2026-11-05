package com.example.wechatbot.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 定时消息：到点后复制到 outbox，沿用既有的投递与伪装链路。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "scheduled_message")
public class ScheduledMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "chat_id", nullable = false, length = 180)
    private String chatId;

    @Column(name = "chat_name", nullable = false, length = 180)
    private String chatName = "";

    /** MESSAGE：发一条/按周期发；REMINDER：等回执的间隔提醒。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledKind kind = ScheduledKind.MESSAGE;

    /** 人话标题（列表和「我有哪些提醒」用），例如「打卡」。 */
    @Column(length = 120)
    private String title;

    /** 提醒间隔（分钟）：发出后等多久还没回执就再催一次。 */
    @Column(name = "interval_minutes")
    private Integer intervalMinutes;

    /** 提醒次数上限。 */
    @Column(name = "max_times")
    private Integer maxTimes;

    /** 停止词（逗号分隔），对方回其中任意一个即视为已完成。 */
    @Column(name = "stop_words", length = 500)
    private String stopWords;

    /** 提醒文案，每行一句，按次数依次使用（人设生成的，比同一句复读自然）。 */
    @Column(columnDefinition = "text")
    private String variants;

    /** 次数用完后的收尾话术。 */
    @Column(name = "final_message", columnDefinition = "text")
    private String finalMessage;

    /** 对方回执的时间与那句原话（审计用）。 */
    @Column(name = "ack_at")
    private OffsetDateTime ackAt;

    @Column(name = "ack_content", length = 500)
    private String ackContent;

    /** ADMIN=管理端创建，CHAT=聊天里自然语言创建。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleSource source = ScheduleSource.ADMIN;

    /** 在聊天里创建时，是谁说的（请假手接管、白名单判断都靠它）。 */
    @Column(name = "creator_chat_id", length = 180)
    private String creatorChatId;

    /** 用户原话，用来复盘自然语言解析的准确率。 */
    @Column(name = "raw_instruction", columnDefinition = "text")
    private String rawInstruction;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    /**
     * 这一系列提醒「本来该在几点」——下一轮从它算，不从 {@link #scheduledAt} 算。
     *
     * <p>催命循环会把 {@code scheduledAt} 推成「18:00、18:05、18:10…」，
     * 如果拿最后那次去加一天，明天就变成 18:10，后天 18:15，越来越晚。
     * 有了这个锚点，对方回话（或次数用尽）之后下一轮永远回到 18:00。
     * 为空表示不重复，按 {@code scheduledAt} 处理。</p>
     */
    @Column(name = "anchor_at")
    private OffsetDateTime anchorAt;

    /** 重复规则；重复发送时每发一次就把 scheduledAt 往后推一个周期。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_rule", nullable = false, length = 20)
    private ScheduledRepeat repeatRule = ScheduledRepeat.NONE;

    /** 重复截止时间；为空表示一直重复。 */
    @Column(name = "repeat_until")
    private OffsetDateTime repeatUntil;

    /** 自定义 cron 表达式（仅 repeatRule = CRON 时使用），例如 {@code 0 9 * * 1-5}。 */
    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    /** 内容按行拆成多条依次发送（群通知分条更自然）。 */
    @Column(name = "split_lines", nullable = false)
    private boolean splitLines = false;

    /** 已经成功发出过几次（重复消息会累加）。 */
    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledStatus status = ScheduledStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
