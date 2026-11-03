package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.ScheduledMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 定时消息：到点后转成 outbox 记录，交给既有的投递器发送。
 *
 * <p>不直接调用微信接口，是为了沿用同一套重试、租约与伪装节奏；
 * 定时消息只负责「什么时候该发」，不负责「怎么发」。</p>
 *
 * <p>支持三种「一次配置长期生效」的写法：每天 / 每周重复、内容按行拆成多条发送。
 * 重复消息发完不置为 SENT，而是把时间往后推一个周期继续排队，管理端能看到下一次发送时间。</p>
 */
@Service
public class ScheduledMessageService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageService.class);
    private static final int BATCH = 20;
    /** 分条发送的条数上限，避免一次填几十行把群刷屏。 */
    private static final int MAX_PARTS = 10;

    private final ScheduledMessageRepository repository;
    private final OutboxMessageRepository outboxRepository;
    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botRepository;
    private final ContentFilterService contentFilter;

    public ScheduledMessageService(ScheduledMessageRepository repository,
                                   OutboxMessageRepository outboxRepository,
                                   ConversationRepository conversationRepository,
                                   BotConfigRepository botRepository,
                                   ContentFilterService contentFilter) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.conversationRepository = conversationRepository;
        this.botRepository = botRepository;
        this.contentFilter = contentFilter;
    }

    public Page<ScheduledMessage> list(int page, int size) {
        return repository.findAllByOrderByScheduledAtDesc(
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    public long pendingCount() {
        return repository.countByStatus(ScheduledStatus.PENDING);
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
        ScheduledMessage message = new ScheduledMessage();
        apply(message, botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines);
        message.setStatus(ScheduledStatus.PENDING);
        return repository.save(message);
    }

    /** 编辑待发送的定时消息：时间、接收人、内容、重复规则都可以改。 */
    @Transactional
    public ScheduledMessage update(UUID id, UUID botId, String chatId, String chatName, String content,
                                   OffsetDateTime scheduledAt, ScheduledRepeat repeatRule,
                                   OffsetDateTime repeatUntil, boolean splitLines) {
        ScheduledMessage message = get(id);
        if (message.getStatus() != ScheduledStatus.PENDING) {
            throw ApiException.badRequest("只有待发送的消息可以编辑");
        }
        apply(message, botId, chatId, chatName, content, scheduledAt, repeatRule, repeatUntil, splitLines);
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

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    /** 每 20 秒扫一次到期消息，转成 outbox 交给投递器。 */
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
        UUID lastOutboxId = null;
        for (String part : filtered) {
            OutboxMessage outbox = new OutboxMessage();
            outbox.setBotId(message.getBotId());
            outbox.setConversationId(message.getConversationId());
            outbox.setChatId(message.getChatId());
            outbox.setContent(part);
            outbox.setStatus(OutboxStatus.PENDING);
            outbox.setNextAttemptAt(now);
            lastOutboxId = outboxRepository.save(outbox).getId();
        }

        message.setOutboxId(lastOutboxId);
        message.setSentAt(now);
        message.setSentCount(message.getSentCount() + 1);
        message.setLastError(null);

        ScheduledRepeat repeat = message.getRepeatRule() == null ? ScheduledRepeat.NONE : message.getRepeatRule();
        OffsetDateTime next = nextOccurrence(message.getScheduledAt(), repeat, message.getRepeatUntil(), now);
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
     * 下一次发送时间。
     *
     * <p>停机或配额延迟可能让 scheduledAt 落后好几轮，这里直接跳到未来最近的一次，
     * 不会补发一串过期的消息。</p>
     */
    private static OffsetDateTime nextOccurrence(OffsetDateTime current, ScheduledRepeat repeat,
                                                 OffsetDateTime repeatUntil, OffsetDateTime now) {
        if (repeat == null || repeat == ScheduledRepeat.NONE || current == null) {
            return null;
        }
        OffsetDateTime next = repeat.next(current);
        while (next != null && !next.isAfter(now)) {
            next = repeat.next(next);
        }
        if (next != null && repeatUntil != null && next.isAfter(repeatUntil)) {
            return null;
        }
        return next;
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
                       OffsetDateTime repeatUntil, boolean splitLines) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要发送的会话");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("消息内容不能为空");
        }
        if (scheduledAt == null) {
            throw ApiException.badRequest("请选择发送时间");
        }
        if (scheduledAt.isBefore(OffsetDateTime.now().minusMinutes(1))) {
            throw ApiException.badRequest("发送时间不能早于当前时间");
        }
        if (repeatUntil != null && repeatUntil.isBefore(scheduledAt)) {
            throw ApiException.badRequest("重复截止时间不能早于发送时间");
        }

        BotConfig bot = botId == null
                ? botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()
                    .orElseThrow(() -> ApiException.badRequest("没有可用的机器人配置"))
                : botRepository.findById(botId).orElseThrow(() -> ApiException.badRequest("机器人不存在"));

        String normalizedChatId = chatId.trim();
        Conversation conversation = conversationRepository
                .findByBotIdAndChatId(bot.getId(), normalizedChatId).orElse(null);

        message.setBotId(bot.getId());
        message.setConversationId(conversation == null ? null : conversation.getId());
        message.setChatId(normalizedChatId);
        message.setChatName(chatName == null || chatName.isBlank()
                ? (conversation == null ? normalizedChatId : conversation.getChatName())
                : chatName.trim());
        message.setContent(content.trim());
        message.setScheduledAt(scheduledAt);
        message.setRepeatRule(repeatRule == null ? ScheduledRepeat.NONE : repeatRule);
        message.setRepeatUntil(repeatUntil);
        message.setSplitLines(splitLines);
    }

    private void markFailed(ScheduledMessage message, String reason) {
        message.setStatus(ScheduledStatus.FAILED);
        message.setAttempts(message.getAttempts() + 1);
        message.setLastError(abbreviate(reason, 1000));
        repository.save(message);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
