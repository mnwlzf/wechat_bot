package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ConversationRepository;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class DebugChatService {
    private final BotConfigRepository botRepository;
    private final ConversationRepository conversationRepository;
    private final MessageLogRepository messageRepository;
    private final ChatOrchestrator chatOrchestrator;

    public DebugChatService(BotConfigRepository botRepository,
                            ConversationRepository conversationRepository,
                            MessageLogRepository messageRepository,
                            ChatOrchestrator chatOrchestrator) {
        this.botRepository = botRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatOrchestrator = chatOrchestrator;
    }

    public AdminDtos.DebugResponse chat(AdminDtos.DebugRequest request) {
        var bot = botRepository.findById(request.botId())
                .orElseThrow(() -> ApiException.notFound("机器人配置不存在"));
        UUID chatId = UUID.nameUUIDFromBytes(("debug:" + request.botId()).getBytes(StandardCharsets.UTF_8));
        String chatKey = "debug-" + chatId;
        Conversation conversation = conversationRepository.findByBotIdAndChatId(request.botId(), chatKey)
                .orElseGet(() -> {
                    Conversation created = new Conversation();
                    created.setBotId(request.botId());
                    created.setChatId(chatKey);
                    created.setChatName("在线调试");
                    created.setChatType(ChatType.PRIVATE);
                    created.setLastMessage(request.content());
                    created.setLastMessageAt(OffsetDateTime.now());
                    return conversationRepository.save(created);
                });

        MessageLog inbound = new MessageLog();
        inbound.setConversationId(conversation.getId());
        inbound.setDirection(MessageDirection.INBOUND);
        inbound.setSenderId("debug");
        inbound.setSenderName("管理员");
        inbound.setContent(request.content());
        inbound.setStatus(MessageStatus.RECEIVED);
        inbound = messageRepository.save(inbound);

        try {
            LlmReply reply = chatOrchestrator.generateReply(bot.getId(), conversation.getId(), inbound.getId(), false);
            return new AdminDtos.DebugResponse(reply.content(), reply.providerName(), reply.model(), reply.latencyMs());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, rootMessage(ex));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}



