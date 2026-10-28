package com.example.wechatbot.service;

import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AgentDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Arrays;

@Service
public class InboxService {
    private final BotConfigRepository botRepository;
    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final DedupeService dedupeService;
    private final RateLimitService rateLimitService;
    private final ChatOrchestrator chatOrchestrator;

    public InboxService(BotConfigRepository botRepository,
                        ConversationRepository conversationRepository,
                        MessageLogRepository messageRepository,
                        DedupeService dedupeService,
                        RateLimitService rateLimitService,
                        ChatOrchestrator chatOrchestrator) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.dedupeService = dedupeService;
        this.rateLimitService = rateLimitService;
        this.chatOrchestrator = chatOrchestrator;
    }

    @Transactional
    public AgentDtos.EventAccepted accept(AgentDtos.AgentEventRequest event) {
        String type = event.type() == null || event.type().isBlank() ? "message" : event.type();
        if (!"message".equalsIgnoreCase(type)) {
            return new AgentDtos.EventAccepted("IGNORED", "暂不处理该事件类型", null);
        }
        if (event.fromSelf()) {
            return new AgentDtos.EventAccepted("IGNORED", "忽略机器人自身消息", null);
        }

        BotConfig bot = event.botId() == null
                ? botRepository.findFirstByEnabledTrueOrderByCreatedAtAsc().orElse(null)
                : botRepository.findById(event.botId()).orElse(null);
        if (bot == null || !bot.isEnabled()) {
            return new AgentDtos.EventAccepted("IGNORED", "机器人不存在或未启用", null);
        }

        String content = event.content().trim();
        if (event.chatType() == ChatType.PRIVATE && !bot.isReplyPrivate()) {
            return new AgentDtos.EventAccepted("IGNORED", "私聊回复未启用", null);
        }
        if (event.chatType() == ChatType.GROUP) {
            if (!bot.isReplyGroup()) {
                return new AgentDtos.EventAccepted("IGNORED", "群聊回复未启用", null);
            }
            if (!matchesWakeWord(bot.getGroupWakeWords(), content)) {
                return new AgentDtos.EventAccepted("IGNORED", "未命中群聊唤醒词", null);
            }
        }

        if (!dedupeService.firstSeen(event.eventId())) {
            return new AgentDtos.EventAccepted("DUPLICATE", "重复事件已忽略", null);
        }
        if (!rateLimitService.allow(bot.getId().toString(), event.chatId(), bot.getRateLimitPerMinute())) {
            return new AgentDtos.EventAccepted("RATE_LIMITED", "该会话触发频率限制", null);
        }

        Conversation conversation = conversationRepository.findByBotIdAndChatId(bot.getId(), event.chatId())
                .orElseGet(() -> {
                    Conversation created = new Conversation();
                    created.setBotId(bot.getId());
                    created.setChatId(event.chatId());
                    created.setChatType(event.chatType());
                    return created;
                });
        conversation.setChatType(event.chatType());
        if (event.chatName() != null && !event.chatName().isBlank()) {
            conversation.setChatName(event.chatName().trim());
        } else if (conversation.getChatName() == null || conversation.getChatName().isBlank()) {
            conversation.setChatName(event.chatType() == ChatType.GROUP ? event.chatId() : safeSenderName(event));
        }
        conversation.setLastMessage(abbreviate(content, 500));
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversation = conversationRepository.save(conversation);

        MessageLog inbound = new MessageLog();
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderId(event.senderId());
        inbound.setSenderName(safeSenderName(event));
        inbound.setContent(content);
        inbound.setStatus(MessageStatus.RECEIVED);
        inbound = messageRepository.save(inbound);

        final var botId = bot.getId();
        final var conversationId = conversation.getId();
        final var inboundId = inbound.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    chatOrchestrator.generateReplyAsync(botId, conversationId, inboundId);
                }
            });
        } else {
            chatOrchestrator.generateReplyAsync(botId, conversationId, inboundId);
        }
        return new AgentDtos.EventAccepted("ACCEPTED", "消息已接收，正在生成回复", conversationId);
    }

    public boolean matchesWakeWord(String configured, String content) {
        if (configured == null || configured.isBlank()) {
            return true;
        }
        return Arrays.stream(configured.split("[,，\\n]"))
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .anyMatch(content::contains);
    }

    private String safeSenderName(AgentDtos.AgentEventRequest event) {
        return event.senderName() == null || event.senderName().isBlank()
                ? event.chatId()
                : event.senderName().trim();
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
