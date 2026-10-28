package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ChatOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final BotConfigRepository botRepository;
    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final OutboxMessageRepository outboxRepository;
    private final LlmGateway llmGateway;

    public ChatOrchestrator(BotConfigRepository botRepository,
                            ConversationRepository conversationRepository,
                            MessageLogRepository messageRepository,
                            OutboxMessageRepository outboxRepository,
                            LlmGateway llmGateway) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.llmGateway = llmGateway;
    }

    @Async("chatExecutor")
    public void generateReplyAsync(UUID botId, UUID conversationId, UUID inboundId) {
        try {
            generateReply(botId, conversationId, inboundId);
        } catch (Exception ex) {
            log.error("Async AI reply failed for conversation {}", conversationId, ex);
        }
    }

    public LlmReply generateReply(UUID botId, UUID conversationId, UUID inboundId) {
        return generateReply(botId, conversationId, inboundId, true);
    }

    public LlmReply generateReply(UUID botId, UUID conversationId, UUID inboundId, boolean enqueueOutbox) {
        BotConfig bot = botRepository.findById(botId)
                .orElseThrow(() -> new IllegalStateException("机器人配置不存在"));
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalStateException("会话不存在"));
        MessageLog inbound = messageRepository.findById(inboundId)
                .orElseThrow(() -> new IllegalStateException("入站消息不存在"));

        try {
            List<ChatMessage> context = buildContext(bot, conversation, inbound);
            LlmReply reply = llmGateway.chat(bot, context);

            inbound.setStatus(MessageStatus.SUCCESS);
            inbound.setErrorMessage(null);
            messageRepository.save(inbound);

            MessageLog outbound = new MessageLog();
            outbound.setConversationId(conversationId);
            outbound.setDirection(MessageDirection.OUTBOUND);
            outbound.setSenderName(bot.getName());
            outbound.setContent(reply.content());
            outbound.setProviderName(reply.providerName());
            outbound.setModel(reply.model());
            outbound.setStatus(MessageStatus.SUCCESS);
            outbound.setPromptTokens(reply.promptTokens());
            outbound.setCompletionTokens(reply.completionTokens());
            outbound.setLatencyMs(reply.latencyMs());
            messageRepository.save(outbound);

            if (enqueueOutbox) {
                OutboxMessage outbox = new OutboxMessage();
                outbox.setBotId(botId);
                outbox.setConversationId(conversationId);
                outbox.setChatId(conversation.getChatId());
                outbox.setContent(reply.content());
                outbox.setStatus(OutboxStatus.PENDING);
                outbox.setNextAttemptAt(OffsetDateTime.now());
                outboxRepository.save(outbox);
            }

            conversation.setLastMessage(abbreviate(reply.content(), 500));
            conversation.setLastMessageAt(OffsetDateTime.now());
            conversationRepository.save(conversation);
            return reply;
        } catch (Exception ex) {
            inbound.setStatus(MessageStatus.FAILED);
            inbound.setErrorMessage(abbreviate(rootMessage(ex), 1000));
            messageRepository.save(inbound);
            throw ex;
        }
    }

    private List<ChatMessage> buildContext(BotConfig bot, Conversation conversation, MessageLog currentInbound) {
        int size = Math.max(1, bot.getContextWindow());
        var pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<MessageLog> recent = new ArrayList<>(messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.getId(), pageable));
        Collections.reverse(recent);

        List<ChatMessage> messages = new ArrayList<>();
        boolean currentIncluded = false;
        for (MessageLog item : recent) {
            if (item.getContent() == null || item.getContent().isBlank()) {
                continue;
            }
            currentIncluded = currentIncluded || item.getId().equals(currentInbound.getId());
            if (item.getDirection() == MessageDirection.INBOUND) {
                String content = item.getContent();
                if (conversation.getChatType() == ChatType.GROUP && item.getSenderName() != null) {
                    content = "[" + item.getSenderName() + "] " + content;
                }
                messages.add(ChatMessage.user(content));
            } else if (item.getStatus() == MessageStatus.SUCCESS) {
                messages.add(ChatMessage.assistant(item.getContent()));
            }
        }

        if (!currentIncluded) {
            messages.add(ChatMessage.user(currentInbound.getContent()));
        }
        return ChatMessage.conversation(bot.getSystemPrompt(), messages);
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}


