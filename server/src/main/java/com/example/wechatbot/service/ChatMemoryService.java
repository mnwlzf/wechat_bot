package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.config.MemoryProperties;
import com.example.wechatbot.domain.ChatMemory;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.MemoryKind;
import com.example.wechatbot.domain.MemorySource;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.repository.ChatMemoryRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 会话（群）共享的长期记忆。
 *
 * <p>为什么需要它：上下文窗口只能装最近 N 条消息，一个活跃的群聊里前面定好的事、
 * 谁在忙什么、大家的称呼，很快就会滚出窗口，机器人于是「各说各的」，信息割裂。
 * 这里把滚出窗口的历史压成一份滚动摘要，再加上人工维护的要点，一起注入系统提示词，
 * 群内所有成员共用同一份记忆。</p>
 *
 * <p>阈值：新增历史达到 {@code triggerMessages} 才压一次，但不会超过「窗口之外能堆多少条」——
 * 有效阈值取 {@code min(triggerMessages, 上下文窗口条数)}，否则窗口一滚完就会有一段
 * 既不在窗口里、也没进摘要的真空期。两次压缩之间还有 {@code minIntervalMinutes} 的最小间隔。</p>
 */
@Service
public class ChatMemoryService {
    /** 短于这个长度的「摘要」基本是模型敷衍的一句废话，不如不更新。 */
    private static final int MIN_SUMMARY_CHARS = 30;

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 注入提示词时单条要点最多带多少字，防止一堆长要点把 token 吃光。 */
    private static final int NOTE_PROMPT_CHARS = 200;

    private static final String SUMMARY_SYSTEM = """
            你负责把一段微信聊天压缩成一份「长期记忆摘要」，供同一个群/会话里的机器人后续参考。
            要求：
            1. 只写聊天里确实出现过的信息：人物与称呼、约定与时间、待办、偏好、关键事实、正在讨论的话题。
            2. 不编造、不推测、不评价、不给建议，也不写「以上」「总之」这类总结套话。
            3. 用中文，纯文本，不要标题、不要列表符号，200-400 字。
            4. 如果已有摘要，把新内容合并进去，保留仍然有效的旧信息，删掉已经被推翻或过期的内容。
            5. 只写对方和群友：不要记录机器人自己的名字、自称、口吻、语气、口头禅、人设或身份设定。
               机器人随时会换人设，把这些写进摘要，只会让以后的口吻和设定前后打架。
            6. 只输出摘要正文，第一句就开始写事实。不要输出思考过程、分析、提纲、英文说明，
               也不要复述本要求，不要写「摘要：」「内容提取：」「需要写…」这类话。""";

    /** 摘要里一旦出现这些，说明模型把「分析过程」当正文输出了，宁可整段丢掉。 */
    private static final Pattern SUMMARY_META = Pattern.compile(
            "We need|We have|Let's|Let us|I need|I will|The user|The summary|Need to|"
                    + "需要写|我们要|我们要写|内容提取|要合并|按照要求|以下是|如下|摘要如下|好的[，,]|Sure[，,]|Here is",
            Pattern.CASE_INSENSITIVE);

    private final ChatMemoryRepository repository;
    private final MessageLogRepository messageRepository;
    private final LlmGateway llmGateway;
    private final MemoryProperties properties;
    /** 同一会话同时只跑一个摘要任务，避免并发重复压缩。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ChatMemoryService(ChatMemoryRepository repository,
                             MessageLogRepository messageRepository,
                             LlmGateway llmGateway,
                             MemoryProperties properties) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.llmGateway = llmGateway;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 管理端列表：按更新时间倒序，可按 chatId 过滤。 */
    public List<ChatMemory> list(String chatId) {
        String normalized = chatId == null ? "" : chatId.trim();
        return normalized.isEmpty()
                ? repository.findAllByOrderByUpdatedAtDesc()
                : repository.findByChatIdOrderByKindAscUpdatedAtDesc(normalized);
    }

    public List<ChatMemory> listByChat(String chatId) {
        return repository.findByChatIdOrderByKindAscUpdatedAtDesc(chatId);
    }

    @Transactional
    public ChatMemory createNote(String chatId, String chatName, String content, Boolean enabledFlag) {
        if (chatId == null || chatId.isBlank()) {
            throw ApiException.badRequest("请选择要记住的会话");
        }
        ChatMemory memory = new ChatMemory();
        memory.setChatId(chatId.trim());
        memory.setChatName(chatName == null ? "" : chatName.trim());
        memory.setKind(MemoryKind.NOTE);
        memory.setSource(MemorySource.MANUAL);
        memory.setContent(validateContent(content));
        memory.setEnabled(enabledFlag == null || enabledFlag);
        return repository.save(memory);
    }

    @Transactional
    public ChatMemory update(UUID id, String content, Boolean enabledFlag) {
        ChatMemory memory = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("记忆条目不存在"));
        if (content != null) {
            memory.setContent(validateContent(content));
        }
        if (enabledFlag != null) {
            memory.setEnabled(enabledFlag);
        }
        return repository.save(memory);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw ApiException.notFound("记忆条目不存在");
        }
        repository.deleteById(id);
    }

    /** 清空某个会话的摘要，下次会从头压缩（人工要点不受影响）。 */
    @Transactional
    public void resetSummary(String chatId) {
        repository.findFirstByChatIdAndKind(chatId, MemoryKind.SUMMARY).ifPresent(repository::delete);
    }

    /**
     * 拼进系统提示词的记忆块；没有任何可用记忆时返回空串。
     *
     * @param chatId   会话的 chat_id
     * @param chatType 群聊与私聊用不同的抬头，避免把群里的记忆说成「你和我」
     */
    public String promptBlock(String chatId, ChatType chatType) {
        if (!properties.isEnabled() || chatId == null || chatId.isBlank()) {
            return "";
        }
        ChatMemory summary = repository.findFirstByChatIdAndKind(chatId, MemoryKind.SUMMARY).orElse(null);
        List<ChatMemory> notes = repository
                .findByChatIdAndKindAndEnabledTrueOrderByUpdatedAtDesc(chatId, MemoryKind.NOTE);
        boolean hasSummary = summary != null && summary.isEnabled()
                && summary.getContent() != null && !summary.getContent().isBlank();
        if (!hasSummary && notes.isEmpty()) {
            return "";
        }

        String title = chatType == ChatType.GROUP ? "本群共享记忆｜群内所有成员共用" : "关于这个人的记忆";
        StringBuilder text = new StringBuilder("【").append(title).append("】\n")
                .append("下面是之前聊过、但已经不在最近对话窗口里的事。可以自然引用，不要生硬复述，")
                .append("也不要把它当成对方刚发的新消息。\n")
                // 摘要里可能残留旧人设时期的自称、口吻（「我叫大肥鱼」「我否认…」）。
                // 不点明这一点，模型会把记忆里的旧身份当成事实，换了人设也照旧那么说话。
                .append("这里只记录对方说过什么、约好过什么。如果里面出现关于「我」叫什么、什么口吻、")
                .append("什么身份的旧说法，一律以当前人设为准，不要沿用。\n");
        if (hasSummary) {
            text.append("摘要：").append(abbreviate(summary.getContent().trim(), properties.getMaxSummaryChars()))
                    .append('\n');
        }
        int limit = Math.max(1, properties.getMaxNotes());
        List<ChatMemory> visible = notes.stream().limit(limit).toList();
        if (!visible.isEmpty()) {
            text.append("要点：\n");
            for (ChatMemory note : visible) {
                text.append("- ").append(abbreviate(note.getContent().trim(), NOTE_PROMPT_CHARS)).append('\n');
            }
        }
        return text.toString().trim();
    }

    /** 回复发完之后异步压缩历史，不阻塞主链路。 */
    @Async("chatExecutor")
    public void summarizeAsync(BotConfig bot, Conversation conversation) {
        try {
            summarize(bot, conversation, false);
        } catch (Exception ex) {
            log.warn("自动生成共享记忆摘要失败: chat={} err={}", conversation.getChatId(), ex.getMessage());
        }
    }

    /** 管理端「立即生成摘要」：同步执行并把结果返回给页面。 */
    @Transactional
    public ChatMemory summarizeNow(BotConfig bot, Conversation conversation) {
        return summarize(bot, conversation, true);
    }

    /**
     * 给「已经聊完、静下来了」的会话补一份摘要，供后台定时扫描调用。
     *
     * <p>为什么必须由后台来做：{@link #summarizeAsync} 只在回复之后触发，而一段对话聊完
     * 就没人再触发它了。上午聊的内容于是卡在窗口之外、摘要之内都没有，下午对方回来问一句，
     * 机器人只能失忆。定时扫一遍，等于在「聊完」和「下次开口」之间补上记忆。</p>
     *
     * @return 是否已经「安静够久」并尝试过压缩（供后台扫描统计）
     */
    public boolean summarizeIfIdle(BotConfig bot, Conversation conversation) {
        if (!idleLongEnough(conversation)) {
            return false;
        }
        summarize(bot, conversation, false);
        return true;
    }

    /** 这个会话是否已经安静够久（久到可以认定「这段聊完了」）。 */
    private boolean idleLongEnough(Conversation conversation) {
        int minutes = properties.getIdleMinutes();
        if (minutes <= 0 || conversation.getLastMessageAt() == null) {
            return false;
        }
        return conversation.getLastMessageAt().isBefore(OffsetDateTime.now().minusMinutes(minutes));
    }

    @Transactional
    public ChatMemory summarize(BotConfig bot, Conversation conversation, boolean force) {
        if (!properties.isEnabled() || !properties.isSummaryEnabled() || bot == null || conversation == null) {
            return null;
        }
        String chatId = conversation.getChatId();
        if (!force && !inFlight.add(chatId)) {
            return null;
        }
        try {
            ChatMemory summary = repository.findFirstByChatIdAndKind(chatId, MemoryKind.SUMMARY).orElse(null);
            if (!force && summary != null && summary.getUpdatedAt() != null
                    && summary.getUpdatedAt().isAfter(OffsetDateTime.now()
                            .minusMinutes(Math.max(0, properties.getMinIntervalMinutes())))) {
                return summary;
            }

            int keep = properties.getKeepRecent() > 0
                    ? properties.getKeepRecent()
                    : Math.max(1, bot.getContextWindow());
            List<MessageLog> recent = messageRepository.findByConversationIdOrderByCreatedAtDesc(
                    conversation.getId(), PageRequest.of(0, keep));
            OffsetDateTime cutoff = recent.isEmpty()
                    ? OffsetDateTime.now()
                    : recent.get(recent.size() - 1).getCreatedAt();

            List<MessageLog> older = messageRepository
                    .findByConversationIdAndCreatedAtLessThanOrderByCreatedAtAsc(
                            conversation.getId(), cutoff, PageRequest.of(0, Math.max(1, properties.getBatchLimit())));
            OffsetDateTime covered = summary == null ? null : summary.getCoveredUntil();
            List<MessageLog> pending = older.stream()
                    .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                    .filter(item -> covered == null || item.getCreatedAt() == null || item.getCreatedAt().isAfter(covered))
                    .toList();
            if (pending.isEmpty()) {
                return summary;
            }
            // 阈值同时受窗口大小约束：已经滚出窗口的消息不该无限期地既不在窗口里、也不在摘要里。
            // 但也不能等到「整整一个窗口」都滚出去才压——那等于 20 条消息（十几轮）的真空期，
            // 中间只要有人隔一段时间回来问一句，机器人的回答就是失忆的。
            // 折中：窗口的 1/4、最少 4 条，这样一两轮对话之后窗口外的内容就会进摘要。
            int threshold = Math.min(Math.max(1, properties.getTriggerMessages()),
                    Math.max(4, Math.max(1, keep) / 4));
            boolean idle = idleLongEnough(conversation);
            if (!force && !idle && pending.size() < threshold) {
                return summary;
            }

            String merged = merge(bot, summary == null ? "" : summary.getContent(), transcript(pending));
            if (merged.isBlank()) {
                return summary;
            }

            ChatMemory target = summary == null ? new ChatMemory() : summary;
            target.setChatId(chatId);
            target.setChatName(conversation.getChatName() == null ? "" : conversation.getChatName());
            target.setKind(MemoryKind.SUMMARY);
            target.setSource(MemorySource.AUTO);
            target.setEnabled(true);
            target.setContent(merged);
            target.setMessageCount(target.getMessageCount() + pending.size());
            target.setCoveredUntil(pending.get(pending.size() - 1).getCreatedAt());
            ChatMemory saved = repository.save(target);
            log.info("共享记忆摘要已更新: chat={} 新增 {} 条消息", chatId, pending.size());
            return saved;
        } finally {
            inFlight.remove(chatId);
        }
    }

    /** 让模型把「旧摘要 + 新聊天」合并成一份新摘要；失败或输出不像摘要时退回旧摘要，不丢记忆。 */
    private String merge(BotConfig bot, String previous, String transcript) {
        StringBuilder user = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            user.append("【已有摘要】\n").append(previous.trim()).append("\n\n");
        }
        user.append("【新增聊天记录】\n").append(transcript);
        try {
            LlmReply reply = llmGateway.chat(bot, List.of(
                    ChatMessage.system(SUMMARY_SYSTEM),
                    ChatMessage.user(user.toString())));
            String cleaned = usableSummary(reply.content());
            if (cleaned == null) {
                log.warn("摘要输出不可用，保留旧摘要: chat={}", bot == null ? "" : bot.getName());
                return previous == null ? "" : previous.trim();
            }
            return abbreviate(cleaned, properties.getMaxSummaryChars());
        } catch (RuntimeException ex) {
            log.warn("摘要模型调用失败，保留旧摘要: {}", ex.getMessage());
            return previous == null ? "" : previous.trim();
        }
    }

    /**
     * 摘要正文是否可用。
     *
     * <p>问过模型「压成摘要」，它有时会把思考过程一起吐出来（线上真的见过
     * 「We need answer in Chinese… 要合并？用户说如果已有摘要…」这种），而摘要会被塞进每一次回复的
     * 系统提示里——脏摘要不只是难看，它会直接把机器人带偏。所以宁可整段丢掉，保留上一版。</p>
     *
     * @return 清理后的正文；返回 null 表示这段输出不能用
     */
    static String usableSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        // 去掉模型爱加的抬头和包裹
        text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        text = text.replaceFirst("^(摘要|长期记忆摘要|记忆摘要)\\s*[:：]\\s*", "");
        if (text.length() < MIN_SUMMARY_CHARS) {
            return null;
        }
        if (SUMMARY_META.matcher(text).find()) {
            return null;
        }
        if (!looksChinese(text)) {
            return null;
        }
        return text;
    }

    /** 摘要是给中文对话用的：英文字母占压倒多数时，基本就是模型在自言自语。 */
    private static boolean looksChinese(String text) {
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjk++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
            }
        }
        return cjk >= latin;
    }

    private String transcript(List<MessageLog> messages) {
        StringBuilder text = new StringBuilder();
        for (MessageLog item : messages) {
            String who = item.getDirection() == MessageDirection.OUTBOUND
                    ? "我"
                    : (item.getSenderName() == null || item.getSenderName().isBlank() ? "对方" : item.getSenderName());
            String when = item.getCreatedAt() == null ? "" : item.getCreatedAt().format(TIME) + " ";
            text.append(when).append(who).append("：")
                    .append(abbreviate(item.getContent().replaceAll("\\s+", " ").trim(), 400))
                    .append('\n');
        }
        return text.toString();
    }

    private String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("记忆内容不能为空");
        }
        String trimmed = content.trim();
        int max = Math.max(50, properties.getNoteMaxChars());
        if (trimmed.length() > max) {
            throw ApiException.badRequest("单条记忆不能超过 " + max + " 个字符");
        }
        return trimmed;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max));
    }
}
