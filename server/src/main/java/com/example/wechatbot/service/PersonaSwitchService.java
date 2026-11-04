package com.example.wechatbot.service;

import com.example.wechatbot.config.PersonaSwitchProperties;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /** 触发词：整条消息只有这几个字才算，避免把「他人设挺好」当成指令。 */
    private static final List<String> TRIGGERS = List.of(
            "换人设", "切换人设", "选择人设", "人设列表", "改人设", "换个人设", "persona");

    private static final Pattern NUMBER = Pattern.compile("^\\d{1,2}$");

    private final PromptTemplateRepository repository;
    private final ConversationRepository conversationRepository;
    private final PersonaSwitchProperties properties;

    /** chatId → 这份名单与过期时间。 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(List<UUID> ids, List<String> names, Instant expiresAt) {}

    public PersonaSwitchService(PromptTemplateRepository repository,
                                ConversationRepository conversationRepository,
                                PersonaSwitchProperties properties) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isSwitchEnabled();
    }

    /**
     * 处理一条来消息。
     *
     * @return 需要回复给用户的文本；empty 表示不是人设指令，按正常流程生成回复
     */
    @Transactional
    public Optional<String> handle(Conversation conversation, String content) {
        if (!enabled() || conversation == null || content == null) {
            return Optional.empty();
        }
        String text = content.trim();
        String chatId = conversation.getChatId();
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }

        if (isTrigger(text)) {
            return Optional.of(listPersonas(chatId));
        }

        Pending current = pending.get(chatId);
        if (current == null) {
            return Optional.empty();
        }
        if (current.expiresAt().isBefore(Instant.now())) {
            pending.remove(chatId);
            return Optional.empty();
        }

        Integer index = matchNumber(text, current.names().size());
        if (index == null) {
            // 回的不是编号：放弃这次选择，让这句话按普通消息继续走
            pending.remove(chatId);
            return Optional.empty();
        }
        pending.remove(chatId);
        return Optional.of(applyPersona(conversation, current, index));
    }

    private String listPersonas(String chatId) {
        List<PromptTemplate> templates = repository.findAllByOrderByIsDefaultDescUpdatedAtDesc();
        List<PromptTemplate> usable = new ArrayList<>();
        for (PromptTemplate template : templates) {
            if (template.getContent() != null && !template.getContent().isBlank()) {
                usable.add(template);
            }
        }
        if (usable.isEmpty()) {
            return "这边还没有可选的设定，等管理员配好再说哈。";
        }
        StringBuilder reply = new StringBuilder("可以选这些人设，回数字就行：\n");
        List<UUID> ids = new ArrayList<>(usable.size());
        List<String> names = new ArrayList<>(usable.size());
        for (int i = 0; i < usable.size(); i++) {
            PromptTemplate template = usable.get(i);
            ids.add(template.getId());
            names.add(template.getName());
            reply.append(i + 1).append(". ").append(template.getName()).append('\n');
        }
        reply.append("回复 1-").append(usable.size()).append(" 的数字即可。");
        pending.put(chatId, new Pending(ids, names, Instant.now()
                .plusSeconds(Math.max(30, properties.getPendingSeconds()))));
        return reply.toString().trim();
    }

    private String applyPersona(Conversation conversation, Pending current, int index) {
        UUID templateId = current.ids().get(index);
        String name = current.names().get(index);
        conversation.setPromptTemplateId(templateId);
        conversationRepository.save(conversation);
        log.info("聊天对象自选人设: chat={} persona={}", conversation.getChatId(), name);
        return "好，从现在开始用「" + name + "」。";
    }

    private static boolean isTrigger(String text) {
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return TRIGGERS.stream().anyMatch(trigger -> trigger.equals(normalized) || trigger.equals(text));
    }

    private static Integer matchNumber(String text, int size) {
        if (!NUMBER.matcher(text).matches()) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= size ? value - 1 : null;
    }
}
