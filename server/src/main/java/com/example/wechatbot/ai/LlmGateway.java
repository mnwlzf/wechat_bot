package com.example.wechatbot.ai;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ModelProviderRepository providerRepository;
    private final SecretCipher secretCipher;
    private final OpenAiCompatibleClient client;
    private final AppProperties properties;

    public LlmGateway(ModelProviderRepository providerRepository, SecretCipher secretCipher,
                      OpenAiCompatibleClient client, AppProperties properties) {
        this.providerRepository = providerRepository;
        this.secretCipher = secretCipher;
        this.client = client;
        this.properties = properties;
    }

    public LlmReply chat(BotConfig bot, List<ChatMessage> messages) {
        List<ModelProvider> candidates = candidates(bot.getModelProviderId());
        if (candidates.isEmpty()) {
            throw new LlmException("没有可用的模型提供方，请先在管理后台配置并启用模型");
        }

        List<String> errors = new ArrayList<>();
        for (ModelProvider provider : candidates) {
            try {
                String apiKey = secretCipher.decrypt(provider.getApiKeyEncrypted());
                log.info("Calling LLM provider={} model={}", provider.getName(), provider.getModel());
                return client.complete(provider, apiKey, messages, bot.getTemperature(), bot.getMaxTokens());
            } catch (Exception ex) {
                String error = provider.getName() + ": " + rootMessage(ex);
                errors.add(error);
                log.warn("LLM provider failed: {}", error);
                if (!properties.getLlm().isFallbackEnabled()) {
                    break;
                }
            }
        }
        throw new LlmException("全部模型调用失败: " + String.join(" | ", errors));
    }

    public LlmReply test(ModelProvider provider) {
        String apiKey = secretCipher.decrypt(provider.getApiKeyEncrypted());
        return client.complete(provider, apiKey, List.of(
                ChatMessage.system("You are a connectivity test. Reply with exactly: OK"),
                ChatMessage.user("ping")
        ), 0, 8);
    }

    private List<ModelProvider> candidates(UUID preferredId) {
        List<ModelProvider> enabled = new ArrayList<>(providerRepository.findByEnabledTrueOrderByPriorityAscCreatedAtAsc());
        if (preferredId == null) {
            return enabled;
        }
        ModelProvider preferred = enabled.stream()
                .filter(provider -> provider.getId().equals(preferredId))
                .findFirst()
                .orElse(null);
        if (preferred == null) {
            return enabled;
        }
        enabled.remove(preferred);
        enabled.addFirst(preferred);
        return enabled;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
