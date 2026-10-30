package com.example.wechatbot.web.dto;

import com.example.wechatbot.domain.ChatType;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AgentDtos {
    private AgentDtos() {}

    public record AgentEventRequest(
            @NotBlank @Size(max = 160) String eventId,
            UUID botId,
            @Size(max = 30) String type,
            @NotBlank @Size(max = 180) String chatId,
            @Size(max = 180) String chatName,
            @NotNull ChatType chatType,
            @Size(max = 180) String senderId,
            @Size(max = 180) String senderName,
            @NotBlank @Size(max = 10000) String content,
            OffsetDateTime sentAt,
            boolean fromSelf,
            @Size(max = 20) String mediaKind,
            @Size(max = 255) String mediaName,
            Long mediaSize,
            @Size(max = 1000) String mediaUrl,
            Integer rawType
    ) {
        /** 文本消息的便捷构造（媒体字段为空）。 */
        public static AgentEventRequest text(String eventId, UUID botId, String type, String chatId,
                                             String chatName, ChatType chatType, String senderId,
                                             String senderName, String content, OffsetDateTime sentAt,
                                             boolean fromSelf) {
            return new AgentEventRequest(eventId, botId, type, chatId, chatName, chatType,
                    senderId, senderName, content, sentAt, fromSelf, null, null, null, null, null);
        }
    }

    public record EventAccepted(String status, String reason, UUID conversationId) {}

    public record OutboxView(
            UUID id,
            UUID conversationId,
            String chatId,
            String content,
            int attempts,
            OffsetDateTime createdAt
    ) {}

    public record OutboxAck(boolean success, @Size(max = 1000) String error) {}
}
