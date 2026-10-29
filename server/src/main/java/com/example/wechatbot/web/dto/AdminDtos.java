package com.example.wechatbot.web.dto;

import com.example.wechatbot.domain.ChatType;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}

    public record ProviderCreate(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 5000) String apiKey,
            @NotBlank @Size(max = 120) String model,
            boolean enabled,
            @Min(0) @Max(10000) int priority,
            @Min(5) @Max(300) int timeoutSeconds,
            @Size(max = 5000) String backupApiKey,
            boolean webSearchEnabled
    ) {}

    public record ProviderUpdate(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 5000) String apiKey,
            @NotBlank @Size(max = 120) String model,
            boolean enabled,
            @Min(0) @Max(10000) int priority,
            @Min(5) @Max(300) int timeoutSeconds,
            @Size(max = 5000) String backupApiKey,
            boolean webSearchEnabled
    ) {}

    public record ProviderView(
            UUID id,
            String name,
            String baseUrl,
            String model,
            boolean enabled,
            int priority,
            int timeoutSeconds,
            boolean hasApiKey,
            String apiKeyHint,
            OffsetDateTime updatedAt,
            boolean hasBackupApiKey,
            String backupApiKeyHint,
            boolean webSearchEnabled
    ) {}

    public record BotUpdate(
            @NotBlank @Size(max = 80) String name,
            boolean enabled,
            UUID modelProviderId,
            @NotBlank @Size(max = 20000) String systemPrompt,
            boolean replyPrivate,
            boolean replyGroup,
            @Size(max = 500) String groupWakeWords,
            @Min(1) @Max(100) int contextWindow,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(1) @Max(16000) int maxTokens,
            @Min(1) @Max(600) int rateLimitPerMinute
    ) {}

    public record BotView(
            UUID id,
            String name,
            boolean enabled,
            UUID modelProviderId,
            String modelProviderName,
            String systemPrompt,
            boolean replyPrivate,
            boolean replyGroup,
            String groupWakeWords,
            int contextWindow,
            double temperature,
            int maxTokens,
            int rateLimitPerMinute,
            OffsetDateTime updatedAt
    ) {}

    public record ConversationView(
            UUID id,
            String chatId,
            String chatName,
            ChatType chatType,
            String lastMessage,
            OffsetDateTime lastMessageAt
    ) {}

    public record MessageView(
            UUID id,
            String direction,
            String senderName,
            String content,
            String providerName,
            String model,
            String status,
            String errorMessage,
            Integer promptTokens,
            Integer completionTokens,
            Long latencyMs,
            OffsetDateTime createdAt
    ) {}

    public record DashboardView(
            long conversations,
            long messages24h,
            long inbound24h,
            long pendingOutbox,
            long enabledProviders,
            List<MessageView> recentMessages
    ) {}

    public record DebugRequest(@NotNull UUID botId, @NotBlank @Size(max = 10000) String content) {}

    public record DebugResponse(String reply, String provider, String model, long latencyMs) {}

    public record ProviderTestResponse(boolean success, String reply, String model, long latencyMs) {}

    public record SearchHit(String title, String url, String siteName, String publishedAt,
                            String excerpt, int authority, double rerankScore) {}

    public record SearchTestResponse(boolean success, String message, List<SearchHit> results,
                                     int usedKeyIndex, long latencyMs) {}

    public record WeChatPolicyUpdate(boolean groupsEnabled, boolean acceptAll, List<String> allowedChats) {}

    public record LlmPolicyUpdate(boolean fallbackEnabled, boolean searchEnabled) {}
}
