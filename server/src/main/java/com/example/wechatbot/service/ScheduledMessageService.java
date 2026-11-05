package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.config.ReminderProperties;
import com.example.wechatbot.disguise.DisguiseClock;
import com.example.wechatbot.domain.*;
import com.example.wechatbot.flow.FlowTraceService;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.ScheduledMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定时任务：到点后转成 outbox 记录，交给既有的投递器发送。
 *
 * <p>不直接调用微信接口，是为了沿用同一套重试、租约与伪装节奏；
 * 定时任务只负责「什么时候该发」，不负责「怎么发」。</p>
 *
 * <p>两种任务：</p>
 * <ul>
 *   <li>{@link ScheduledKind#MESSAGE}：一次性或按每天/每周/cron 重复；</li>
 *   <li>{@link ScheduledKind#REMINDER}：每 {@code intervalMinutes} 催一次，对方回话就停
 *       （{@link #ack}），最多催 {@code maxTimes} 次，次数用完补一句收尾再置 EXPIRED。
 *       撞上免打扰时段不发送也不计数，醒来接着催。</li>
 * </ul>
 */
@Service
public class ScheduledMessageService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageService.class);
    private static final int BATCH = 20;
    /** 分条发送的条数上限，避免一次填几十行把群刷屏。 */
    private static final int MAX_PARTS = 10;
    /** 提醒间隔的硬上限：一天催一次已经是极限了。 */
    private static final int MAX_INTERVAL_MINUTES = 24 * 60;
    /** cron 的「几点几分」按这个时区算：业务时区固定，不跟着入参 offset 或 JVM 默认时区漂。 */
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Shanghai");

    private final ScheduledMessageRepository repository;
    private final OutboxMessageRepository outboxRepository;
    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botRepository;
    private final ContentFilterService contentFilter;
    private final DisguiseClock clock;
    private final ReminderProperties reminderProperties;

    public ScheduledMessageService(ScheduledMessageRepository repository,
                                   OutboxMessageRepository outboxRepository,
                                   ConversationRepository conversationRepository,
                                   BotConfigRepository botRepository,
                                   ContentFilterService contentFilter,
                                   DisguiseClock clock,
                                   ReminderProperties reminderProperties) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.conversationRepository = conversationRepository;
        this.botRepository = botRepository;
        this.contentFilter = contentFilter;
        this.clock = clock;
        this.reminderProperties = reminderProperties;
    }

    public Page<ScheduledMessage> list(int page, int size) {
        return repository.findAllByOrderByScheduledAtDesc(
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    /** 只列提醒（管理端「提醒」页签）。 */
    public Page<ScheduledMessage> listReminders(int page, int size) {
        return repository.findByKindOrderByScheduledAtDesc(ScheduledKind.REMINDER,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    public long pendingCount() {
        return repository.countByStatus(ScheduledStatus.PENDING);
    }

    /**
     * 「今天」这条任务走到哪一步了。
     *
     * @param status      PENDING / SENT / NUDGING / DONE / GAVE_UP / IDLE / STOPPED / FAILED
     * @param text        给列表直接显示的一句话（「已催 3 次，等回话」）
     * @param sentCount   今天发出去几次（跨零点的一轮按整轮算，够用）
     * @param replied     今天对方回话了吗；null = 这类任务不用回（定时消息）
     * @param repliedText 回话那一列的文案
     */
    public record Today(String status, String text, int sentCount, Boolean replied, String repliedText) {}

    /**
     * 把「今天」算出来给列表看：今天发没发、催了几次、对方回没回。
     *
     * <p>不存库、每次现算——存字段就得在每个分支（催、回话、收尾、停止）里同步维护，
     * 漏一处列表就会长期显示错的状态；而这些东西全在 {@code sentAt} / {@code ackAt} /
     * {@code status} 里，算一次就够了。日期一律按 Asia/Shanghai 切。</p>
     */
    public static Today today(ScheduledMessage message, OffsetDateTime now) {
        ScheduledStatus status = message.getStatus() == null ? ScheduledStatus.PENDING : message.getStatus();
        boolean reminder = message.getKind() == ScheduledKind.REMINDER;
        java.time.LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        boolean sentToday = message.getSentAt() != null
                && message.getSentAt().atZoneSameInstant(ZONE).toLocalDate().isEqual(today);
        boolean ackedToday = message.getAckAt() != null
                && message.getAckAt().atZoneSameInstant(ZONE).toLocalDate().isEqual(today);
        boolean dueToday = message.getScheduledAt() != null
                && message.getScheduledAt().atZoneSameInstant(ZONE).toLocalDate().isEqual(today);
        int count = sentToday ? Math.max(0, message.getSentCount()) : 0;

        switch (status) {
            case CANCELLED:
                return new Today("STOPPED", "已停止", 0, null, null);
            case FAILED:
                return new Today("FAILED", "发送失败", count, null, null);
            case ACKED:
                return new Today("DONE", ackedToday ? "今天已完成" : "已完成", 0,
                        ackedToday, ackedToday ? "今天回话了" : null);
            case EXPIRED:
                return sentToday
                        ? new Today("GAVE_UP", "催满 " + (message.getMaxTimes() == null ? 0 : message.getMaxTimes())
                        + " 次，作罢", count, reminder ? Boolean.FALSE : null, reminder ? "一直没回" : null)
                        : new Today("IDLE", "今天不发", 0, null, null);
            case SENT:
                return sentToday
                        ? new Today("SENT", "今天已发送", count, null, null)
                        : new Today("IDLE", "今天不发", 0, null, null);
            default:
                break;
        }
        // PENDING：今天已经回话（每天的任务回完话就排到明天了）> 今天已经发过 > 今天还没到点
        if (ackedToday) {
            return new Today("DONE", "今天已完成", count, true, "今天回话了");
        }
        if (sentToday) {
            return reminder
                    ? new Today("NUDGING", "已催 " + count + " 次，等回话", count, false, "还没回")
                    : new Today("SENT", "今天已发送", count, null, null);
        }
        if (dueToday) {
            return new Today("PENDING", "待发送", 0, null, null);
        }
        return new Today("IDLE", "今天不发", 0, null, null);
    }

    public ScheduledMessage get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("定时消息不存在"));
    }

    /** 兼容旧调用：一次性、不分条。 */
    @Transactional
    public ScheduledMessage create(UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt) {
        return create(botId, chatId, chatName, content, scheduledAt, ScheduledRepeat.NONE, null, false);
    }

    @Transactional
    public ScheduledMessage create(UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                                   OffsetDateTime repeatUntil, boolean splitLines) {
        return create(botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines, null);
    }

    @Transactional
    public ScheduledMessage create(UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                                   OffsetDateTime repeatUntil, boolean splitLines, String cronExpression) {
        ScheduledMessage message = new ScheduledMessage();
        apply(message, botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines, cronExpression);
        message.setStatus(ScheduledStatus.PENDING);
        return repository.save(message);
    }

    /**
     * 提醒任务需要的东西一次给全。
     *
     * @param variants     人设生成的几句文案（每行一句，按次数依次用），可以为空
     * @param finalMessage 次数用完后的收尾话术，可以为空
     */
    public record ReminderSpec(String title,
                               OffsetDateTime firstAt,
                               Integer intervalMinutes,
                               Integer maxTimes,
                               String stopWords,
                               String content,
                               String variants,
                               String finalMessage,
                               ScheduledRepeat repeatRule,
                               String cronExpression,
                               OffsetDateTime repeatUntil,
                               ScheduleSource source,
                               String creatorChatId,
                               String rawInstruction) {}

    /**
     * 建一个提醒任务。
     *
     * <p>只允许私聊：群里谁都能说话，提醒会变成对所有人的广播，也没法判定该谁回执。</p>
     */
    @Transactional
    public ScheduledMessage createReminder(UUID botId, String chatId, String chatName, ReminderSpec spec) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要提醒的会话");
        }
        String normalizedChatId = chatId.trim();
        BotConfig bot = requireBot(botId);
        Conversation conversation = conversationRepository.findByBotIdAndChatId(bot.getId(), normalizedChatId)
                .orElse(null);
        if (conversation != null && conversation.getChatType() == ChatType.GROUP) {
            throw ApiException.badRequest("群聊不支持提醒：只能给私聊的人设提醒");
        }
        OffsetDateTime firstAt = spec.firstAt() == null ? OffsetDateTime.now().plusMinutes(1) : spec.firstAt();
        if (firstAt.isBefore(OffsetDateTime.now().minusMinutes(1))) {
            throw ApiException.badRequest("提醒时间不能早于当前时间");
        }
        long waiting = repository.countByChatIdAndKindAndStatus(
                normalizedChatId, ScheduledKind.REMINDER, ScheduledStatus.PENDING);
        if (waiting >= Math.max(1, reminderProperties.getMaxWaitingPerChat())) {
            throw ApiException.badRequest("这个会话已经有 " + waiting + " 个提醒在等回执，先停掉一个再建");
        }
        long today = repository.countByChatIdAndKindAndCreatedAtAfter(normalizedChatId, ScheduledKind.REMINDER,
                OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0));
        if (today >= Math.max(1, reminderProperties.getMaxPerDayPerChat())) {
            throw ApiException.badRequest("今天已经建了 " + today + " 个提醒，明天再说吧");
        }

        ScheduledMessage message = new ScheduledMessage();
        message.setKind(ScheduledKind.REMINDER);
        message.setBotId(bot.getId());
        message.setConversationId(conversation == null ? null : conversation.getId());
        message.setChatId(normalizedChatId);
        message.setChatName(chatName == null || chatName.isBlank()
                ? (conversation == null ? normalizedChatId : conversation.getChatName())
                : chatName.trim());
        message.setTitle(trimTo(spec.title(), 120));
        message.setContent(trimTo(spec.content(), 2000));
        message.setVariants(trimTo(spec.variants(), 4000));
        message.setFinalMessage(trimTo(spec.finalMessage(), 500));
        String stopWords = spec.stopWords() == null || spec.stopWords().isBlank()
                ? defaultStopWords()
                : spec.stopWords().trim();
        message.setStopWords(trimTo(stopWords, 500));
        message.setScheduledAt(firstAt);
        message.setRepeatRule(spec.repeatRule() == null ? ScheduledRepeat.NONE : spec.repeatRule());
        // 记下这一系列本来该在的钟点：催到 18:25 之后明天还得是 18:00
        message.setAnchorAt(message.getRepeatRule() == ScheduledRepeat.NONE ? null : firstAt);
        message.setCronExpression(trimTo(spec.cronExpression(), 120));
        message.setRepeatUntil(spec.repeatUntil());
        message.setIntervalMinutes(clampInterval(spec.intervalMinutes()));
        message.setMaxTimes(clampMaxTimes(spec.maxTimes()));
        message.setSplitLines(false);
        message.setStatus(ScheduledStatus.PENDING);
        message.setSource(spec.source() == null ? ScheduleSource.ADMIN : spec.source());
        message.setCreatorChatId(trimTo(spec.creatorChatId(), 180));
        message.setRawInstruction(trimTo(spec.rawInstruction(), 2000));
        return repository.save(message);
    }

    /** 默认停止词：对方随口一句「好了」「收到」也算完成。 */
    static String defaultStopWords() {
        return "已打卡,打卡了,打完了,完成了,弄好了,搞定了,好了,收到,知道了,不用提醒了,别提醒了";
    }

    /** 编辑待发送的定时消息：时间、接收人、内容、重复规则都可以改。 */
    @Transactional
    public ScheduledMessage update(UUID id, UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                                   OffsetDateTime repeatUntil, boolean splitLines) {
        return update(id, botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines, null);
    }

    @Transactional
    public ScheduledMessage update(UUID id, UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                                   OffsetDateTime repeatUntil, boolean splitLines, String cronExpression) {
        ScheduledMessage message = get(id);
        if (message.getStatus() != ScheduledStatus.PENDING) {
            throw ApiException.badRequest("只有待发送的消息可以编辑");
        }
        apply(message, botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines, cronExpression);
        return repository.save(message);
    }

    @Transactional
    public ScheduledMessage cancel(UUID id) {
        ScheduledMessage message = get(id);
        if (message.getStatus() != ScheduledStatus.PENDING) {
            throw ApiException.badRequest("只有待发送的消息可以取消");
        }
        message.setStatus(ScheduledStatus.CANCELLED);
        return repository.save(message);
    }

    /**
     * 要改哪几项就传哪几项，其余字段保持原样。
     *
     * <p>聊天里说「把打卡改成每天九点」「改成十分钟催一次」时，我们只知道要改的那一两项；
     * 用这个补丁而不是整行覆盖，避免把没提到的字段（停止词、文案、重复规则）顺手清空。</p>
     */
    public record ReminderPatch(String title,
                                OffsetDateTime firstAt,
                                Integer intervalMinutes,
                                Integer maxTimes,
                                String stopWords,
                                String content,
                                String variants,
                                String finalMessage,
                                ScheduledRepeat repeatRule,
                                String cronExpression,
                                OffsetDateTime repeatUntil) {}

    /** 局部更新：只改传进来的非空字段。 */
    @Transactional
    public ScheduledMessage patch(UUID id, ReminderPatch patch) {
        ScheduledMessage message = get(id);
        if (message.getStatus() != ScheduledStatus.PENDING) {
            throw ApiException.badRequest("这个任务已经不在等待中了，改不了");
        }
        if (patch == null) {
            return message;
        }
        if (patch.firstAt() != null) {
            // 只拒绝「把时间改到过去」；原样保存（前端把 +08:00 转成了 Z）不算改
            boolean sameInstant = patch.firstAt().toInstant().equals(message.getScheduledAt().toInstant());
            if (patch.firstAt().isBefore(OffsetDateTime.now().minusMinutes(1)) && !sameInstant) {
                throw ApiException.badRequest("改的时间已经过去了");
            }
            message.setScheduledAt(patch.firstAt());
            // 改成九点就是重新锚到九点：以后每轮都从九点起，不会延续之前催出来的偏移
            if (message.getRepeatRule() != null && message.getRepeatRule() != ScheduledRepeat.NONE) {
                message.setAnchorAt(patch.firstAt());
            }
        }
        if (hasText(patch.title())) {
            message.setTitle(trimTo(patch.title(), 120));
        }
        if (patch.content() != null) {
            message.setContent(trimTo(patch.content(), 2000));
        }
        if (patch.intervalMinutes() != null) {
            message.setIntervalMinutes(clampInterval(patch.intervalMinutes()));
        }
        if (patch.maxTimes() != null) {
            message.setMaxTimes(clampMaxTimes(patch.maxTimes()));
        }
        if (hasText(patch.stopWords())) {
            message.setStopWords(trimTo(patch.stopWords(), 500));
        }
        if (patch.variants() != null) {
            message.setVariants(trimTo(patch.variants(), 4000));
        }
        if (patch.finalMessage() != null) {
            message.setFinalMessage(trimTo(patch.finalMessage(), 500));
        }
        if (patch.repeatRule() != null) {
            message.setRepeatRule(patch.repeatRule());
            if (patch.repeatRule() == ScheduledRepeat.NONE) {
                message.setAnchorAt(null);
            } else if (message.getAnchorAt() == null) {
                message.setAnchorAt(message.getScheduledAt());
            }
        }
        if (patch.cronExpression() != null) {
            message.setCronExpression(trimTo(patch.cronExpression(), 120));
        }
        if (patch.repeatUntil() != null) {
            message.setRepeatUntil(patch.repeatUntil());
        }
        ScheduledMessage saved = repository.save(message);
        FlowTraceService.mark(FlowTraceService.Stage.REMIND, message.getChatId(), message.getChatName(),
                "提醒「" + safeTitle(message) + "」的设置被改了："
                        + message.getScheduledAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        + " 起，每 " + message.getIntervalMinutes() + " 分钟，最多 " + message.getMaxTimes() + " 次",
                null);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    // ------------------------------------------------------------------ 提醒

    /** 这个私聊里还在等的提醒（含还没开始催的）。 */
    public List<ScheduledMessage> waitingReminders(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return List.of();
        }
        return repository.findByChatIdAndKindAndStatusOrderByScheduledAtAsc(chatId.trim(),
                ScheduledKind.REMINDER, ScheduledStatus.PENDING);
    }

    /** 最近真正催过的那条：自然语言回执（「弄好了」）默认停它。 */
    public Optional<ScheduledMessage> latestWaitingReminder(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByChatIdAndKindAndStatusAndSentCountGreaterThanOrderBySentAtDesc(
                        chatId.trim(), ScheduledKind.REMINDER, ScheduledStatus.PENDING, 0, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    /**
     * 对方回话了：把这条提醒收掉。
     *
     * @param ackContent 触发停止的那句原话（留痕，方便复盘「它凭什么算我完成了」）
     */
    @Transactional
    public boolean ack(ScheduledMessage message, String ackContent) {
        if (message == null || message.getStatus() != ScheduledStatus.PENDING) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        int rounds = message.getSentCount();
        message.setAckAt(now);
        message.setAckContent(trimTo(ackContent, 500));
        message.setLastError(null);
        // 每天重复的提醒：这一轮到此为止，明天照旧；一次性的就彻底结束
        ScheduledRepeat repeat = message.getRepeatRule() == null ? ScheduledRepeat.NONE : message.getRepeatRule();
        boolean rescheduled = false;
        if (repeat != ScheduledRepeat.NONE) {
            OffsetDateTime next = nextOccurrence(message, now);
            if (next != null) {
                message.setScheduledAt(next);
                message.setSentCount(0);
                rescheduled = true;
            }
        }
        message.setStatus(rescheduled ? ScheduledStatus.PENDING : ScheduledStatus.ACKED);
        repository.save(message);
        FlowTraceService.mark(FlowTraceService.Stage.ACK, message.getChatId(), message.getChatName(),
                "对方回话了，提醒「" + safeTitle(message) + "」本轮结束（催了 " + rounds + " 次"
                        + (rescheduled ? "，下次 "
                        + message.getScheduledAt().atZoneSameInstant(ZONE)
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "")
                        + "）",
                ackContent);
        return true;
    }

    /** 「别提醒我了」：把这个会话里等待中的提醒全部停掉。 */
    @Transactional
    public int stopWaiting(String chatId) {
        List<ScheduledMessage> waiting = waitingReminders(chatId);
        for (ScheduledMessage message : waiting) {
            message.setStatus(ScheduledStatus.CANCELLED);
            message.setAckContent("用户要求停止提醒");
            repository.save(message);
            FlowTraceService.mark(FlowTraceService.Stage.ACK, message.getChatId(), message.getChatName(),
                    "对方要求停止提醒「" + safeTitle(message) + "」", null);
        }
        return waiting.size();
    }

    /** 管理端「停止」：待发或等回执都能停。 */
    @Transactional
    public ScheduledMessage stop(UUID id, String reason) {
        ScheduledMessage message = get(id);
        if (message.getStatus() != ScheduledStatus.PENDING) {
            throw ApiException.badRequest("这个任务已经不在等待中了");
        }
        message.setStatus(ScheduledStatus.CANCELLED);
        message.setAckContent(trimTo(reason, 500));
        return repository.save(message);
    }

    /** 每 20 秒扫一次到期任务，转成 outbox 交给投递器。 */
    @Scheduled(fixedDelayString = "${app.scheduled.dispatch-interval-ms:20000}", initialDelay = 30000)
    public void dispatchDue() {
        List<ScheduledMessage> due = repository
                .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        ScheduledStatus.PENDING, OffsetDateTime.now(), PageRequest.of(0, BATCH));
        if (due.isEmpty()) {
            return;
        }
        for (ScheduledMessage message : due) {
            try {
                queue(message);
            } catch (RuntimeException ex) {
                log.error("定时消息转入 outbox 失败: id={}", message.getId(), ex);
                message.setStatus(ScheduledStatus.FAILED);
                message.setAttempts(message.getAttempts() + 1);
                message.setLastError(abbreviate(ex.getMessage(), 1000));
                repository.save(message);
            }
        }
        log.info("定时消息已处理 {} 条", due.size());
    }

    private void queue(ScheduledMessage message) {
        if (message.getKind() == ScheduledKind.REMINDER) {
            queueReminder(message);
            return;
        }
        List<String> parts = split(message.getContent(), message.isSplitLines());
        if (parts.isEmpty()) {
            markFailed(message, "消息内容为空");
            return;
        }

        List<String> filtered = new ArrayList<>(parts.size());
        for (String part : parts) {
            ContentFilterService.Result result = contentFilter.apply(part);
            if (result.blocked()) {
                markFailed(message, "敏感词拦截：" + result.note());
                return;
            }
            filtered.add(result.content());
        }

        OffsetDateTime now = OffsetDateTime.now();
        message.setOutboxId(enqueue(message, filtered, now));

        message.setSentAt(now);
        message.setSentCount(message.getSentCount() + 1);
        message.setLastError(null);

        OffsetDateTime next = nextOccurrence(message, now);
        if (next == null) {
            message.setStatus(ScheduledStatus.SENT);
        } else {
            // 重复消息继续排队：状态留在 PENDING，时间推到下一次
            message.setScheduledAt(next);
            message.setStatus(ScheduledStatus.PENDING);
        }
        repository.save(message);
    }

    /**
     * 提醒：催一次，然后按间隔排下一次；次数用完补一句收尾。
     *
     * <p>撞上免打扰时段（半夜 / 离开）**不发送也不计数**——人在睡觉时催六次，
     * 醒来就只剩「6 次都没回」，等于白催。</p>
     */
    private void queueReminder(ScheduledMessage message) {
        OffsetDateTime now = OffsetDateTime.now();
        Instant instant = now.toInstant();
        if (!clock.canReply(instant)) {
            Instant resume = clock.resumeWithJitter(clock.nextActiveAt(instant));
            message.setScheduledAt(OffsetDateTime.ofInstant(resume, now.getOffset()));
            message.setStatus(ScheduledStatus.PENDING);
            repository.save(message);
            FlowTraceService.mark(FlowTraceService.Stage.SKIP, message.getChatId(), message.getChatName(),
                    "提醒撞上免打扰时段，改到 " + resume.atOffset(now.getOffset()).toLocalTime()
                            .withNano(0).toString().substring(0, 5) + " 再催", safeTitle(message));
            return;
        }
        if (message.getRepeatUntil() != null && now.isAfter(message.getRepeatUntil())) {
            message.setStatus(ScheduledStatus.EXPIRED);
            repository.save(message);
            return;
        }

        ContentFilterService.Result result = contentFilter.apply(reminderText(message));
        if (result.blocked()) {
            markFailed(message, "敏感词拦截：" + result.note());
            return;
        }

        message.setOutboxId(enqueue(message, List.of(result.content()), now));
        message.setSentAt(now);
        message.setSentCount(message.getSentCount() + 1);
        message.setLastError(null);

        int maxTimes = clampMaxTimes(message.getMaxTimes());
        boolean exhausted = message.getSentCount() >= maxTimes;
        if (exhausted && reminderProperties.isFinalMessage() && hasText(message.getFinalMessage())) {
            ContentFilterService.Result closing = contentFilter.apply(message.getFinalMessage().trim());
            if (!closing.blocked()) {
                message.setOutboxId(enqueue(message, List.of(closing.content()), now));
            }
        }
        if (exhausted) {
            FlowTraceService.mark(FlowTraceService.Stage.REMIND, message.getChatId(), message.getChatName(),
                    "提醒「" + safeTitle(message) + "」已催满 " + maxTimes + " 次，作罢", null);
            finishOrReschedule(message, ScheduledStatus.EXPIRED, now);
        } else {
            message.setScheduledAt(now.plusMinutes(clampInterval(message.getIntervalMinutes())));
            message.setStatus(ScheduledStatus.PENDING);
            FlowTraceService.mark(FlowTraceService.Stage.REMIND, message.getChatId(), message.getChatName(),
                    "提醒「" + safeTitle(message) + "」第 " + message.getSentCount() + "/" + maxTimes
                            + " 次，等对方回话", message.getContent());
        }
        repository.save(message);
    }

    /**
     * 这一轮结束了：设了每天/每周重复的，就排到下一次重新开始数次数；否则落到终态。
     *
     * <p>「每天 18:00 提醒我打卡，没回就 5 分钟催一次」= 每天一轮，每轮最多 6 次。</p>
     */
    private void finishOrReschedule(ScheduledMessage message, ScheduledStatus terminal, OffsetDateTime now) {
        ScheduledRepeat repeat = message.getRepeatRule() == null ? ScheduledRepeat.NONE : message.getRepeatRule();
        if (repeat != ScheduledRepeat.NONE) {
            OffsetDateTime next = nextOccurrence(message, now);
            if (next != null) {
                message.setScheduledAt(next);
                message.setSentCount(0);
                message.setStatus(ScheduledStatus.PENDING);
                return;
            }
        }
        message.setStatus(terminal);
    }

    /** 本次该说哪句：人设生成的多句轮着用，同一句复读六遍太假。 */
    static String reminderText(ScheduledMessage message) {
        List<String> variants = variants(message);
        if (variants.isEmpty()) {
            String fallback = hasText(message.getContent())
                    ? message.getContent().trim()
                    : "该" + safeTitle(message) + "了";
            return fallback + "（回「" + firstStopWord(message) + "」我就不催了）";
        }
        int index = Math.min(Math.max(0, message.getSentCount()), variants.size() - 1);
        return variants.get(index);
    }

    static List<String> variants(ScheduledMessage message) {
        if (!hasText(message.getVariants())) {
            return List.of();
        }
        return message.getVariants().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static String firstStopWord(ScheduledMessage message) {
        String words = message.getStopWords();
        if (!hasText(words)) {
            return "好了";
        }
        String first = words.split("[,，;；]")[0].trim();
        return first.isEmpty() ? "好了" : first;
    }

    private static String safeTitle(ScheduledMessage message) {
        return hasText(message.getTitle()) ? message.getTitle().trim() : "提醒";
    }

    /** 真正写 outbox：所有任务共用一条投递链路，返回最后一条的 id（便于回溯）。 */
    private UUID enqueue(ScheduledMessage message, List<String> parts, OffsetDateTime now) {
        UUID last = null;
        for (String part : parts) {
            OutboxMessage outbox = new OutboxMessage();
            outbox.setBotId(message.getBotId());
            outbox.setConversationId(message.getConversationId());
            outbox.setChatId(message.getChatId());
            outbox.setContent(part);
            outbox.setStatus(OutboxStatus.PENDING);
            outbox.setNextAttemptAt(now);
            last = outboxRepository.save(outbox).getId();
        }
        return last;
    }

    /**
     * 下一次发送时间。
     *
     * <p><b>从锚点算，不从 {@code scheduledAt} 算。</b>催命循环会把时间推成
     * 「18:00、18:05、18:10…」，拿最后那次加一天就成了明天 18:10、后天 18:15，
     * 越催越晚——用户说的「每天六点」被悄悄改掉了。锚点记的是「本来该在的钟点」，
     * 所以对方回话之后下一轮永远回到 18:00。</p>
     *
     * <p>停机或配额延迟可能让锚点落后好几轮，这里直接跳到未来最近的一次，
     * 不会补发一串过期的消息。</p>
     */
    private static OffsetDateTime nextOccurrence(ScheduledMessage message, OffsetDateTime now) {
        ScheduledRepeat repeat = message.getRepeatRule() == null ? ScheduledRepeat.NONE : message.getRepeatRule();
        OffsetDateTime current = message.getAnchorAt() != null ? message.getAnchorAt() : message.getScheduledAt();
        if (repeat == ScheduledRepeat.NONE || current == null) {
            return null;
        }
        OffsetDateTime next = repeat == ScheduledRepeat.CRON
                ? nextCron(message.getCronExpression(), current)
                : repeat.next(current);
        int guard = 0;
        while (next != null && !next.isAfter(now) && guard++ < 1000) {
            next = repeat == ScheduledRepeat.CRON
                    ? nextCron(message.getCronExpression(), next)
                    : repeat.next(next);
        }
        OffsetDateTime repeatUntil = message.getRepeatUntil();
        if (next != null && repeatUntil != null && next.isAfter(repeatUntil)) {
            return null;
        }
        return next;
    }

    /**
     * cron 的下一次触发时间；表达式非法时返回 null（保存时已经校验过，这里只防御历史数据）。
     *
     * <p><b>cron 必须按业务时区算，不能按入参自带的 offset 算。</b>
     * {@code timestamptz} 读出来是 UTC（+00:00），如果照着这个 offset 去问 cron「下一次 18 点」，
     * 得到的是 UTC 18 点，也就是北京时间第二天凌晨 2 点——线上真的这么错过一次：
     * 「0 18 * * 1-6 下班打卡」第一次 18:01 发对了，之后每轮都跑到凌晨 2 点。</p>
     */
    static OffsetDateTime nextCron(String expression, OffsetDateTime after) {
        CronExpression cron = parseCronOrNull(expression);
        if (cron == null || after == null) {
            return null;
        }
        java.time.ZonedDateTime next = cron.next(after.toInstant().atZone(ZONE));
        return next == null ? null : next.toOffsetDateTime();
    }

    /**
     * 只改「几点几分」，日期和星期几原样保留：{@code 0 18 * * 1-6} + 17:30 → {@code 30 17 * * 1-6}。
     *
     * <p>为什么需要：cron 任务改时间时如果只挪 {@code scheduledAt}，这一轮按新时间发了，
     * 下一轮又被表达式拉回老时间——用户会以为「改了只生效一次」。</p>
     */
    static String retimeCron(String expression, OffsetDateTime at) {
        if (expression == null || expression.isBlank() || at == null) {
            return null;
        }
        String[] parts = expression.trim().replaceAll("\\s+", " ").split(" ");
        java.time.ZonedDateTime local = at.atZoneSameInstant(ZONE);
        int minute = local.getMinute();
        int hour = local.getHour();
        if (parts.length == 5) {
            return "0 " + minute + " " + hour + " " + parts[2] + " " + parts[3] + " " + parts[4];
        }
        if (parts.length == 6) {
            return "0 " + minute + " " + hour + " " + parts[3] + " " + parts[4] + " " + parts[5];
        }
        return null;
    }

    /**
     * 解析 cron 表达式，兼容两种写法：
     * 5 段「分 时 日 月 周」（例如 {@code 0 9 * * 1-5}）会自动补上秒位。
     */
    static CronExpression parseCronOrNull(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String normalized = normalizeCron(expression);
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String normalizeCron(String expression) {
        String trimmed = expression.trim().replaceAll("\\s+", " ");
        int fields = trimmed.split(" ").length;
        return fields == 5 ? "0 " + trimmed : trimmed;
    }

    private static CronExpression requireCron(String expression) {
        if (expression == null || expression.isBlank()) {
            throw ApiException.badRequest("请填写 cron 表达式，例如 0 9 * * 1-5（工作日 9 点）");
        }
        CronExpression cron = parseCronOrNull(expression);
        if (cron == null) {
            throw ApiException.badRequest("cron 表达式看不懂：" + expression
                    + "，格式是「分 时 日 月 周」，例如 0 9 * * 1-5");
        }
        return cron;
    }

    /** 分条发送：按行拆，空行丢弃，最多 {@link #MAX_PARTS} 条。 */
    static List<String> split(String content, boolean splitLines) {
        if (content == null) {
            return List.of();
        }
        if (!splitLines) {
            String trimmed = content.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .limit(MAX_PARTS)
                .toList();
    }

    private void apply(ScheduledMessage message, UUID botId, String chatId, String chatName, String content,
                       OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                       OffsetDateTime repeatUntil, boolean splitLines, String cronExpression) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要发送的会话");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("消息内容不能为空");
        }
        ScheduledRepeat repeat = repeatRule == null ? ScheduledRepeat.NONE : repeatRule;
        String cron = cronExpression == null ? null : cronExpression.trim();
        OffsetDateTime effectiveAt = scheduledAt;
        if (repeat == ScheduledRepeat.CRON) {
            CronExpression parsed = requireCron(cron);
            // 只给 cron 不给时间时，从「现在之后的下一次」开始；给了过去的时间也自动顺延。
            // 这里同样按业务时区算，否则 JVM 跑在 UTC 的机器上会整体偏 8 小时。
            if (effectiveAt == null || effectiveAt.isBefore(OffsetDateTime.now().minusMinutes(1))) {
                java.time.ZonedDateTime start = parsed.next(java.time.Instant.now().atZone(ZONE));
                if (start == null) {
                    throw ApiException.badRequest("这个 cron 永远不会触发：" + cron);
                }
                effectiveAt = start.toOffsetDateTime();
            }
        } else {
            cron = null;
        }
        if (effectiveAt == null) {
            throw ApiException.badRequest("请选择发送时间");
        }
        if (effectiveAt.isBefore(OffsetDateTime.now().minusMinutes(1))) {
            throw ApiException.badRequest("发送时间不能早于当前时间");
        }
        if (repeatUntil != null && repeatUntil.isBefore(effectiveAt)) {
            throw ApiException.badRequest("重复截止时间不能早于发送时间");
        }

        BotConfig bot = requireBot(botId);
        String normalizedChatId = chatId.trim();
        Conversation conversation = conversationRepository
                .findByBotIdAndChatId(bot.getId(), normalizedChatId).orElse(null);

        // 时间没动（只改了内容 / 接收人）就保留原来的锚点：否则在管理端点一次保存，
        // 会把「每天 18:00」重锚成当前这一次的 18:10
        OffsetDateTime previous = message.getScheduledAt();
        boolean timeChanged = previous == null || !previous.toInstant().equals(effectiveAt.toInstant());

        message.setKind(ScheduledKind.MESSAGE);
        message.setBotId(bot.getId());
        message.setConversationId(conversation == null ? null : conversation.getId());
        message.setChatId(normalizedChatId);
        message.setChatName(chatName == null || chatName.isBlank()
                ? (conversation == null ? normalizedChatId : conversation.getChatName())
                : chatName.trim());
        message.setContent(content.trim());
        message.setScheduledAt(effectiveAt);
        message.setRepeatRule(repeat);
        message.setAnchorAt(repeat == ScheduledRepeat.NONE ? null
                : (timeChanged || message.getAnchorAt() == null ? effectiveAt : message.getAnchorAt()));
        message.setRepeatUntil(repeatUntil);
        message.setSplitLines(splitLines);
        message.setCronExpression(cron);
    }

    private BotConfig requireBot(UUID botId) {
        return botId == null
                ? botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()
                    .orElseThrow(() -> ApiException.badRequest("没有可用的机器人配置"))
                : botRepository.findById(botId).orElseThrow(() -> ApiException.badRequest("机器人不存在"));
    }

    private void markFailed(ScheduledMessage message, String reason) {
        message.setStatus(ScheduledStatus.FAILED);
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(abbreviate(reason, 1000));
        repository.save(message);
    }

    private int clampInterval(Integer minutes) {
        int floor = Math.max(1, reminderProperties.getMinIntervalMinutes());
        int value = minutes == null || minutes <= 0 ? reminderProperties.getDefaultIntervalMinutes() : minutes;
        return Math.min(Math.max(value, floor), MAX_INTERVAL_MINUTES);
    }

    private int clampMaxTimes(Integer times) {
        int hard = Math.max(1, reminderProperties.getHardMaxTimes());
        int value = times == null || times <= 0 ? reminderProperties.getDefaultMaxTimes() : times;
        return Math.min(Math.max(value, 1), hard);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
