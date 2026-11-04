package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 人设绑定：按群（@chatroom）或按人（私聊 wxid）指定提示词，并提供「实际会发什么」的预览。
 *
 * <p>群里和私聊在数据上都是 {@code conversation} 行，所以绑定逻辑完全一样；
 * 这里额外支持<b>只有 chatId</b> 的绑定——还没聊过的联系人也能先把人设挂上，
 * 不用等机器人先收到一条消息。</p>
 */
@Service
public class PersonaBindingService {
    private static final Logger log = LoggerFactory.getLogger(PersonaBindingService.class);

    /** 人设短于这个字数，模型基本听不出区别——值得在页面上直接点名。 */
    static final int THIN_PERSONA_CHARS = 40;

    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botConfigRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final MessageLogRepository messageRepository;
    private final PromptTemplateService personaService;
    private final ChatMemoryService memoryService;
    private final DisguiseService disguise;
    private final AdminQueryService adminQueryService;

    public PersonaBindingService(ConversationRepository conversationRepository,
                                 BotConfigRepository botConfigRepository,
                                 PromptTemplateRepository promptTemplateRepository,
                                 MessageLogRepository messageRepository,
                                 PromptTemplateService personaService,
                                 ChatMemoryService memoryService,
                                 DisguiseService disguise,
                                 AdminQueryService adminQueryService) {
        this.conversationRepository = conversationRepository;
        this.botConfigRepository = botConfigRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.messageRepository = messageRepository;
        this.personaService = personaService;
        this.memoryService = memoryService;
        this.disguise = disguise;
        this.adminQueryService = adminQueryService;
    }

    /**
     * 按 chatId 绑定人设：会话存在就改它，不存在就补一行（私聊按 wxid，群按 @chatroom）。
     *
     * @param promptTemplateId 传 null 表示解绑，回到默认人设
     */
    @Transactional
    public AdminDtos.ConversationView bind(String chatId, UUID promptTemplateId) {
        String normalized = normalize(chatId);
        if (promptTemplateId != null && !promptTemplateRepository.existsById(promptTemplateId)) {
            throw ApiException.badRequest("人设不存在");
        }
        Conversation conversation = conversationRepository
                .findFirstByChatIdOrderByLastMessageAtDesc(normalized)
                .orElseGet(() -> createConversation(normalized));
        AdminDtos.ConversationView view = adminQueryService.setPersona(conversation.getId(), promptTemplateId);
        log.info("人设绑定: chat={} type={} persona={}", normalized, view.chatType(), promptTemplateId);
        return view;
    }

    /**
     * 预览该会话真正会发给模型的系统提示词：人设、换人设提醒、共享记忆、口语约束。
     *
     * <p>「人设换了但语气没变」这类问题，聊天记录是判不出来的——它只说明机器人说了什么，
     * 说明不了它被告知了什么。所以这里复用真实生成时的同一批函数（同一份人设解析、同一段记忆、
     * 同一段口语约束、同一条换人设提醒），页面上看到的就是模型真的看到的那几段。</p>
     */
    public AdminDtos.PersonaPreview preview(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        BotConfig bot = botConfigRepository.findById(conversation.getBotId()).orElse(null);
        PromptTemplateService.Persona persona = personaService.resolve(bot, conversation);
        String content = persona.content() == null ? "" : persona.content();
        String memory = memoryService.promptBlock(conversation.getChatId(), conversation.getChatType());
        String style = disguise.promptSuffix(conversation.getChatId());
        String switchNote = switchNote(bot, conversation);

        List<String> warnings = new ArrayList<>();
        if (content.length() < THIN_PERSONA_CHARS) {
            warnings.add("这份人设只有 " + content.length() + " 个字，模型很难从这么短的描述里听出语气差别"
                    + "——写清「怎么说话」比写「是谁」更有用。");
        }
        if (switchNote != null) {
            warnings.add("窗口里还有换人设之前的回复，已额外注入一句「别学旧语气」。");
        }
        if (memory.isBlank()) {
            warnings.add("这个会话还没有记忆摘要，历史全靠 " + contextWindow(bot) + " 条对话窗口。");
        }

        return new AdminDtos.PersonaPreview(
                conversation.getId(),
                conversation.getChatId(),
                conversation.getChatName(),
                conversation.getChatType().name(),
                conversation.getPromptTemplateId(),
                persona.name(),
                sourceOf(conversation, persona),
                persona.builtin(),
                content,
                content.length(),
                content.length() < THIN_PERSONA_CHARS,
                switchNote,
                memory,
                style,
                List.copyOf(warnings)
        );
    }

    /** 和真实生成时一样的判断：窗口里还有没有「我」在换人设之前说过的话。 */
    private String switchNote(BotConfig bot, Conversation conversation) {
        if (conversation.getPersonaSwitchedAt() == null) {
            return null;
        }
        List<MessageLog> recent = new ArrayList<>(messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.getId(),
                PageRequest.of(0, contextWindow(bot), Sort.by(Sort.Direction.DESC, "createdAt"))));
        Collections.reverse(recent);
        return ChatOrchestrator.personaSwitchNote(conversation, recent);
    }

    private static int contextWindow(BotConfig bot) {
        return bot == null ? 20 : Math.max(1, bot.getContextWindow());
    }

    /**
     * 生效来源：会话自己绑了人设就是 chat，否则看是不是默认人设顶上来，最后才是机器人提示词。
     * 页面上直接显示这句话，能一眼看出「我改的那份到底有没有被用上」。
     */
    private static String sourceOf(Conversation conversation, PromptTemplateService.Persona persona) {
        if (conversation.getPromptTemplateId() != null && persona.templateId() != null) {
            return "chat";
        }
        return persona.templateId() != null ? "default" : "bot";
    }

    private String normalize(String chatId) {
        String value = chatId == null ? "" : chatId.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("请填写微信群 ID（@chatroom 结尾）或好友微信号 wxid");
        }
        if (value.length() > 180) {
            throw ApiException.badRequest("ID 太长，最多 180 个字符");
        }
        return value;
    }

    private Conversation createConversation(String chatId) {
        BotConfig bot = botConfigRepository.findFirstByEnabledTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> ApiException.badRequest("还没有启用的机器人，请先在「设置」里添加一个"));
        Conversation created = new Conversation();
        created.setBotId(bot.getId());
        created.setChatId(chatId);
        created.setChatName(chatId);
        created.setChatType(chatId.endsWith("@chatroom") ? ChatType.GROUP : ChatType.PRIVATE);
        created.setLastMessage("");
        created.setLastMessageAt(OffsetDateTime.now());
        return conversationRepository.save(created);
    }
}
