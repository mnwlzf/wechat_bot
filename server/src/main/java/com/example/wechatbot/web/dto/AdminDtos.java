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
            @Size(max = 5000) String backupApiKey
    ) {}

    public record ProviderUpdate(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 5000) String apiKey,
            @NotBlank @Size(max = 120) String model,
            boolean enabled,
            @Min(0) @Max(10000) int priority,
            @Min(5) @Max(300) int timeoutSeconds,
            @Size(max = 5000) String backupApiKey
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
            String backupApiKeyHint
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
            OffsetDateTime lastMessageAt,
            boolean botPaused,
            UUID promptTemplateId,
            String promptTemplateName
    ) {}

    public record TakeoverRequest(boolean paused) {}

    public record PersonaRequest(UUID promptTemplateId) {}

    public record PromptTemplateInput(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 300) String description,
            @NotBlank @Size(max = 20000) String content
    ) {}

    public record PromptTemplateView(
            UUID id,
            String name,
            String description,
            String content,
            OffsetDateTime updatedAt
    ) {}

    public record ScheduledMessageInput(
            UUID botId,
            @NotBlank @Size(max = 180) String chatId,
            @Size(max = 180) String chatName,
            @NotBlank @Size(max = 5000) String content,
            @NotNull OffsetDateTime scheduledAt
    ) {}

    public record ScheduledMessageView(
            UUID id,
            UUID botId,
            String chatId,
            String chatName,
            String content,
            OffsetDateTime scheduledAt,
            String status,
            int attempts,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime sentAt
    ) {}

    public record AutoReplyRuleInput(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 20) String matchType,
            @NotBlank @Size(max = 500) String pattern,
            @NotBlank @Size(max = 5000) String reply,
            @NotBlank @Size(max = 20) String scope,
            @Min(0) @Max(10000) int priority,
            boolean enabled
    ) {}

    public record AutoReplyRuleView(
            UUID id,
            String name,
            String matchType,
            String pattern,
            String reply,
            String scope,
            int priority,
            boolean enabled,
            OffsetDateTime updatedAt
    ) {}

    public record FilterPolicyUpdate(List<String> bannedWords, String mode) {}

    public record MediaPolicyUpdate(boolean enabled, String replyMode, @Size(max = 500) String ackText) {}

    public record UsagePricingUpdate(@Min(0) double promptPricePerK, @Min(0) double completionPricePerK) {}

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
            String mediaKind,
            String mediaName,
            Long mediaSize,
            String mediaUrl,
            Integer rawType,
            String quoteAuthor,
            String quoteText,
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

    /**
     * 在线调试请求。
     *
     * <p>{@code quoteAuthor} / {@code quoteText} 可选：填了就按「引用回复」来构造上下文，
     * 用来复现「引用一条消息再问」的场景，不用真的去微信里发一遍。</p>
     *
     * <p>{@code mediaKind} 可选：模拟语音 / 图片等媒体消息（例如传 {@code VOICE} + 内容
     * {@code [语音 5″]}），用来确认「听不到语音」这类说明确实生效。</p>
     */
    public record DebugRequest(@NotNull UUID botId,
                               @NotBlank @Size(max = 10000) String content,
                               @Size(max = 180) String quoteAuthor,
                               @Size(max = 1000) String quoteText,
                               @Size(max = 20) String mediaKind) {

        public DebugRequest(UUID botId, String content) {
            this(botId, content, null, null, null);
        }
    }

    public record DebugResponse(String reply, String provider, String model, long latencyMs) {}

    public record ProviderTestResponse(boolean success, String reply, String model, long latencyMs) {}

    public record SearchHit(String title, String url, String siteName, String publishedAt,
                            String excerpt, int authority, double rerankScore) {}

    public record SearchTestResponse(boolean success, String message, List<SearchHit> results,
                                     int usedKeyIndex, long latencyMs) {}

    public record WeChatPolicyUpdate(boolean groupsEnabled, boolean acceptAll, List<String> allowedChats) {}

    /**
     * 白名单候选会话（按昵称 / 备注 / 群名称搜索得到）。
     *
     * @param kind          PRIVATE / GROUP / OFFICIAL / SYSTEM
     * @param blocked       公众号、机器人、系统会话：永远不回复，不允许加进白名单
     * @param blockedReason 不可添加的原因，直接展示给用户
     * @param source        名称来源：alias（本机备注）/ reader（外部读取服务）/ contact / conversation / session / hook
     * @param lastMessage   最近一条消息，用来认出没有名字的群
     */
    public record ChatCandidateView(String chatId, String name, String kind, boolean group,
                                    boolean blocked, String blockedReason, String source,
                                    String lastMessage, OffsetDateTime lastActiveAt) {}

    /** 会话备注：名称留空表示删除。 */
    public record ChatAliasUpdate(@Size(max = 180) String name) {}

    public record ChatAliasView(String chatId, String name) {}

    public record LlmPolicyUpdate(boolean fallbackEnabled) {}

    public record UsageDaily(String date, long inbound, long outbound, long promptTokens, long completionTokens) {}

    public record UsageProvider(String provider, String model, long replies, long promptTokens,
                                long completionTokens, double avgLatencyMs) {}

    public record UsageTotals(long inbound, long outbound, long replied, long ignored, long failed,
                              long promptTokens, long completionTokens, long avgLatencyMs,
                              long activeConversations) {}

    public record UsageView(int days, OffsetDateTime from, OffsetDateTime to, UsageTotals totals,
                            double estimatedCost, String currency,
                            List<UsageDaily> daily, List<UsageProvider> providers) {}
}





