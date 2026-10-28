package com.example.wechatbot.service;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.OutboxStatus;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.repository.OutboxMessageRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class AdminQueryService {
    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final OutboxMessageRepository outboxRepository;
    private final ModelProviderRepository providerRepository;

    public AdminQueryService(ConversationRepository conversationRepository,
                             MessageLogRepository messageRepository,
                             OutboxMessageRepository outboxRepository,
                             ModelProviderRepository providerRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.outboxRepository = outboxRepository;
        this.providerRepository = providerRepository;
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

    public Page<AdminDtos.ConversationView> conversations(String query, int page, int size) {
        String normalized = query == null || query.isBlank() ? null : query.trim();
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        var result = normalized == null
                ? conversationRepository.findAllByOrderByLastMessageAtDesc(pageable)
                : conversationRepository.search(normalized, pageable);
        return result.map(this::toConversationView);
    }

    public Page<AdminDtos.MessageView> messages(UUID conversationId, int page, int size) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.notFound("会话不存在");
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        return messageRepository.findByConversationId(conversationId, pageable).map(this::toMessageView);
    }

    private AdminDtos.ConversationView toConversationView(Conversation conversation) {
        return new AdminDtos.ConversationView(
                conversation.getId(),
                conversation.getChatId(),
                conversation.getChatName(),
                conversation.getChatType(),
                conversation.getLastMessage(),
                conversation.getLastMessageAt()
        );
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
                message.getCreatedAt()
        );
    }
}
