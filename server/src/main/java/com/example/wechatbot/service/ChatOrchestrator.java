package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.ai.SearchAugmenter;
import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private final SearchAugmenter searchAugmenter;
    private final DisguiseService disguise;
    private final DisguiseProperties disguiseProperties;
    private final AutoReplyService autoReply;
    private final ContentFilterService contentFilter;
    private final PromptTemplateRepository promptTemplateRepository;

    public ChatOrchestrator(BotConfigRepository botRepository,
                            ConversationRepository conversationRepository,
                            MessageLogRepository messageRepository,
                            OutboxMessageRepository outboxRepository,
                            LlmGateway llmGateway,
                            SearchAugmenter searchAugmenter,
                            DisguiseService disguise,
                            DisguiseProperties disguiseProperties,
                            AutoReplyService autoReply,
                            ContentFilterService contentFilter,
                            PromptTemplateRepository promptTemplateRepository) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.llmGateway = llmGateway;
        this.searchAugmenter = searchAugmenter;
        this.disguise = disguise;
        this.disguiseProperties = disguiseProperties;
        this.autoReply = autoReply;
        this.contentFilter = contentFilter;
        this.promptTemplateRepository = promptTemplateRepository;
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
            LlmReply reply = autoReply.match(conversation.getChatType(), inbound.getContent())
                    .map(rule -> {
                        log.info("关键词规则命中，跳过模型: conversation={} rule={}", conversationId, rule.getName());
                        return LlmReply.rule(rule.getReply());
                    })
                    .orElse(null);

            if (reply == null) {
                List<ChatMessage> context = buildContext(bot, conversation, inbound);
                reply = llmGateway.chat(bot, context);
                if (enqueueOutbox && disguiseProperties.isRegenerateOnRepeat()
                        && disguise.isRepetitive(conversation.getChatId(), reply.content())) {
                    reply = regenerateWithVariation(bot, context, reply);
                }
            }

            ContentFilterService.Result filtered = contentFilter.apply(reply.content());
            if (filtered.blocked()) {
                inbound.setStatus(MessageStatus.SUCCESS);
                inbound.setErrorMessage(null);
                messageRepository.save(inbound);

                MessageLog blocked = outboundLog(conversationId, bot, reply, reply.content());
                blocked.setStatus(MessageStatus.IGNORED);
                blocked.setErrorMessage(abbreviate(filtered.note(), 1000));
                messageRepository.save(blocked);
                conversation.setLastMessage(abbreviate(reply.content(), 500));
                conversation.setLastMessageAt(OffsetDateTime.now());
                conversationRepository.save(conversation);
                log.info("敏感词拦截，未发送: conversation={} note={}", conversationId, filtered.note());
                return reply;
            }
            if (filtered.content() != null && !filtered.content().equals(reply.content())) {
                log.info("敏感词改写: conversation={} note={}", conversationId, filtered.note());
                reply = reply.withContent(filtered.content());
            }

            inbound.setStatus(MessageStatus.SUCCESS);
            inbound.setErrorMessage(null);
            messageRepository.save(inbound);

            DisguiseService.Plan plan = enqueueOutbox
                    ? disguise.plan(conversation.getChatId(), conversation.getChatType(), reply.content(), Instant.now())
                    : disguise.preview(conversation.getChatId(), conversation.getChatType(), reply.content(), Instant.now());

            if (plan.dropped() && enqueueOutbox) {
                MessageLog skipped = outboundLog(conversationId, bot, reply, reply.content());
                skipped.setStatus(MessageStatus.IGNORED);
                skipped.setErrorMessage(abbreviate("伪装层未发送：" + plan.decision() + " " + plan.note(), 1000));
                messageRepository.save(skipped);
                log.info("伪装层未发送，conversation={} decision={} note={}",
                        conversationId, plan.decision(), plan.note());
                conversation.setLastMessage(abbreviate(reply.content(), 500));
                conversation.setLastMessageAt(OffsetDateTime.now());
                conversationRepository.save(conversation);
                return reply;
            }

            List<DisguiseService.PlannedPart> parts = plan.parts();
            for (DisguiseService.PlannedPart part : parts) {
                MessageLog outbound = outboundLog(conversationId, bot, reply, part.text());
                messageRepository.save(outbound);

                if (enqueueOutbox) {
                    OutboxMessage outbox = new OutboxMessage();
                    outbox.setBotId(botId);
                    outbox.setConversationId(conversationId);
                    outbox.setChatId(conversation.getChatId());
                    outbox.setContent(part.text());
                    outbox.setStatus(OutboxStatus.PENDING);
                    outbox.setNextAttemptAt(OffsetDateTime.ofInstant(part.sendAt(), zone()));
                    outboxRepository.save(outbox);
                }
            }

            String joined = plan.dropped() ? reply.content() : plan.joinedText();
            conversation.setLastMessage(abbreviate(joined, 500));
            conversation.setLastMessageAt(OffsetDateTime.now());
            conversationRepository.save(conversation);

            if (!plan.dropped()) {
                log.info("伪装层排期，conversation={} parts={} first={} note={}",
                        conversationId, parts.size(), plan.firstSendAt(), plan.note());
            }
            return plan.dropped() ? reply : withContent(reply, joined);
        } catch (Exception ex) {
            inbound.setStatus(MessageStatus.FAILED);
            inbound.setErrorMessage(abbreviate(rootMessage(ex), 1000));
            messageRepository.save(inbound);
            throw ex;
        }
    }

    private MessageLog outboundLog(UUID conversationId, BotConfig bot, LlmReply reply, String content) {
        MessageLog outbound = new MessageLog();
        outbound.setConversationId(conversationId);
        outbound.setDirection(MessageDirection.OUTBOUND);
        outbound.setSenderName(bot.getName());
        outbound.setContent(content);
        outbound.setProviderName(reply.providerName());
        outbound.setModel(reply.model());
        outbound.setStatus(MessageStatus.SUCCESS);
        outbound.setPromptTokens(reply.promptTokens());
        outbound.setCompletionTokens(reply.completionTokens());
        outbound.setLatencyMs(reply.latencyMs());
        return outbound;
    }

    private LlmReply regenerateWithVariation(BotConfig bot, List<ChatMessage> context, LlmReply previous) {
        try {
            List<ChatMessage> retry = new ArrayList<>(context);
            retry.add(ChatMessage.assistant(previous.content()));
            retry.add(ChatMessage.system("刚才那句和之前的回复太像了。换一种说法重答：更短、更口语，不要重复上一句的句式和用词。"));
            log.info("回复与历史高度相似，触发换说法重答");
            return llmGateway.chat(bot, retry);
        } catch (RuntimeException ex) {
            log.warn("换说法重答失败，沿用原回复: {}", ex.getMessage());
            return previous;
        }
    }

    private LlmReply withContent(LlmReply reply, String content) {
        return new LlmReply(reply.providerId(), reply.providerName(), reply.model(), content,
                reply.promptTokens(), reply.completionTokens(), reply.latencyMs());
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

        String style = disguise.promptSuffix();
        String searchContext = searchAugmenter.buildContext(bot, currentInbound.getContent());
        String systemPrompt = resolveSystemPrompt(bot, conversation);

        List<ChatMessage> withStyle = new ArrayList<>(messages.size() + 3);
        withStyle.add(ChatMessage.system(systemPrompt));
        if (!style.isBlank()) {
            withStyle.add(ChatMessage.system(style));
        }
        if (searchContext != null && !searchContext.isBlank()) {
            withStyle.add(ChatMessage.system(searchContext));
        }
        withStyle.addAll(messages);
        return List.copyOf(withStyle);
    }

    /** 会话绑定了提示词模板时优先使用模板，否则回退到机器人默认提示词。 */
    private String resolveSystemPrompt(BotConfig bot, Conversation conversation) {
        if (conversation.getPromptTemplateId() != null) {
            String template = promptTemplateRepository.findById(conversation.getPromptTemplateId())
                    .map(PromptTemplate::getContent)
                    .orElse(null);
            if (template != null && !template.isBlank()) {
                return template;
            }
        }
        return bot.getSystemPrompt();
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(disguiseProperties.getZone());
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
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
