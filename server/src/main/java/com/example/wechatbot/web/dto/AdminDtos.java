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
            String promptTemplateName,
            /** 会话实际生效的人设：未绑定时为「默认人设（默认）」，都没有则为 null。 */
            String effectivePersonaName
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
            /** 是否为全局默认人设（会话未单独指定时回退到它）。 */
            boolean defaultPersona,
            /** 内置人设（例如「大肥鱼」），可编辑但会被标记出来。 */
            boolean builtin,
            String builtinKey,
            OffsetDateTime updatedAt
    ) {}

    public record MemoryInput(
            @NotBlank @Size(max = 180) String chatId,
            @Size(max = 180) String chatName,
            @NotBlank @Size(max = 2000) String content,
            Boolean enabled
    ) {}

    public record MemoryUpdateInput(
            @Size(max = 2000) String content,
            Boolean enabled
    ) {}

    public record MemoryView(
            UUID id,
            String chatId,
            String chatName,
            String kind,
            String source,
            String content,
            boolean enabled,
            int messageCount,
            OffsetDateTime coveredUntil,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    /** 发信策略的编辑入参：null 表示该项沿用下一层（默认策略 / 环境变量）。 */
    public record SendPolicyInput(
            Integer readDelayMinMs,
            Integer readDelayMaxMs,
            Integer maxDelayMs,
            Boolean splitEnabled,
            Integer maxParts,
            Integer maxPartChars,
            Integer perMinute,
            Integer per10Minutes,
            Integer perHour,
            Integer perDay,
            Integer minGapMs,
            Integer emojiLimit,
            Double dropRate,
            Boolean promptEnabled,
            Boolean enabled
    ) {}

    public record SendPolicyView(
            UUID id,
            String chatId,
            String chatName,
            boolean isDefault,
            boolean enabled,
            Integer readDelayMinMs,
            Integer readDelayMaxMs,
            Integer maxDelayMs,
            Boolean splitEnabled,
            Integer maxParts,
            Integer maxPartChars,
            Integer perMinute,
            Integer per10Minutes,
            Integer perHour,
            Integer perDay,
            Integer minGapMs,
            Integer emojiLimit,
            Double dropRate,
            Boolean promptEnabled,
            OffsetDateTime updatedAt
    ) {}

    /** 合并三层之后的生效值；inherited=true 表示这个会话没有单独配置。 */
    public record SendPolicyEffective(
            int readDelayMinMs,
            int readDelayMaxMs,
            int maxDelayMs,
            boolean splitEnabled,
            int maxParts,
            int maxPartChars,
            int perMinute,
            int per10Minutes,
            int perHour,
            int perDay,
            int minGapMs,
            int emojiLimit,
            double dropRate,
            boolean promptEnabled,
            boolean inherited
    ) {}

    public record SendPolicyOverview(
            SendPolicyEffective defaultPolicy,
            SendPolicyView defaultRow,
            List<SendPolicyView> overrides
    ) {}

    public record ChatSendPolicy(
            SendPolicyEffective effective,
            SendPolicyView override
    ) {}

    /** 回复策略的编辑入参：null 表示该项沿用下一层（默认策略 / 机器人配置）。 */
    public record ReplyPolicyInput(
            String mode,
            String wakeWords,
            Double randomRate,
            Integer humanCooldownSeconds,
            Boolean enabled
    ) {}

    public record ReplyPolicyView(
            UUID id,
            String chatId,
            String chatName,
            boolean isDefault,
            boolean enabled,
            String mode,
            String wakeWords,
            Double randomRate,
            Integer humanCooldownSeconds,
            OffsetDateTime updatedAt
    ) {}

    /** 合并三层之后的生效策略；inherited=true 表示这个群没有单独配置。 */
    public record ReplyPolicyEffective(
            String mode,
            String wakeWords,
            double randomRate,
            int humanCooldownSeconds,
            String source,
            boolean inherited
    ) {}

    public record ReplyPolicyOverview(
            ReplyPolicyEffective defaultPolicy,
            ReplyPolicyView defaultRow,
            List<ReplyPolicyView> overrides,
            String botWakeWords
    ) {}

    public record ChatReplyPolicy(
            ReplyPolicyEffective effective,
            ReplyPolicyView override
    ) {}

    public record ScheduledMessageInput(
            UUID botId,
            @NotBlank @Size(max = 180) String chatId,
            @Size(max = 180) String chatName,
            @NotBlank @Size(max = 5000) String content,
            @NotNull OffsetDateTime scheduledAt,
            /** NONE / DAILY / WEEKLY */
            @Size(max = 20) String repeatRule,
            OffsetDateTime repeatUntil,
            /** 按行拆成多条依次发送 */
            Boolean splitLines
    ) {}

    public record ScheduledMessageView(
            UUID id,
            UUID botId,
            String chatId,
            String chatName,
            String content,
            OffsetDateTime scheduledAt,
            String repeatRule,
            OffsetDateTime repeatUntil,
            boolean splitLines,
            int sentCount,
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
                               @Size(max = 20) String mediaKind,
                               /** 这次调试用哪个人设；null 表示跟随默认人设。 */
                               UUID promptTemplateId) {

        public DebugRequest(UUID botId, String content) {
            this(botId, content, null, null, null, null);
        }
    }

    /** 按 chatId 直接指定人设：会话还没出现过（例如还没私聊过）也能先绑上。 */
    public record PersonaBindRequest(@NotBlank @Size(max = 180) String chatId, UUID promptTemplateId) {}

    /**
     * 某个会话实际会发给模型的人设相关提示词，用来解释「改了人设为什么感觉没变」。
     */
    public record PersonaPreview(
            UUID conversationId,
            String chatId,
            String chatName,
            String chatType,
            UUID promptTemplateId,
            String personaName,
            /** chat = 该会话单独指定；default = 默认人设；bot = 机器人自身提示词 */
            String personaSource,
            boolean personaBuiltin,
            String personaContent,
            String memoryBlock,
            String styleBlock
    ) {}

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





