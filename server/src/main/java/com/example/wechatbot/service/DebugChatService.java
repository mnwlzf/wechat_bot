package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.Conversation;
import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageLog;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.domain.MediaKind;
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
        // 带上引用信息就能在调试页复现「引用一条消息再说话」，走的是与真实消息相同的上下文拼装
        if (request.quoteText() != null && !request.quoteText().isBlank()) {
            inbound.setQuoteAuthor(request.quoteAuthor());
            inbound.setQuoteText(request.quoteText());
        }
        // 指定媒体类型就能复现「对方发来一条语音/图片」时的说明，不用真的去微信里发
        MediaKind mediaKind = mediaKindOf(request.mediaKind());
        if (mediaKind != null) {
            inbound.setMediaKind(mediaKind);
        }
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

    /** 调试页传进来的媒体类型：认不出来就当纯文本，不因为写错一个枚举值就 500。 */
    private static MediaKind mediaKindOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MediaKind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}



