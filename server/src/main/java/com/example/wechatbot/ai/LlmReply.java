package com.example.wechatbot.ai;

import java.util.UUID;

public record LlmReply(
        UUID providerId,
        String providerName,
        String model,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs
) {
    /** 关键词规则命中时的合成回复，不消耗模型额度。 */
    public static LlmReply rule(String content) {
        return new LlmReply(null, "关键词规则", "auto-reply", content, 0, 0, 0L);
    }

    public LlmReply withContent(String newContent) {
        return new LlmReply(providerId, providerName, model, newContent, promptTokens, completionTokens, latencyMs);
    }
}
