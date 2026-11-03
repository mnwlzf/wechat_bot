package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.ai.SearchAugmenter;
import com.example.wechatbot.asr.VoiceTranscriber;
import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.domain.*;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.wechat.WeChatImageResolver;
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

    /**
     * 用户「引用一条消息再说话」时的约定说明。
     *
     * <p>只在这条消息确实带引用时注入，避免平时白占 token。没有这句说明时，模型会把
     * 「【引用的消息｜来自 X】」当成系统标记而不是内容，于是回「我看不到具体内容」。</p>
     */
    private static final String QUOTE_HINT = """
            用户这次发的是「引用回复」。消息里【引用的消息｜来自 某人】下面那一段，就是被引用那条消息的\
            真实原文（可能很短，比如就一个字、一个表情或[图片]占位），不是编号、不是占位符，也不是让你去别处取的内容。
            请把它当成你亲眼看到的内容照实理解并回答：被引用的原文是什么就说是什么，不要回答「看不到具体内容」。\
            只有原文本身是[图片]、[语音]、[文件]这类占位、而你又没有拿到对应附件时，才说明这一条看不到细节。""";

    /** 语音没能转写（没配 ASR / 没找到音频文件）：只能报时长，别猜内容。 */
    private static final String VOICE_HINT = """
            对方这次发的是一条语音消息，你只能看到它的时长，听不到内容，也没有转写能力。\
            不要编造语音里说了什么；需要内容就请对方转成文字或重发。""";

    /** 语音已转写成文字：按文字正常回答，不要再说「听不到」。 */
    private static final String VOICE_TRANSCRIBED_HINT = """
            对方这次发的是语音，已经转成文字给你了（个别同音字可能不准，按上下文理解就行）。\
            直接按转写出来的内容回答，不要说「我听不到语音」「我这边只有语音」这类话。""";

    /** 图片在本地没找到 / 解密失败：只剩一句 [图片]，此时也不能装作看见了。 */
    private static final String IMAGE_MISSING_HINT = """
            对方这次发的是一张图片，但本地没能取到画面（文件没找到或解密失败），你看不到图里的内容。\
            不要编造画面细节；可以请对方重新发一次，或者把图里的文字打出来。""";

    /** 拟人模式的判定说明：要不要回、回什么，都由模型自己定。 */
    private static final String HUMAN_HINT = """
            【拟人模式】你就在这个群里，不是被叫到才说话的客服。读完上面的对话，自己判断最新这条消息要不要接：
            - 不接：别人之间在聊、已经有人答了、只是「嗯/收到/哈哈」或表情、跟你无关或你插不上话、同一话题你刚说过。
              这种情况只输出 [[NO_REPLY]]，不要有任何其它文字，也不要解释原因。
            - 要接：直接写你要发出去的那句话，像群成员一样自然、简短、口语化；不要写「我来回答」「作为 AI」这类话。
            判断标准像真人：没被点到也可以插一句，但别每条都刷存在感；不确定时宁可 [[NO_REPLY]]。""";

    /** 模型表示「这次不回复」的标记。 */
    static final String NO_REPLY_TOKEN = "[[NO_REPLY]]";

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
    private final PromptTemplateService personaService;
    private final ChatMemoryService memoryService;
    private final WeChatImageResolver imageResolver;
    private final VoiceTranscriber voiceTranscriber;
    private final ReplyPolicyService replyPolicies;
    private final HumanReplyGuard humanGuard;

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
                            PromptTemplateService personaService,
                            ChatMemoryService memoryService,
                            WeChatImageResolver imageResolver,
                            VoiceTranscriber voiceTranscriber,
                            ReplyPolicyService replyPolicies,
                            HumanReplyGuard humanGuard) {
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
        this.personaService = personaService;
        this.memoryService = memoryService;
        this.imageResolver = imageResolver;
        this.voiceTranscriber = voiceTranscriber;
        this.replyPolicies = replyPolicies;
        this.humanGuard = humanGuard;
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

    /**
     * 生成并投递一条回复。
     *
     * <p>拟人模式（{@link ReplyMode#HUMAN}）下，先按群策略判断这次要不要问模型，再让模型自己决定回不回：
     * 冷却期内、或模型输出 {@code [[NO_REPLY]]} 时都只把来消息标记为忽略并返回 {@code null}，
     * 不发送任何东西、也不占发信配额。</p>
     */
    public LlmReply generateReply(UUID botId, UUID conversationId, UUID inboundId, boolean enqueueOutbox) {
        BotConfig bot = requireBot(botId);
        Conversation conversation = requireConversation(conversationId);
        MessageLog inbound = requireInbound(inboundId);
        if (inbound.getStatus() == MessageStatus.IGNORED) {
            // 连发被合并的那几条、以及拟人模式判定不回的，都只当上下文，不再单独回一条
            log.info("跳过单独回复：该消息已标记忽略（{}）: conversation={}",
                    inbound.getErrorMessage(), conversationId);
            return null;
        }

        try {
            LlmReply reply = autoReply.match(conversation.getChatType(), inbound.getContent())
                    .map(rule -> {
                        log.info("关键词规则命中，跳过模型: conversation={} rule={}", conversationId, rule.getName());
                        return LlmReply.rule(rule.getReply());
                    })
                    .orElse(null);

            if (reply == null) {
                // 只有真实投递才走拟人判定，在线调试不占用冷却、也不会被跳过
                ReplyPolicyService.Effective policy = replyPolicies.forChat(conversation.getChatId(), bot);
                boolean human = enqueueOutbox && policy.mode() == ReplyMode.HUMAN;
                if (human && !humanGuard.acquire(conversation.getChatId(), policy.humanCooldownSeconds())) {
                    markSkipped(inbound, "拟人模式：判定冷却中（" + policy.humanCooldownSeconds() + " 秒），本条只记录");
                    return null;
                }
                List<ChatMessage> context = buildContext(bot, conversation, inbound);
                if (human) {
                    context = withHumanHint(context);
                }
                reply = llmGateway.chat(bot, context);
                if (human && isNoReply(reply.content())) {
                    markSkipped(inbound, "拟人模式：模型判断这条不需要回复");
                    return null;
                }
                if (enqueueOutbox && disguiseProperties.isRegenerateOnRepeat()
                        && disguise.isRepetitive(conversation.getChatId(), reply.content())) {
                    reply = regenerateWithVariation(bot, context, reply);
                }
            }
            LlmReply delivered = deliver(bot, conversation, inbound, reply, enqueueOutbox);
            // 真实会话才维护共享记忆；在线调试不污染任何群的历史
            if (enqueueOutbox) {
                memoryService.summarizeAsync(bot, conversation);
            }
            return delivered;
        } catch (Exception ex) {
            markFailed(inbound, ex);
            throw ex;
        }
    }

    /**
     * 固定话术回复（媒体消息确认、人工触发等）。与模型回复共用同一条投递链路：
     * 敏感词过滤 → 伪装层排期 → outbox，因此不会被防封策略绕过。
     */
    @Async("chatExecutor")
    public void generateFixedReplyAsync(UUID botId, UUID conversationId, UUID inboundId, String text) {
        try {
            generateFixedReply(botId, conversationId, inboundId, text);
        } catch (Exception ex) {
            log.error("Async fixed reply failed for conversation {}", conversationId, ex);
        }
    }

    public LlmReply generateFixedReply(UUID botId, UUID conversationId, UUID inboundId, String text) {
        BotConfig bot = requireBot(botId);
        Conversation conversation = requireConversation(conversationId);
        MessageLog inbound = requireInbound(inboundId);
        if (inbound.getStatus() == MessageStatus.IGNORED) {
            log.info("跳过固定回复：该消息已标记忽略（{}）: conversation={}",
                    inbound.getErrorMessage(), conversationId);
            return null;
        }
        try {
            return deliver(bot, conversation, inbound, LlmReply.rule(text), true);
        } catch (Exception ex) {
            markFailed(inbound, ex);
            throw ex;
        }
    }

    private LlmReply deliver(BotConfig bot, Conversation conversation, MessageLog inbound,
                             LlmReply reply, boolean enqueueOutbox) {
        UUID botId = bot.getId();
        UUID conversationId = conversation.getId();

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
    }

    private BotConfig requireBot(UUID botId) {
        return botRepository.findById(botId)
                .orElseThrow(() -> new IllegalStateException("机器人配置不存在"));
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalStateException("会话不存在"));
    }

    private MessageLog requireInbound(UUID inboundId) {
        return messageRepository.findById(inboundId)
                .orElseThrow(() -> new IllegalStateException("入站消息不存在"));
    }

    private void markFailed(MessageLog inbound, Exception ex) {
        inbound.setStatus(MessageStatus.FAILED);
        inbound.setErrorMessage(abbreviate(rootMessage(ex), 1000));
        messageRepository.save(inbound);
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

    /** 把拟人判定说明插在人设之后、对话之前，兼容只认「system 在前」的模型服务。 */
    static List<ChatMessage> withHumanHint(List<ChatMessage> context) {
        List<ChatMessage> messages = new ArrayList<>(context.size() + 1);
        messages.addAll(context);
        messages.add(Math.min(1, messages.size()), ChatMessage.system(HUMAN_HINT));
        return messages;
    }

    /** 模型用标记表示「这次不回复」；容忍空格与大小写差异。 */
    static boolean isNoReply(String content) {
        if (content == null) {
            return true;
        }
        String normalized = content.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("[[NO_REPLY]]") || normalized.contains("[[NOREPLY]]")
                || normalized.contains("[[不回复]]");
    }

    /** 拟人模式判定不回复：只把来消息标记为忽略，不发任何消息、不占配额。 */
    private void markSkipped(MessageLog inbound, String note) {
        inbound.setStatus(MessageStatus.IGNORED);
        inbound.setErrorMessage(abbreviate(note, 1000));
        messageRepository.save(inbound);
        log.info("拟人模式未回复: conversation={} note={}", inbound.getConversationId(), note);
    }

    private List<ChatMessage> buildContext(BotConfig bot, Conversation conversation, MessageLog currentInbound) {
        int size = Math.max(1, bot.getContextWindow());
        var pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<MessageLog> recent = new ArrayList<>(messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.getId(), pageable));
        Collections.reverse(recent);

        List<ChatMessage> messages = new ArrayList<>();
        boolean currentIncluded = false;
        boolean currentImageAttached = false;
        boolean currentVoiceTranscribed = false;
        for (MessageLog item : recent) {
            if (item.getContent() == null || item.getContent().isBlank()) {
                continue;
            }
            currentIncluded = currentIncluded || item.getId().equals(currentInbound.getId());
            if (item.getDirection() == MessageDirection.INBOUND) {
                boolean current = item.getId().equals(currentInbound.getId());
                String prefix = conversation.getChatType() == ChatType.GROUP && item.getSenderName() != null
                        ? "[" + item.getSenderName() + "] "
                        : "";
                String content = prefix + item.getContent();
                // 语音在本地转成文字后，用转写结果替换「[语音 3″]」占位，模型就能读懂说了什么
                if (current && item.getMediaKind() == MediaKind.VOICE) {
                    String voiceText = voiceTranscript(item);
                    if (!voiceText.isBlank()) {
                        content = prefix + "[语音转写] " + voiceText;
                        currentVoiceTranscribed = true;
                    }
                }
                WeChatImageResolver.Attachment image = current
                        ? imageAttachment(conversation.getChatId(), item)
                        : null;
                // 本条没有自己的图时，看看被引用的那条是不是图片：能解出来就让模型直接看画面
                WeChatImageResolver.Attachment quotedImage =
                        current && image == null ? quotedImageAttachment(conversation.getChatId(), item) : null;
                if (current) {
                    content = withQuote(item, content, quotedImage != null);
                    currentImageAttached = image != null || quotedImage != null;
                }
                if (image != null && image.thumbnail()) {
                    // 本地只有缩略图时明确告诉模型画面受限，避免它假装看清了小字
                    content = content + "\n（这张图只有缩略图，分辨率有限，细节可能看不清）";
                } else if (quotedImage != null && quotedImage.thumbnail()) {
                    content = content + "\n（被引用那张图只有缩略图，分辨率有限，细节可能看不清）";
                }
                WeChatImageResolver.Attachment attached = image != null ? image : quotedImage;
                messages.add(attached == null
                        ? ChatMessage.user(content)
                        : ChatMessage.userWithImage(content, attached.dataUrl()));
            } else if (item.getStatus() == MessageStatus.SUCCESS) {
                messages.add(ChatMessage.assistant(item.getContent()));
            }
        }

        if (!currentIncluded) {
            messages.add(ChatMessage.user(currentInbound.getContent()));
        }

        String style = disguise.promptSuffix(conversation.getChatId());
        String searchQuery = currentInbound.getMediaKind() == null ? currentInbound.getContent() : "";
        String searchContext = searchAugmenter.buildContext(bot, searchQuery);
        PromptTemplateService.Persona persona = personaService.resolve(bot, conversation);
        String memoryBlock = memoryService.promptBlock(conversation.getChatId(), conversation.getChatType());

        List<ChatMessage> withStyle = new ArrayList<>(messages.size() + 6);
        withStyle.add(ChatMessage.system(persona.content()));
        if (!memoryBlock.isBlank()) {
            withStyle.add(ChatMessage.system(memoryBlock));
        }
        if (!style.isBlank()) {
            withStyle.add(ChatMessage.system(style));
        }
        if (hasQuote(currentInbound)) {
            withStyle.add(ChatMessage.system(QUOTE_HINT));
        }
        String mediaHint = mediaHint(currentInbound, currentImageAttached, currentVoiceTranscribed);
        if (mediaHint != null) {
            withStyle.add(ChatMessage.system(mediaHint));
        }
        if (searchContext != null && !searchContext.isBlank()) {
            withStyle.add(ChatMessage.system(searchContext));
        }
        withStyle.addAll(messages);
        return List.copyOf(withStyle);
    }

    /**
     * 当前消息是「看得到但读不出内容」的类型时，明确告诉模型边界，别让它编。
     *
     * <p>语音转写成功时改成「已经转成文字给你了」；没配 ASR 或没找到音频文件时仍然只说时长。
     * 图片取不到本地文件时也只剩一句 {@code [图片]}。这两种情况模型最容易硬猜，
     * 所以在系统提示里点名说清楚。</p>
     */
    private static String mediaHint(MessageLog item, boolean imageAttached, boolean voiceTranscribed) {
        if (item == null || item.getMediaKind() == null) {
            return null;
        }
        return switch (item.getMediaKind()) {
            case VOICE -> voiceTranscribed ? VOICE_TRANSCRIBED_HINT : VOICE_HINT;
            case IMAGE -> imageAttached ? null : IMAGE_MISSING_HINT;
            default -> null;
        };
    }

    /** 语音消息 → 文字；转写不可用或失败时返回空串，行为退回「只能看到时长」。 */
    private String voiceTranscript(MessageLog item) {
        if (item.getMediaKind() != MediaKind.VOICE) {
            return "";
        }
        return voiceTranscriber.transcribe(item.getSvrId(), item.getMediaMd5(), item.getCreatedAt(),
                item.getMediaUrl()).orElse("");
    }

    /**
     * 把引用消息拼成「被引用的原文」+「这次说的话」两块，让模型知道在回应哪一句。
     *
     * <p>早先的写法是单行 {@code [引用 张三：原文]}，模型经常把它当成一个它读不懂的标记，
     * 于是回答「我这边只显示一条引用，看不到具体内容」——明明原文就在括号里。改成独立两段
     * 并加上「原文就是这些字」的说明后，模型会照实复述被引用的内容。</p>
     */
    private String withQuote(MessageLog item, String content, boolean imageAttached) {
        String quote = item.getQuoteText();
        if (quote == null || quote.isBlank()) {
            return content;
        }
        return quoteBlock(item.getQuoteAuthor(), quote, content, imageAttached);
    }

    /** 引用块的实际排版；单独抽出来便于单测覆盖（模型对这段话的排版很敏感）。 */
    static String quoteBlock(String author, String quote, String content, boolean imageAttached) {
        String who = author == null || author.isBlank() ? "对方" : author.trim();
        StringBuilder text = new StringBuilder()
                .append("【引用的消息｜来自 ").append(who).append("】\n")
                .append(abbreviate(quote, 800));
        if (imageAttached) {
            text.append("\n（被引用的是一张图片，已附在本条消息里，可以直接看画面）");
        }
        return text.append("\n【我这次说的话】\n").append(content).toString();
    }

    private static boolean hasQuote(MessageLog item) {
        return item != null && item.getQuoteText() != null && !item.getQuoteText().isBlank();
    }

    /** 当前消息是图片时取附件；取不到返回 null，模型只看到 [图片] 占位文本。 */
    private WeChatImageResolver.Attachment imageAttachment(String chatId, MessageLog item) {
        if (item.getMediaKind() != MediaKind.IMAGE) {
            return null;
        }
        List<String> md5Candidates = new ArrayList<>(2);
        addIfPresent(md5Candidates, item.getMediaMd5());
        addIfPresent(md5Candidates, item.getMediaThumbMd5());
        if (md5Candidates.isEmpty()) {
            return null;
        }
        return imageResolver.toDataUrl(chatId, item.getCreatedAt(), md5Candidates).orElse(null);
    }

    /**
     * 被引用的那条消息是图片时取它的附件。
     *
     * <p>时间要用「被引用消息自己的发送时间」，不能用本条回复的时间：被引用的图可能是
     * 几天前发的，按回复时间去找月份目录会直接落空。</p>
     */
    private WeChatImageResolver.Attachment quotedImageAttachment(String chatId, MessageLog item) {
        List<String> md5Candidates = new ArrayList<>(2);
        addIfPresent(md5Candidates, item.getQuoteMd5());
        addIfPresent(md5Candidates, item.getQuoteThumbMd5());
        if (md5Candidates.isEmpty()) {
            return null;
        }
        return imageResolver.toDataUrl(chatId, item.getQuoteMediaAt(), md5Candidates).orElse(null);
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(disguiseProperties.getZone());
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static String abbreviate(String text, int max) {
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
