package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 人设（提示词模板）：支持自定义增删改、切换全局默认人设，并按会话/群单独指定。
 *
 * <p>提示词优先级：<b>会话绑定的人设 → 全局默认人设 → 机器人自身的 system prompt</b>。
 * 所以「按群指定」只需要给那个群绑定人设，其余群自动走默认，不会出现有的群没脾气。</p>
 */
@Service
public class PromptTemplateService {
    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 什么都没配时的兜底提示词，保证模型至少不会收到空 system 消息。 */
    static final String FALLBACK_PROMPT = "你是一个友好、简洁、可靠的微信助手。回答时优先使用中文，不要编造事实。";

    /** 生效中的人设：{@code templateId} 为空表示回退到了机器人自身的提示词。 */
    public record Persona(UUID templateId, String name, String content, boolean builtin, boolean defaultPersona) {}

    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    public List<PromptTemplate> list() {
        return repository.findAllByOrderByIsDefaultDescUpdatedAtDesc();
    }

    public PromptTemplate get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("提示词模板不存在"));
    }

    /** 全局默认人设；没有配置时返回 empty。 */
    public PromptTemplate defaultTemplate() {
        return repository.findFirstByIsDefaultTrue().orElse(null);
    }

    @Transactional
    public PromptTemplate create(PromptTemplate input) {
        validate(input);
        input.setId(null);
        input.setBuiltinKey(null);
        // 库里还没有任何人设时，第一个创建的人设自动成为默认，省得用户以为「建了没用」
        input.setDefault(repository.count() == 0);
        return repository.save(input);
    }

    @Transactional
    public PromptTemplate update(UUID id, PromptTemplate input) {
        validate(input);
        PromptTemplate template = get(id);
        template.setName(input.getName().trim());
        template.setDescription(input.getDescription() == null ? "" : input.getDescription());
        template.setContent(input.getContent());
        // 默认标记不走普通编辑，避免把唯一索引撞掉
        return repository.save(template);
    }

    @Transactional
    public void delete(UUID id) {
        PromptTemplate template = get(id);
        boolean wasDefault = template.isDefault();
        repository.delete(template);
        if (wasDefault) {
            promoteNewest();
        }
    }

    /** 切换全局默认人设；传 null 表示取消默认（之后按机器人自身的提示词回复）。 */
    @Transactional
    public PromptTemplate setDefault(UUID id) {
        repository.clearDefaultFlags();
        if (id == null) {
            return null;
        }
        PromptTemplate template = get(id);
        template.setDefault(true);
        PromptTemplate saved = repository.save(template);
        log.info("默认人设已切换为「{}」", saved.getName());
        return saved;
    }

    /**
     * 解析某个会话最终使用的人设。
     *
     * @param bot          机器人配置，兜底用它的 system prompt
     * @param conversation 会话；为空时直接走默认人设
     */
    public Persona resolve(BotConfig bot, Conversation conversation) {
        if (conversation != null && conversation.getPromptTemplateId() != null) {
            PromptTemplate bound = repository.findById(conversation.getPromptTemplateId()).orElse(null);
            if (hasContent(bound)) {
                return toPersona(bound);
            }
        }
        PromptTemplate fallback = defaultTemplate();
        if (hasContent(fallback)) {
            return toPersona(fallback);
        }
        String prompt = bot == null || bot.getSystemPrompt() == null || bot.getSystemPrompt().isBlank()
                ? FALLBACK_PROMPT
                : bot.getSystemPrompt();
        return new Persona(null, "机器人默认提示词", prompt, false, false);
    }

    /** 会话生效人设的名字，管理端列表里用来显示「未指定 → 走哪个默认」。 */
    public String effectivePersonaName(Conversation conversation) {
        if (conversation != null && conversation.getPromptTemplateId() != null) {
            PromptTemplate bound = repository.findById(conversation.getPromptTemplateId()).orElse(null);
            if (hasContent(bound)) {
                return bound.getName();
            }
        }
        PromptTemplate fallback = defaultTemplate();
        return hasContent(fallback) ? fallback.getName() + "（默认）" : null;
    }

    private Persona toPersona(PromptTemplate template) {
        return new Persona(template.getId(), template.getName(), template.getContent(),
                template.getBuiltinKey() != null, template.isDefault());
    }

    private static boolean hasContent(PromptTemplate template) {
        return template != null && template.getContent() != null && !template.getContent().isBlank();
    }

    /** 默认人设被删掉后，把最近更新的一条顶上来，避免机器人突然没有脾气。 */
    private void promoteNewest() {
        repository.findAllByOrderByUpdatedAtDesc().stream().findFirst().ifPresent(next -> {
            repository.clearDefaultFlags();
            next.setDefault(true);
            repository.save(next);
            log.info("默认人设已删除，自动改由「{}」担任默认", next.getName());
        });
    }

    private void validate(PromptTemplate input) {
        if (input.getName() == null || input.getName().isBlank()) {
            throw ApiException.badRequest("人设名称不能为空");
        }
        if (input.getContent() == null || input.getContent().isBlank()) {
            throw ApiException.badRequest("人设内容不能为空");
        }
        if (input.getName().length() > 120) {
            throw ApiException.badRequest("人设名称不能超过 120 个字符");
        }
        if (input.getDescription() != null && input.getDescription().length() > 300) {
            throw ApiException.badRequest("人设说明不能超过 300 个字符");
        }
        if (input.getContent().length() > 20000) {
            throw ApiException.badRequest("人设内容不能超过 20000 个字符");
        }
    }
}
