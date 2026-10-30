package com.example.wechatbot.ai;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.service.RuntimeSettingsService;
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
    private final DeepSeekSearchClient searchClient;
    private final AppProperties properties;
    private final RuntimeSettingsService settings;

    public LlmGateway(ModelProviderRepository providerRepository, SecretCipher secretCipher,
                      OpenAiCompatibleClient client, DeepSeekSearchClient searchClient,
                      AppProperties properties, RuntimeSettingsService settings) {
        this.providerRepository = providerRepository;
        this.secretCipher = secretCipher;
        this.client = client;
        this.searchClient = searchClient;
        this.properties = properties;
        this.settings = settings;
    }

    public LlmReply chat(BotConfig bot, List<ChatMessage> messages) {
        List<ModelProvider> candidates = candidates(bot.getModelProviderId());
        if (candidates.isEmpty()) {
            throw new LlmException("没有可用的模型提供方，请先在管理后台配置并启用模型");
        }

        List<String> errors = new ArrayList<>();
        for (ModelProvider provider : candidates) {
            try {
                List<String> apiKeys = apiKeys(provider);
                log.info("Calling LLM provider={} model={} keys={}", provider.getName(), provider.getModel(), apiKeys.size());
                return client.complete(provider, apiKeys, messages, bot.getTemperature(), bot.getMaxTokens());
            } catch (Exception ex) {
                String error = provider.getName() + ": " + rootMessage(ex);
                errors.add(error);
                log.warn("LLM provider failed: {}", error);
                if (!settings.llmFallbackEnabled()) {
                    break;
                }
            }
        }
        throw new LlmException("全部模型调用失败: " + String.join(" | ", errors));
    }

    /**
     * 用 DeepSeek 官方搜索接口查一次网页。搜索固定走官方域名，
     * 与对话的 Base URL 解耦（中转站通常不代理搜索）。
     */
    public DeepSeekSearchClient.SearchOutcome search(BotConfig bot, String query, String originalQuestion) {
        return testSearch(query);
    }

    /** 联网搜索使用独立的搜索 Base URL 与搜索 Key，不读取聊天 Provider 的 Key。 */
    public DeepSeekSearchClient.SearchOutcome testSearch(String query) {
        AppProperties.Search config = properties.getLlm().getSearch();
        List<String> keys = searchKeys(config);
        if (keys.isEmpty()) {
            throw new LlmException("未配置联网搜索专用 API Key");
        }
        return searchClient.search(config.getBaseUrl(), keys, query, null,
                config.getCount(), config.isNeedContent(), config.getTimeoutSeconds());
    }

    /** 用指定 Provider 做权限校验，实际搜索仍使用独立搜索配置。 */
    public DeepSeekSearchClient.SearchOutcome search(ModelProvider provider, String query, String originalQuestion) {
        return testSearch(query);
    }

    public LlmReply test(ModelProvider provider) {
        return client.complete(provider, apiKeys(provider), List.of(
                ChatMessage.system("You are a connectivity test. Reply with exactly: OK"),
                ChatMessage.user("ping")
        ), 0, 8);
    }

    /** 主 Key 在前、备用 Key 在后；空值与重复值会被过滤。 */
    List<String> apiKeys(ModelProvider provider) {
        List<String> keys = new ArrayList<>();
        String primary = decrypt(provider.getApiKeyEncrypted());
        if (!primary.isBlank()) {
            keys.add(primary);
        }
        String backup = decrypt(provider.getApiKeyBackupEncrypted());
        if (!backup.isBlank() && !keys.contains(backup)) {
            keys.add(backup);
        }
        return keys;
    }

    /** 只使用联网搜索专用 Key，绝不回退到聊天模型 Key。 */
    List<String> searchKeys(AppProperties.Search config) {
        List<String> keys = new ArrayList<>();
        String primary = config.getApiKey() == null ? "" : config.getApiKey().trim();
        String backup = config.getBackupApiKey() == null ? "" : config.getBackupApiKey().trim();
        if (!primary.isBlank()) {
            keys.add(primary);
        }
        if (!backup.isBlank() && !keys.contains(backup)) {
            keys.add(backup);
        }
        return keys;
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        String value = secretCipher.decrypt(encrypted);
        return value == null ? "" : value.trim();
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


