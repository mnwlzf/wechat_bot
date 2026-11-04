package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    private final ConversationRepository conversationRepository;
    private final BotConfigRepository botConfigRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final PromptTemplateService personaService;
    private final ChatMemoryService memoryService;
    private final DisguiseService disguise;
    private final AdminQueryService adminQueryService;

    public PersonaBindingService(ConversationRepository conversationRepository,
                                 BotConfigRepository botConfigRepository,
                                 PromptTemplateRepository promptTemplateRepository,
                                 PromptTemplateService personaService,
                                 ChatMemoryService memoryService,
                                 DisguiseService disguise,
                                 AdminQueryService adminQueryService) {
        this.conversationRepository = conversationRepository;
        this.botConfigRepository = botConfigRepository;
        this.promptTemplateRepository = promptTemplateRepository;
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

    /** 预览该会话真正会发给模型的人设 / 记忆 / 风格三段系统提示词。 */
    public AdminDtos.PersonaPreview preview(UUID conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        BotConfig bot = botConfigRepository.findById(conversation.getBotId()).orElse(null);
        PromptTemplateService.Persona persona = personaService.resolve(bot, conversation);
        return new AdminDtos.PersonaPreview(
                conversation.getId(),
                conversation.getChatId(),
                conversation.getChatName(),
                conversation.getChatType().name(),
                conversation.getPromptTemplateId(),
                persona.name(),
                sourceOf(conversation, persona),
                persona.builtin(),
                persona.content(),
                memoryService.promptBlock(conversation.getChatId(), conversation.getChatType()),
                disguise.promptSuffix(conversation.getChatId())
        );
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

    private static String normalize(String chatId) {
        String value = chatId == null ? "" : chatId.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("请填写微信群 ID（@chatroom 结尾）或好友微信号 wxid");
        }
        if (value.length() > 180) {
            throw ApiException.badRequest("ID 太长，最多 180 个字符");
        }
        return value;
    }
}
