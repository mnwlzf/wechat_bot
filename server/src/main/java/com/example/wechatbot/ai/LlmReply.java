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
) {}
