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
import java.util.List;
import java.util.UUID;

/**
 * 定时消息：到点后转成 outbox 记录，交给既有的投递器发送。
 *
 * <p>不直接调用微信接口，是为了沿用同一套重试、租约与伪装节奏；
 * 定时消息只负责“什么时候该发”，不负责“怎么发”。</p>
 */
@Service
public class ScheduledMessageService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageService.class);
    private static final int BATCH = 20;

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

    @Transactional
    public ScheduledMessage create(UUID botId, String chatId, String chatName, String content, OffsetDateTime scheduledAt) {
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

        BotConfig bot = botId == null
                ? botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()
                    .orElseThrow(() -> ApiException.badRequest("没有可用的机器人配置"))
                : botRepository.findById(botId).orElseThrow(() -> ApiException.badRequest("机器人不存在"));

        String normalizedChatId = chatId.trim();
        Conversation conversation = conversationRepository
                .findByBotIdAndChatId(bot.getId(), normalizedChatId).orElse(null);

        ScheduledMessage message = new ScheduledMessage();
        message.setBotId(bot.getId());
        message.setConversationId(conversation == null ? null : conversation.getId());
        message.setChatId(normalizedChatId);
        message.setChatName(chatName == null || chatName.isBlank()
                ? (conversation == null ? normalizedChatId : conversation.getChatName())
                : chatName.trim());
        message.setContent(content.trim());
        message.setScheduledAt(scheduledAt);
        message.setStatus(ScheduledStatus.PENDING);
        return repository.save(message);
    }

    @Transactional
    public ScheduledMessage cancel(UUID id) {
        ScheduledMessage message = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("定时消息不存在"));
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
        ContentFilterService.Result filtered = contentFilter.apply(message.getContent());
        if (filtered.blocked()) {
            message.setStatus(ScheduledStatus.FAILED);
            message.setAttempts(message.getAttempts() + 1);
            message.setLastError(abbreviate("敏感词拦截：" + filtered.note(), 1000));
            repository.save(message);
            return;
        }

        OutboxMessage outbox = new OutboxMessage();
        outbox.setBotId(message.getBotId());
        outbox.setConversationId(message.getConversationId());
        outbox.setChatId(message.getChatId());
        outbox.setContent(filtered.content());
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setNextAttemptAt(OffsetDateTime.now());
        outbox = outboxRepository.save(outbox);

        message.setOutboxId(outbox.getId());
        message.setStatus(ScheduledStatus.SENT);
        message.setSentAt(OffsetDateTime.now());
        message.setLastError(null);
        repository.save(message);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
