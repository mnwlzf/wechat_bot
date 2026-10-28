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
            boolean fromSelf
    ) {}

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
