package com.example.wechatbot.service;

import com.example.wechatbot.domain.*;
import com.example.wechatbot.flow.FlowTraceService;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.wechat.ChatIdentity;
import com.example.wechatbot.web.dto.AgentDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class InboxService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InboxService.class);

    private final BotConfigRepository botRepository;
    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final DedupeService dedupeService;
    private final RateLimitService rateLimitService;
    private final ChatOrchestrator chatOrchestrator;
    private final RuntimeSettingsService settings;
    private final ReplyPolicyService replyPolicies;
    private final ReplyDispatcher replyDispatcher;
    private final PersonaSwitchService personaSwitch;
    private final ReminderAckService reminderAck;
    private final ReminderIntentService reminderIntent;

    public InboxService(BotConfigRepository botRepository,
                        ConversationRepository conversationRepository,
                        MessageLogRepository messageRepository,
                        DedupeService dedupeService,
                        RateLimitService rateLimitService,
                        ChatOrchestrator chatOrchestrator,
                        RuntimeSettingsService settings,
                        ReplyPolicyService replyPolicies,
                        ReplyDispatcher replyDispatcher,
                        PersonaSwitchService personaSwitch,
                        ReminderAckService reminderAck,
                        ReminderIntentService reminderIntent) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.dedupeService = dedupeService;
        this.rateLimitService = rateLimitService;
        this.chatOrchestrator = chatOrchestrator;
        this.settings = settings;
        this.replyPolicies = replyPolicies;
        this.replyDispatcher = replyDispatcher;
        this.personaSwitch = personaSwitch;
        this.reminderAck = reminderAck;
        this.reminderIntent = reminderIntent;
    }

    @Transactional
    public AgentDtos.EventAccepted accept(AgentDtos.AgentEventRequest event) {
        return accept(event, HookExtras.EMPTY);
    }

    @Transactional
    public AgentDtos.EventAccepted accept(AgentDtos.AgentEventRequest event, HookExtras extras) {
        HookExtras hook = extras == null ? HookExtras.EMPTY : extras;
        String type = event.type() == null || event.type().isBlank() ? "message" : event.type();
        if (!"message".equalsIgnoreCase(type)) {
            return new AgentDtos.EventAccepted("IGNORED", "暂不处理该事件类型", null);
        }
        if (event.fromSelf()) {
            return new AgentDtos.EventAccepted("IGNORED", "忽略机器人自身消息", null);
        }
        // 最后一道防线：公众号 / 机器人 / 微信系统会话永远不自动回复（回调、轮询、Agent 接口都走这里）
        if (ChatIdentity.isNeverReply(event.chatId())) {
            return new AgentDtos.EventAccepted("IGNORED", ChatIdentity.rejectReason(event.chatId()), null);
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
        boolean triggered = true;
        String decisionNote = null;
        if (event.chatType() == ChatType.GROUP) {
            if (!bot.isReplyGroup()) {
                return new AgentDtos.EventAccepted("IGNORED", "群聊回复未启用", null);
            }
            // 每个群可以有自己的回复策略：主动回复 / 随机回复 / 关键词唤醒
            ReplyPolicyService.Effective policy = replyPolicies.forChat(event.chatId(), bot);
            boolean wakeMatched = matchesWakeWord(policy.wakeWords(), content);
            switch (policy.mode()) {
                case ALWAYS -> {
                    triggered = true;
                    decisionNote = "主动回复（每条群消息都参与）";
                }
                case RANDOM -> {
                    // 被叫到一定回；没叫到时按概率插话（人设指令同样免抽奖）
                    boolean personaCommand = !media && personaSwitch.enabled()
                            && PersonaSwitchService.isTrigger(content);
                    boolean hit = wakeMatched || personaCommand
                            || ThreadLocalRandom.current().nextDouble() < policy.randomRate();
                    triggered = hit;
                    decisionNote = hit
                            ? (wakeMatched ? "命中关键词唤醒"
                                : personaCommand ? "人设指令（免随机）"
                                : "随机命中（" + percent(policy.randomRate()) + "）")
                            : "随机未命中（" + percent(policy.randomRate()) + "）";
                }
                case WAKE -> {
                    triggered = wakeMatched;
                    decisionNote = wakeMatched ? "命中关键词唤醒" : "未命中群聊唤醒词";
                    // 人设指令免唤醒：用户想换人设时不该先被迫叫一声机器人
                    if (!triggered && !media && personaSwitch.enabled()
                            && PersonaSwitchService.isTrigger(content)) {
                        triggered = true;
                        decisionNote = "人设指令（免唤醒）";
                    }
                    // 文本消息没唤醒就直接忽略，不入库（媒体消息没有文字，仍记录后按策略决定）
                    if (!triggered && !media) {
                        return new AgentDtos.EventAccepted("IGNORED", decisionNote, null);
                    }
                }
                case HUMAN -> {
                    // 拟人模式：消息一律入库，要不要回交给模型判断（节流与判定在 ChatOrchestrator 里）
                    triggered = true;
                    decisionNote = "拟人模式：交给模型判断";
                }
            }
        }
        String mediaMode = settings.mediaReplyMode();
        boolean shouldReply = media ? (triggered && !"ignore".equals(mediaMode)) : triggered;

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
            inbound.setMediaMd5(hook.mediaMd5());
            inbound.setMediaAeskey(hook.mediaAeskey());
            inbound.setMediaThumbMd5(hook.mediaThumbMd5());
            inbound.setMediaThumbAeskey(hook.mediaThumbAeskey());
        }
        // 引用消息本质是文本，rawType 仍然记下来便于排查（49 = appmsg）
        inbound.setRawType(event.rawType());
        // 每条消息都记下微信服务器消息 ID：语音消息靠它在本地库里精确定位音频
        inbound.setSvrId(hook.svrId());
        if (hook.hasQuote()) {
            inbound.setQuoteAuthor(hook.quoteAuthor());
            inbound.setQuoteText(hook.quoteText());
            // 被引用的可能是图片：把定位本地文件要用的 md5 与它自己的发送时间一起存下来
            if (hook.hasQuotedImage()) {
                inbound.setQuoteMd5(hook.quoteMd5());
                inbound.setQuoteThumbMd5(hook.quoteThumbMd5());
                inbound.setQuoteMediaAt(hook.quoteAt());
            }
        }
        inbound = messageRepository.save(inbound);
        FlowTraceService.mark(FlowTraceService.Stage.RECEIVED, conversation.getChatId(),
                conversation.getChatName(),
                (conversation.getChatType() == ChatType.GROUP ? "群里" : "私聊")
                        + (shouldReply ? "的新消息，准备回" : "的新消息（按策略不回）"),
                content);

        if (conversation.isBotPaused()) {
            inbound.setStatus(MessageStatus.IGNORED);
            inbound.setErrorMessage("人工接管中，未自动回复");
            messageRepository.save(inbound);
            FlowTraceService.mark(FlowTraceService.Stage.SKIP, conversation.getChatId(),
                    conversation.getChatName(), "人工接管中，不自动回复", content);
            return new AgentDtos.EventAccepted("PAUSED", "人工接管中，未自动回复", conversation.getId());
        }

        // 提醒任务：先看是不是「回执」（回话了就把等着的那条收掉），再看是不是在建/停/查提醒。
        // 两条都只在私聊里生效——群里谁都在说话，提醒会变成广播，也判不清该谁回执。
        if (!media && conversation.getChatType() == ChatType.PRIVATE) {
            Optional<String> ackReply = reminderAck.handle(bot, conversation, content);
            if (ackReply.isPresent()) {
                sendReminderReply(bot, conversation, inbound, ackReply.get(), "提醒已被回执停掉");
                return new AgentDtos.EventAccepted("REMINDER_ACK", "提醒收到回执，已停止", conversation.getId());
            }
            Optional<String> reminderReply = reminderIntent.handle(bot, conversation, content);
            if (reminderReply.isPresent()) {
                sendReminderReply(bot, conversation, inbound, reminderReply.get(), "按对方的话安排了提醒");
                return new AgentDtos.EventAccepted("REMINDER", "已处理提醒指令", conversation.getId());
            }
        }

        // 聊天对象自己挑人设：命令式（「换人设」）和自然语言（「换成大肥鱼」）都认。
        // 这条回复不走聚合队列：它是控制指令的答复，被后面的消息合并掉就永远发不出去了；
        // 而且必须原样单条发出，编号和换行拆条后在手机上会变成一堆碎片。
        if (!media && personaSwitch.enabled()) {
            Optional<String> personaReply = personaSwitch.handle(conversation, content);
            if (personaReply.isPresent()) {
                try {
                    chatOrchestrator.generateVerbatimReply(bot.getId(), conversation.getId(),
                            inbound.getId(), personaReply.get());
                } catch (RuntimeException ex) {
                    log.warn("人设回复发送失败: conversation={}", conversation.getId(), ex);
                }
                return new AgentDtos.EventAccepted("PERSONA", "按聊天对象的选择切换了人设",
                        conversation.getId());
            }
        }

        if (!shouldReply) {
            inbound.setStatus(MessageStatus.IGNORED);
            inbound.setErrorMessage(media
                    ? "媒体消息按策略仅记录（replyMode=" + mediaMode + "）"
                    : (decisionNote == null ? "未触发回复" : decisionNote));
            messageRepository.save(inbound);
            FlowTraceService.mark(FlowTraceService.Stage.SKIP, conversation.getChatId(),
                    conversation.getChatName(), "没回：" + inbound.getErrorMessage(), content);
            return new AgentDtos.EventAccepted("IGNORED", inbound.getErrorMessage(), conversation.getId());
        }

        final var botId = bot.getId();
        final var conversationId = conversation.getId();
        final var inboundId = inbound.getId();
        final String chatKey = conversation.getChatId();
        final boolean ackReply = media && "ack".equals(mediaMode);
        final String ackText = ackReply ? settings.mediaAckText() : null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchReply(chatKey, botId, conversationId, inboundId, ackReply, ackText);
                }
            });
        } else {
            dispatchReply(chatKey, botId, conversationId, inboundId, ackReply, ackText);
        }
        return new AgentDtos.EventAccepted("ACCEPTED", "消息已接收，正在生成回复", conversationId);
    }

    /**
     * 提醒相关的回复走「原样单条」：它是控制答复，被聚合掉或者被拆成好几条都会看不懂。
     */
    private void sendReminderReply(BotConfig bot, Conversation conversation, MessageLog inbound,
                                   String text, String note) {
        FlowTraceService.mark(FlowTraceService.Stage.ACK, conversation.getChatId(),
                conversation.getChatName(), note, text);
        try {
            chatOrchestrator.generateVerbatimReply(bot.getId(), conversation.getId(), inbound.getId(), text);
        } catch (RuntimeException ex) {
            log.warn("提醒回复发送失败: conversation={}", conversation.getId(), ex);
        }
    }

    /**
     * 交给聚合器：同一个群一秒里连发好几条时，只回一次、而且回的是「最后那一下」之后的完整对话。
     *
     * <p>被合并掉的那条消息标成「已合并」，避免页面上留下一条永远是「已接收」却没人管的记录。</p>
     */
    private void dispatchReply(String chatId, UUID botId, UUID conversationId, UUID inboundId,
                               boolean ackReply, String ackText) {
        replyDispatcher.submit(chatId,
                () -> {
                    if (ackReply) {
                        chatOrchestrator.generateFixedReply(botId, conversationId, inboundId, ackText);
                    } else {
                        chatOrchestrator.generateReply(botId, conversationId, inboundId);
                    }
                },
                () -> markMerged(inboundId));
    }

    private void markMerged(UUID inboundId) {
        messageRepository.findById(inboundId).ifPresent(item -> {
            if (item.getStatus() != MessageStatus.RECEIVED) {
                return;
            }
            item.setStatus(MessageStatus.IGNORED);
            item.setErrorMessage("已合并到随后的回复（短时间内多条消息），只作为上下文");
            messageRepository.save(item);
        });
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

    /** 概率转成人话，写进忽略原因里方便排查。 */
    private static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }
}
