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
    private final RuntimeSettingsService settings;

    public InboxService(BotConfigRepository botRepository,
                        ConversationRepository conversationRepository,
                        MessageLogRepository messageRepository,
                        DedupeService dedupeService,
                        RateLimitService rateLimitService,
                        ChatOrchestrator chatOrchestrator,
                        RuntimeSettingsService settings) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.dedupeService = dedupeService;
        this.rateLimitService = rateLimitService;
        this.chatOrchestrator = chatOrchestrator;
        this.settings = settings;
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
        MediaKind mediaKind = mediaKindOf(event.mediaKind());
        boolean media = mediaKind != null;
        if (media && !settings.mediaEnabled()) {
            return new AgentDtos.EventAccepted("IGNORED", "媒体消息处理未启用", null);
        }
        if (!media && content.isBlank()) {
            return new AgentDtos.EventAccepted("IGNORED", "空消息内容", null);
        }
        if (event.chatType() == ChatType.PRIVATE && !bot.isReplyPrivate()) {
            return new AgentDtos.EventAccepted("IGNORED", "私聊回复未启用", null);
        }
        boolean wakeMatched = true;
        if (event.chatType() == ChatType.GROUP) {
            if (!bot.isReplyGroup()) {
                return new AgentDtos.EventAccepted("IGNORED", "群聊回复未启用", null);
            }
            wakeMatched = matchesWakeWord(bot.getGroupWakeWords(), content);
            // 文本消息没唤醒就直接忽略；媒体消息没有文字，改为记录后按策略决定是否回复
            if (!wakeMatched && !media) {
                return new AgentDtos.EventAccepted("IGNORED", "未命中群聊唤醒词", null);
            }
        }
        String mediaMode = settings.mediaReplyMode();
        boolean shouldReply = media ? (wakeMatched && !"ignore".equals(mediaMode)) : wakeMatched;

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
        if (media) {
            inbound.setMediaKind(mediaKind);
            inbound.setMediaName(event.mediaName());
            inbound.setMediaSize(event.mediaSize());
            inbound.setMediaUrl(event.mediaUrl());
            inbound.setRawType(event.rawType());
        }
        inbound = messageRepository.save(inbound);

        if (conversation.isBotPaused()) {
            inbound.setStatus(MessageStatus.IGNORED);
            inbound.setErrorMessage("人工接管中，未自动回复");
            messageRepository.save(inbound);
            return new AgentDtos.EventAccepted("PAUSED", "人工接管中，未自动回复", conversation.getId());
        }

        if (!shouldReply) {
            inbound.setStatus(MessageStatus.IGNORED);
            inbound.setErrorMessage(media
                    ? "媒体消息按策略仅记录（replyMode=" + mediaMode + "）"
                    : "未命中群聊唤醒词");
            messageRepository.save(inbound);
            return new AgentDtos.EventAccepted("IGNORED", inbound.getErrorMessage(), conversation.getId());
        }

        final var botId = bot.getId();
        final var conversationId = conversation.getId();
        final var inboundId = inbound.getId();
        final boolean ackReply = media && "ack".equals(mediaMode);
        final String ackText = ackReply ? settings.mediaAckText() : null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (ackReply) {
                        chatOrchestrator.generateFixedReplyAsync(botId, conversationId, inboundId, ackText);
                    } else {
                        chatOrchestrator.generateReplyAsync(botId, conversationId, inboundId);
                    }
                }
            });
        } else if (ackReply) {
            chatOrchestrator.generateFixedReplyAsync(botId, conversationId, inboundId, ackText);
        } else {
            chatOrchestrator.generateReplyAsync(botId, conversationId, inboundId);
        }
        return new AgentDtos.EventAccepted("ACCEPTED", "消息已接收，正在生成回复", conversationId);
    }

    private static MediaKind mediaKindOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MediaKind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MediaKind.OTHER;
        }
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
