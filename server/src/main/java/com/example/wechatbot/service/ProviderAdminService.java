package com.example.wechatbot.service;

import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.ai.LlmReply;
import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProviderAdminService {
    private final ModelProviderRepository providerRepository;
    private final BotConfigRepository botRepository;
    private final SecretCipher secretCipher;
    private final LlmGateway llmGateway;

    public ProviderAdminService(ModelProviderRepository providerRepository,
                                BotConfigRepository botRepository,
                                SecretCipher secretCipher,
                                LlmGateway llmGateway) {
        this.providerRepository = providerRepository;
        this.botRepository = botRepository;
        this.secretCipher = secretCipher;
        this.llmGateway = llmGateway;
    }

    public List<AdminDtos.ProviderView> list() {
        return providerRepository.findAllByOrderByPriorityAscCreatedAtAsc().stream().map(this::toView).toList();
    }

    public AdminDtos.ProviderView create(AdminDtos.ProviderCreate request) {
        ModelProvider provider = new ModelProvider();
        apply(provider, request.name(), request.baseUrl(), request.model(), request.enabled(),
                request.priority(), request.timeoutSeconds());
        provider.setApiKeyEncrypted(secretCipher.encrypt(request.apiKey()));
        provider.setApiKeyBackupEncrypted(secretCipher.encrypt(nullToEmpty(request.backupApiKey())));
        provider.setWebSearchEnabled(request.webSearchEnabled());
        return toView(providerRepository.save(provider));
    }

    public AdminDtos.ProviderView update(UUID id, AdminDtos.ProviderUpdate request) {
        ModelProvider provider = find(id);
        apply(provider, request.name(), request.baseUrl(), request.model(), request.enabled(),
                request.priority(), request.timeoutSeconds());
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            provider.setApiKeyEncrypted(secretCipher.encrypt(request.apiKey()));
        }
        if (request.backupApiKey() != null) {
            String backup = request.backupApiKey().trim();
            provider.setApiKeyBackupEncrypted(backup.isEmpty() ? "" : secretCipher.encrypt(backup));
        }
        provider.setWebSearchEnabled(request.webSearchEnabled());
        return toView(providerRepository.save(provider));
    }

    @Transactional
    public void delete(UUID id) {
        ModelProvider provider = find(id);
        if (botRepository.existsByModelProviderId(id)) {
            throw ApiException.conflict("该模型正在被机器人使用，请先切换机器人模型");
        }
        providerRepository.delete(provider);
    }

    public AdminDtos.ProviderTestResponse test(UUID id) {
        ModelProvider provider = find(id);
        try {
            LlmReply reply = llmGateway.test(provider);
            return new AdminDtos.ProviderTestResponse(true, reply.content(), reply.model(), reply.latencyMs());
        } catch (Exception ex) {
            return new AdminDtos.ProviderTestResponse(false, rootMessage(ex), provider.getModel(), 0);
        }
    }

    public AdminDtos.SearchTestResponse search(UUID id, String query) {
        ModelProvider provider = find(id);
        try {
            var outcome = llmGateway.search(provider, query, null);
            List<AdminDtos.SearchHit> hits = outcome.results().stream()
                    .map(item -> new AdminDtos.SearchHit(item.title(), item.url(), item.siteName(),
                            item.publishedAt(), item.bestExcerpt(), item.authority(), item.rerankScore()))
                    .toList();
            return new AdminDtos.SearchTestResponse(true, "OK", hits, outcome.usedKeyIndex(), outcome.latencyMs());
        } catch (Exception ex) {
            return new AdminDtos.SearchTestResponse(false, rootMessage(ex), List.of(), -1, 0);
        }
    }

    private ModelProvider find(UUID id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("模型提供方不存在"));
    }

    private void apply(ModelProvider provider, String name, String baseUrl, String model, boolean enabled,
                       int priority, int timeoutSeconds) {
        provider.setName(name.trim());
        provider.setBaseUrl(baseUrl.trim());
        provider.setModel(model.trim());
        provider.setEnabled(enabled);
        provider.setPriority(priority);
        provider.setTimeoutSeconds(timeoutSeconds);
    }

    private AdminDtos.ProviderView toView(ModelProvider provider) {
        String apiKey = secretCipher.decrypt(provider.getApiKeyEncrypted());
        String hint = apiKey.isBlank() ? null : "****" + apiKey.substring(Math.max(0, apiKey.length() - 4));
        String backupKey = provider.getApiKeyBackupEncrypted() == null
                ? ""
                : secretCipher.decrypt(provider.getApiKeyBackupEncrypted());
        String backupHint = backupKey == null || backupKey.isBlank()
                ? null
                : "****" + backupKey.substring(Math.max(0, backupKey.length() - 4));
        return new AdminDtos.ProviderView(
                provider.getId(),
                provider.getName(),
                provider.getBaseUrl(),
                provider.getModel(),
                provider.isEnabled(),
                provider.getPriority(),
                provider.getTimeoutSeconds(),
                !apiKey.isBlank(),
                hint,
                provider.getUpdatedAt(),
                backupKey != null && !backupKey.isBlank(),
                backupHint,
                provider.isWebSearchEnabled()
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
