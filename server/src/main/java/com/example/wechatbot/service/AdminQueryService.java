package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.OutboxStatus;
import com.example.wechatbot.domain.PromptTemplate;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.repository.PromptTemplateRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminQueryService {
    private static final Logger log = LoggerFactory.getLogger(AdminQueryService.class);

    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final OutboxMessageRepository outboxRepository;
    private final ModelProviderRepository providerRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final ChatDirectoryService directoryService;

    public AdminQueryService(ConversationRepository conversationRepository,
                             MessageLogRepository messageRepository,
                             OutboxMessageRepository outboxRepository,
                             ModelProviderRepository providerRepository,
                             PromptTemplateRepository promptTemplateRepository,
                             ChatDirectoryService directoryService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.providerRepository = providerRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.directoryService = directoryService;
    }

    public AdminDtos.DashboardView dashboard() {
        OffsetDateTime since = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(24);
        var recent = messageRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 8))
                .stream().map(this::toMessageView).toList();
        return new AdminDtos.DashboardView(
                conversationRepository.count(),
                messageRepository.countByCreatedAtGreaterThanEqual(since),
                messageRepository.countByDirectionAndCreatedAtGreaterThanEqual(MessageDirection.INBOUND, since),
                outboxRepository.countByStatus(OutboxStatus.PENDING),
                providerRepository.findByEnabledTrueOrderByPriorityAscCreatedAtAsc().size(),
                recent
        );
    }

    /**
     * 会话列表 / 搜索。
     *
     * <p>会话表里只存了 chatId 的群和好友（从没收到过带名字的回调、或者早期数据），
     * 直接 LIKE {@code chat_name} 是搜不到「客户群」「张三」这种名字的。所以数据库搜不到时，
     * 再到会话目录（本机备注 › 联系人昵称 › 群名称 › 最近回调）里按名字找一遍，
     * 把命中的 chatId 回表取会话，并用目录里的名字做显示名。</p>
     */
    public Page<AdminDtos.ConversationView> conversations(String query, int page, int size) {
        String normalized = query == null || query.isBlank() ? null : query.trim();
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        if (normalized == null) {
            return conversationRepository.findAllByOrderByLastMessageAtDesc(pageable)
                    .map(this::toConversationView);
        }
        var matched = conversationRepository.search(normalized, pageable);
        if (matched.getTotalElements() > 0) {
            return matched.map(this::toConversationView);
        }
        return searchByDirectoryName(normalized, pageable);
    }

    /** 按昵称 / 备注 / 群名称补搜：数据库里没有名字也能找到。 */
    private Page<AdminDtos.ConversationView> searchByDirectoryName(String query, Pageable pageable) {
        List<ChatDirectoryService.Candidate> hits;
        try {
            hits = directoryService.search("all", query, 200);
        } catch (RuntimeException ex) {
            // 目录依赖微信 Hook / 本地库，读不到就当没搜到，不要影响会话列表本身
            log.debug("会话目录搜索失败，忽略: {}", ex.getMessage());
            return Page.empty(pageable);
        }
        Map<String, String> nameById = new LinkedHashMap<>();
        for (ChatDirectoryService.Candidate hit : hits) {
            nameById.putIfAbsent(hit.chatId(), hit.name());
        }
        if (nameById.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Conversation> found = conversationRepository.findByChatIdIn(nameById.keySet());
        List<AdminDtos.ConversationView> views = found.stream()
                .sorted(Comparator.comparing(Conversation::getLastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(item -> {
                    String name = nameById.get(item.getChatId());
                    boolean blank = item.getChatName() == null || item.getChatName().isBlank()
                            || item.getChatName().equals(item.getChatId());
                    return toConversationView(item, blank ? name : item.getChatName());
                })
                .toList();
        int from = Math.min(pageable.getPageNumber() * pageable.getPageSize(), views.size());
        int to = Math.min(from + pageable.getPageSize(), views.size());
        return new PageImpl<>(views.subList(from, to), pageable, views.size());
    }

    private AdminDtos.ConversationView toConversationView(Conversation conversation) {
        return toConversationView(conversation, conversation.getChatName());
    }

    private AdminDtos.ConversationView toConversationView(Conversation conversation, String chatName) {
        String templateName = conversation.getPromptTemplateId() == null
                ? null
                : promptTemplateRepository.findById(conversation.getPromptTemplateId())
                    .map(PromptTemplate::getName).orElse(null);
        return new AdminDtos.ConversationView(
                conversation.getId(),
                conversation.getChatId(),
                chatName,
                conversation.getChatType(),
                conversation.getLastMessage(),
                conversation.getLastMessageAt(),
                conversation.isBotPaused(),
                conversation.getPromptTemplateId(),
                templateName,
                effectivePersonaName(conversation, templateName)
        );
    }

    public Page<AdminDtos.MessageView> messages(UUID conversationId, int page, int size) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.notFound("会话不存在");
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        return messageRepository.findByConversationId(conversationId, pageable).map(this::toMessageView);
    }

    /** 未单独指定人设时，页面上要能看出实际走的是哪个默认人设。 */
    private String effectivePersonaName(Conversation conversation, String boundName) {
        if (boundName != null) {
            return boundName;
        }
        return promptTemplateRepository.findFirstByIsDefaultTrue()
                .map(template -> template.getName() + "（默认）")
                .orElse(null);
    }

    /** 人工接管开关：接管后该会话不再自动回复，只记录消息。 */
    @Transactional
    public AdminDtos.ConversationView setTakeover(UUID conversationId, boolean paused) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        conversation.setBotPaused(paused);
        return toConversationView(conversationRepository.save(conversation));
    }

    /** 会话级人格：绑定提示词模板，null 表示回退到机器人的默认提示词。 */
    @Transactional
    public AdminDtos.ConversationView setPersona(UUID conversationId, UUID promptTemplateId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (promptTemplateId != null && !promptTemplateRepository.existsById(promptTemplateId)) {
            throw ApiException.badRequest("提示词模板不存在");
        }
        conversation.setPromptTemplateId(promptTemplateId);
        return toConversationView(conversationRepository.save(conversation));
    }

    private AdminDtos.MessageView toMessageView(MessageLog message) {
        return new AdminDtos.MessageView(
                message.getId(),
                message.getDirection().name(),
                message.getSenderName(),
                message.getContent(),
                message.getProviderName(),
                message.getModel(),
                message.getStatus().name(),
                message.getErrorMessage(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getLatencyMs(),
                message.getMediaKind() == null ? null : message.getMediaKind().name(),
                message.getMediaName(),
                message.getMediaSize(),
                message.getMediaUrl(),
                message.getRawType(),
                message.getQuoteAuthor(),
                message.getQuoteText(),
                message.getCreatedAt()
        );
    }
}
