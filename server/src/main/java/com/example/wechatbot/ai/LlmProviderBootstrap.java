package com.example.wechatbot.ai;

import com.example.wechatbot.config.LlmBootstrapProperties;
import com.example.wechatbot.domain.BotConfig;
import com.example.wechatbot.domain.ModelProvider;
import com.example.wechatbot.repository.BotConfigRepository;
import com.example.wechatbot.repository.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用环境变量把模型 Provider 写进数据库，避免每次重装都要打开管理端手填。
 *
 * <p>幂等：同名 provider 已存在时按 {@code overwrite} 决定覆盖还是只补空字段；
 * API Key 会用 {@link SecretCipher} 加密后再落库，日志只输出掩码。</p>
 *
 * <p>没有配置任何变量时静默跳过，不影响只使用管理端维护 provider 的部署。</p>
 *
 * <p>在 bean 初始化阶段执行（早于 Web 端口开放），因此管理端一启动就能读到引导结果。</p>
 */
@Component
public class LlmProviderBootstrap implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(LlmProviderBootstrap.class);

    public record ProviderStatus(String role, String name, String baseUrl, String model,
                                 int priority, String action) {}

    public record Status(boolean enabled, boolean overwrite, boolean assignToBot,
                         List<ProviderStatus> providers, int assignedBots, List<String> warnings) {
        public static Status disabled() {
            return new Status(false, false, false, List.of(), 0, List.of());
        }
    }

    private final LlmBootstrapProperties properties;
    private final ModelProviderRepository providerRepository;
    private final BotConfigRepository botRepository;
    private final SecretCipher secretCipher;

    private volatile Status status = Status.disabled();

    public LlmProviderBootstrap(LlmBootstrapProperties properties,
                                ModelProviderRepository providerRepository,
                                BotConfigRepository botRepository,
                                SecretCipher secretCipher) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.botRepository = botRepository;
        this.secretCipher = secretCipher;
    }

    @Override
    public void afterPropertiesSet() {
        run();
    }

    public void run() {
        if (!properties.isEnabled()) {
            status = Status.disabled();
            log.info("模型 Provider 环境变量引导已关闭（LLM_BOOTSTRAP_ENABLED=false）");
            return;
        }

        List<ProviderStatus> results = new ArrayList<>(2);
        List<String> warnings = new ArrayList<>();
        apply("primary", properties.getPrimary(), results, warnings);
        apply("fallback", properties.getFallback(), results, warnings);

        int assigned = 0;
        if (properties.isAssignToBot() && !results.isEmpty()) {
            Optional<ModelProvider> primary = providerRepository.findFirstByName(results.getFirst().name());
            if (primary.isPresent()) {
                assigned = assign(primary.get(), warnings);
            }
        }
        status = new Status(true, properties.isOverwrite(), properties.isAssignToBot(),
                List.copyOf(results), assigned, List.copyOf(warnings));
        if (results.isEmpty()) {
            log.info("未配置 LLM_BASE_URL / LLM_MODEL，跳过模型 Provider 引导");
        }
        for (String warning : warnings) {
            log.warn("模型 Provider 引导：{}", warning);
        }
    }

    public Status status() {
        return status;
    }

    private void apply(String role,
                       LlmBootstrapProperties.Provider config,
                       List<ProviderStatus> results,
                       List<String> warnings) {
        if (config == null) {
            return;
        }
        String baseUrl = OpenAiCompatibleClient.normalizeBaseUrl(config.getBaseUrl());
        String model = trim(config.getModel());
        String name = trim(config.getName());

        if (baseUrl.isBlank() && model.isBlank()) {
            // 完全未配置的槽位不写状态，避免管理端看到无意义的 skipped 记录
            return;
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            warnings.add(role + " 配置不完整：LLM_BASE_URL 与 LLM_MODEL 必须同时提供");
            results.add(new ProviderStatus(role, name, baseUrl, model, 0, "skipped"));
            return;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            warnings.add(role + " 的 Base URL 必须以 http:// 或 https:// 开头：" + baseUrl);
            results.add(new ProviderStatus(role, name, baseUrl, model, 0, "skipped"));
            return;
        }
        if (name.isBlank()) {
            name = "env-" + hostOf(baseUrl);
        }

        Optional<ModelProvider> existing = providerRepository.findFirstByName(name);
        ModelProvider provider = existing.orElseGet(ModelProvider::new);
        boolean created = existing.isEmpty();
        boolean changed = created;
        if (created) {
            provider.setName(name);
        }

        if (created || properties.isOverwrite()) {
            changed |= assignIfDifferent(provider.getBaseUrl(), baseUrl, provider::setBaseUrl);
            changed |= assignIfDifferent(provider.getModel(), model, provider::setModel);
        } else {
            if (isBlank(provider.getBaseUrl())) {
                provider.setBaseUrl(baseUrl);
                changed = true;
            }
            if (isBlank(provider.getModel())) {
                provider.setModel(model);
                changed = true;
            }
        }

        String apiKey = trim(config.getApiKey());
        String currentKey = currentKey(provider, warnings, role);
        if (apiKey.isBlank()) {
            if (currentKey.isBlank()) {
                warnings.add(role + " 未提供 LLM_API_KEY，跳过创建/启用");
                results.add(new ProviderStatus(role, name, baseUrl, model, 0, "skipped"));
                return;
            }
        } else if (!apiKey.equals(currentKey)
                && (created || properties.isOverwrite() || currentKey.isBlank())) {
            provider.setApiKeyEncrypted(secretCipher.encrypt(apiKey));
            changed = true;
        }

        String backupKey = trim(config.getBackupApiKey());
        if (!backupKey.isBlank()) {
            String currentBackup = isBlank(provider.getApiKeyBackupEncrypted())
                    ? ""
                    : trim(secretCipher.decrypt(provider.getApiKeyBackupEncrypted()));
            if (!backupKey.equals(currentBackup)
                    && (created || properties.isOverwrite() || currentBackup.isBlank())) {
                provider.setApiKeyBackupEncrypted(secretCipher.encrypt(backupKey));
                changed = true;
            }
        }

        provider.setEnabled(true);
        if (provider.getPriority() != config.getPriority()) {
            provider.setPriority(config.getPriority());
            changed = true;
        }
        int timeout = Math.max(5, Math.min(300, config.getTimeoutSeconds()));
        if (provider.getTimeoutSeconds() != timeout) {
            provider.setTimeoutSeconds(timeout);
            changed = true;
        }
        providerRepository.save(provider);

        String action = created ? "created" : (changed ? "updated" : "unchanged");
        results.add(new ProviderStatus(role, name, provider.getBaseUrl(), provider.getModel(),
                provider.getPriority(), action));
        log.info("模型 Provider 环境变量引导：role={} name={} base={} model={} key={} backup={} action={}",
                role, name, provider.getBaseUrl(), provider.getModel(), mask(apiKey), mask(backupKey), action);
    }

    private int assign(ModelProvider target, List<String> warnings) {
        int assigned = 0;
        boolean anyBound = false;
        for (BotConfig bot : botRepository.findAll()) {
            if (target.getId().equals(bot.getModelProviderId())) {
                anyBound = true;
                continue;
            }
            UUID current = bot.getModelProviderId();
            boolean broken = current == null || providerRepository.findById(current).isEmpty();
            if (!broken) {
                continue;
            }
            bot.setModelProviderId(target.getId());
            botRepository.save(bot);
            assigned++;
            anyBound = true;
            log.info("机器人 {} 已挂载环境变量模型 {}", bot.getName(), target.getName());
        }
        if (assigned == 0) {
            log.info("没有需要自动挂载模型的机器人（都已在管理端配置）");
        }
        if (!anyBound) {
            warnings.add("primary 未被任何机器人使用：机器人仍绑定其它模型，请在管理端切换后再测试");
        }
        return assigned;
    }

    private boolean assignIfDifferent(String current, String next,
                                      java.util.function.Consumer<String> setter) {
        if (next.equals(current)) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    private String currentKey(ModelProvider provider, List<String> warnings, String role) {
        try {
            return trim(secretCipher.decrypt(provider.getApiKeyEncrypted()));
        } catch (RuntimeException ex) {
            warnings.add(role + " 现有 API Key 无法解密（APP_SECRET_KEY 是否变更？），将按空值处理");
            return "";
        }
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "(none)";
        }
        String tail = apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
        return "****" + tail;
    }

    private String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null || host.isBlank() ? "provider" : host;
        } catch (RuntimeException ex) {
            return "provider";
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
