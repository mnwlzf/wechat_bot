package com.example.wechatbot.service;

import com.example.wechatbot.ai.ChatMessage;
import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.config.PersonaSwitchProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 让聊天对象自己挑人设：发「换人设」→ 只回人设名称列表 → 回数字 → 立刻生效。
 *
 * <p>两个约束：</p>
 * <ul>
 *   <li><b>只发名称</b>：绝不把人设正文发出去，否则等于把 system prompt 泄露给聊天对象；</li>
 *   <li><b>真的生效</b>：选定后直接改这个会话的 {@code promptTemplateId}，
 *       下一条消息就按新的人设生成，不需要重启或额外确认。</li>
 * </ul>
 *
 * <p>待选状态放在内存里，{@code app.persona.pending-seconds} 秒后过期；
 * 进程重启会丢，这时再发一次「换人设」即可。</p>
 */
@Service
public class PersonaSwitchService {
    private static final Logger log = LoggerFactory.getLogger(PersonaSwitchService.class);

    /** 整句等于这些词时直接当成「给我名单」，避免把「他人设挺好」当成指令。 */
    private static final List<String> TRIGGERS = List.of(
            "换人设", "切换人设", "选择人设", "人设列表", "改人设", "换个人设", "persona");

    /** 表示「想换」的动词/介词，自然语言判断用。 */
    private static final List<String> SWITCH_VERBS = List.of(
            "换", "改", "切换", "切到", "调成", "设置", "选", "用", "来", "要");

    /** 名单最多列多少个（手机上太长就没法看了）。 */
    private static final int MAX_LISTED = 12;

    /** 判断「整句只是报了个名字」时剥掉的前后缀（客气话、语气词、标点）。 */
    private static final List<String> FILLERS = List.of(
            "换成", "改成", "帮我", "给我", "麻烦", "可以", "就", "那", "我", "想", "要", "用", "换", "改",
            "选", "来", "把", "帮", "请", "吧", "呀", "啊", "哈", "哦", "呢", "嘛", "谢谢", "了", "的",
            "好", "能", "吗", "，", ",", "。", ".", "！", "!", "？", "?", "、", " ", "　");

    private static final Pattern NUMBER = Pattern.compile("^\\d{1,2}$");

    private final PromptTemplateRepository repository;
    private final ConversationRepository conversationRepository;
    private final PersonaSwitchProperties properties;
    private final LlmGateway llmGateway;

    /** chatId → 这份名单与过期时间。 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(List<UUID> ids, List<String> names, Instant expiresAt) {}

    public PersonaSwitchService(PromptTemplateRepository repository,
                                ConversationRepository conversationRepository,
                                PersonaSwitchProperties properties,
                                LlmGateway llmGateway) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.properties = properties;
        this.llmGateway = llmGateway;
    }

    public boolean enabled() {
        return properties.isSwitchEnabled();
    }

    /**
     * 处理一条来消息。
     *
     * <p>支持两种说法：命令式（「换人设」）和自然语言（「换成大肥鱼」「我想用客服语气」
     * 「有哪些人设」「还能换吗」）。判断顺序：先看有没有点名某份人设，再看是不是在等编号。</p>
     *
     * @return 需要回复给用户的文本；empty 表示不是人设指令，按正常流程生成回复
     */
    @Transactional
    public Optional<String> handle(BotConfig bot, Conversation conversation, String content) {
        if (!enabled() || conversation == null || content == null) {
            return Optional.empty();
        }
        String text = content.trim();
        String chatId = conversation.getChatId();
        if (chatId == null || chatId.isBlank() || text.isEmpty()) {
            return Optional.empty();
        }
        List<PromptTemplate> usable = usablePersonas();
        if (usable.isEmpty()) {
            return isListIntent(text)
                    ? Optional.of("这边还没有可选的设定，等管理员配好再说哈。")
                    : Optional.empty();
        }

        // 1) 直接点名了某份人设：「换成大肥鱼」「用客服语气回我」——不用先发名单
        PromptTemplate named = matchByName(usable, text);
        if (named != null && !isNegated(text)) {
            pending.remove(chatId);
            return Optional.of(applyPersona(bot, conversation, named));
        }

        // 2) 名单已经发过：回数字、或者直接说名字都行
        Pending current = pending.get(chatId);
        if (current != null) {
            if (current.expiresAt().isBefore(Instant.now())) {
                pending.remove(chatId);
            } else {
                Integer index = matchNumber(text, current.ids().size());
                if (index != null) {
                    pending.remove(chatId);
                    return Optional.of(applyPersona(bot, conversation, current.ids().get(index),
                            current.names().get(index)));
                }
                if (isListIntent(text)) {
                    // 又问了一次：重发名单，别把这句话当选择吞掉
                    return Optional.of(listPersonas(chatId, usable));
                }
                if (named == null) {
                    pending.remove(chatId);
                }
            }
        }

        // 3) 想换但没点名 / 问有哪些：「换个人设」「人设都有哪些」
        if (isListIntent(text)) {
            return Optional.of(listPersonas(chatId, usable));
        }
        return Optional.empty();
    }

    private List<PromptTemplate> usablePersonas() {
        List<PromptTemplate> usable = new ArrayList<>();
        for (PromptTemplate template : repository.findAllByOrderByIsDefaultDescUpdatedAtDesc()) {
            if (template.getName() != null && !template.getName().isBlank()
                    && template.getContent() != null && !template.getContent().isBlank()) {
                usable.add(template);
            }
        }
        return usable;
    }

    /** 只发名称与编号，一条消息发完；手机上一行一个，不会看成一堆碎片。 */
    private String listPersonas(String chatId, List<PromptTemplate> usable) {
        int limit = Math.min(usable.size(), MAX_LISTED);
        StringBuilder reply = new StringBuilder("可以换成这些人设，回数字就行了：\n");
        List<UUID> ids = new ArrayList<>(limit);
        List<String> names = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            PromptTemplate template = usable.get(i);
            ids.add(template.getId());
            names.add(template.getName());
            reply.append(i + 1).append(". ").append(template.getName()).append('\n');
        }
        reply.append('\n').append("也可以直接说名字，比如「用").append(names.getFirst()).append("」。");
        if (usable.size() > limit) {
            reply.append('\n').append("（一共 ").append(usable.size()).append(" 个，这里只列前 ")
                    .append(limit).append(" 个）");
        }
        pending.put(chatId, new Pending(ids, names, Instant.now()
                .plusSeconds(Math.max(30, properties.getPendingSeconds()))));
        return reply.toString().strip();
    }

    private String applyPersona(BotConfig bot, Conversation conversation, PromptTemplate template) {
        return applyPersona(bot, conversation, template.getId(), template.getName(), template.getContent());
    }

    private String applyPersona(BotConfig bot, Conversation conversation, UUID templateId, String name) {
        String content = repository.findById(templateId).map(PromptTemplate::getContent).orElse(null);
        return applyPersona(bot, conversation, templateId, name, content);
    }

    private String applyPersona(BotConfig bot, Conversation conversation, UUID templateId, String name,
                                String templateContent) {
        conversation.setPromptTemplateId(templateId);
        // 记下切换时间：窗口里那些旧口吻的回复要被标成「别学」（见 ChatOrchestrator.personaSwitchNote）
        conversation.setPersonaSwitchedAt(OffsetDateTime.now());
        conversationRepository.save(conversation);
        log.info("聊天对象自选人设: chat={} persona={}", conversation.getChatId(), name);
        return confirmationInVoice(bot, name, templateContent);
    }

    /**
     * 换人设的确认语，用**新的人设**说一句。
     *
     * <p>固定模板「好，从现在开始用「X」。」是系统口吻：用户刚换完人设，听到的第一句还是原来那个腔调，
     * 自然觉得「换了没效果」。让模型按新人设说一句，切换当场就能听出来。
     * 模型不可用、或者输出不像一句话时退回模板——确认语宁可是模板，也不能乱说。</p>
     */
    private String confirmationInVoice(BotConfig bot, String name, String templateContent) {
        String fallback = "好，从现在开始用「" + name + "」。";
        if (llmGateway == null || bot == null || templateContent == null || templateContent.isBlank()) {
            return fallback;
        }
        try {
            LlmReply reply = llmGateway.chat(bot, List.of(
                    ChatMessage.system(templateContent),
                    ChatMessage.user("从现在起你就是上面设定里的这个人。对方刚刚要求换个说法，"
                            + "用你自己的口吻应一句（一句话，不超过 20 字），照办即可；"
                            + "不要复述设定，不要解释，不要提「人设」这两个字，只输出你要发出去的那句话。")));
            String text = reply == null || reply.content() == null ? "" : reply.content().trim();
            if (text.isEmpty() || text.length() > 60 || text.contains(ChatOrchestrator.NO_REPLY_TOKEN)) {
                return fallback;
            }
            return text;
        } catch (RuntimeException ex) {
            log.warn("人设确认语生成失败，退回模板: {}", ex.getMessage());
            return fallback;
        }
    }

    /** 这条消息是不是「换人设」指令（供回复策略判断：人设指令免唤醒）。 */
    public static boolean isTrigger(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        String normalized = trimmed.toLowerCase(java.util.Locale.ROOT);
        return TRIGGERS.stream().anyMatch(trigger -> trigger.equals(normalized) || trigger.equals(trimmed))
                || isListIntent(trimmed);
    }

    /**
     * 自然语言里「想换人设 / 问有哪些人设」的判断。
     *
     * <p>要既宽松又不误伤：必须同时出现「人设」和换/问的语气词。
     * 像「你这个人设挺有意思」没有动词也没有疑问词，不算指令。</p>
     */
    static boolean isListIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String value = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (TRIGGERS.contains(value)) {
            return true;
        }
        if (!value.contains("人设") && !value.contains("设定") && !value.contains("性格")) {
            return false;
        }
        return SWITCH_VERBS.stream().anyMatch(value::contains)
                || value.contains("哪些") || value.contains("有什么") || value.contains("有啥")
                || value.contains("列表") || value.contains("都有") || value.contains("吗")
                || value.contains("还有");
    }

    /** 说「不要换 / 别换」的时候不要真去换。 */
    private static boolean isNegated(String text) {
        String value = text.toLowerCase(java.util.Locale.ROOT);
        return value.contains("不要") || value.contains("别换") || value.contains("不用换")
                || value.contains("不想换") || value.contains("不换");
    }

    /** 消息里提到的、最长的那份人设名称（长名优先，避免「客服」抢了「客服语气」）。 */
    private static PromptTemplate matchByName(List<PromptTemplate> usable, String text) {
        String value = text.toLowerCase(java.util.Locale.ROOT);
        boolean verb = SWITCH_VERBS.stream().anyMatch(value::contains);
        PromptTemplate best = null;
        for (PromptTemplate template : usable) {
            String name = template.getName();
            if (name == null || name.isBlank() || name.length() < 2) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!value.contains(lower)) {
                continue;
            }
            // 要么句子里有「换 / 用 / 选」这类动作，要么整句去掉客气话之后就只剩这个名称。
            // 「大肥鱼在吗」这种只剩「在」字的闲聊不算选择。
            if (verb || stripFillers(value).equals(lower)) {
                if (best == null || name.length() > best.getName().length()) {
                    best = template;
                }
            }
        }
        return best;
    }

    /** 去掉「就…吧」「用…呀」这类前后缀和标点，方便判断「整句只是报了个名字」。 */
    private static String stripFillers(String value) {
        String result = value.trim();
        boolean changed = true;
        while (changed && !result.isEmpty()) {
            changed = false;
            for (String filler : FILLERS) {
                if (result.length() > filler.length() && result.startsWith(filler)) {
                    result = result.substring(filler.length()).trim();
                    changed = true;
                }
                if (result.length() > filler.length() && result.endsWith(filler)) {
                    result = result.substring(0, result.length() - filler.length()).trim();
                    changed = true;
                }
            }
        }
        return result;
    }

    private static Integer matchNumber(String text, int size) {
        if (!NUMBER.matcher(text).matches()) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= size ? value - 1 : null;
    }
}
