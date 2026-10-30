package com.example.wechatbot.service;

import com.example.wechatbot.ai.SecretCipher;
import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 联网搜索独立配置：Base URL、主/备 Key、搜索模式与结果参数。
 * 与聊天模型 Provider 的 Base URL / Key 完全分离。
 */
@Service
public class SearchSettingsService {
    private static final Logger log = LoggerFactory.getLogger(SearchSettingsService.class);

    private static final String KEY_ENABLED = "search.enabled";
    private static final String KEY_MODE = "search.mode";
    private static final String KEY_BASE_URL = "search.baseUrl";
    private static final String KEY_API_KEY = "search.apiKeyEncrypted";
    private static final String KEY_BACKUP_API_KEY = "search.backupApiKeyEncrypted";
    private static final String KEY_COUNT = "search.count";
    private static final String KEY_NEED_CONTENT = "search.needContent";
    private static final String KEY_MAX_RESULTS = "search.maxResults";
    private static final String KEY_MAX_CONTENT_CHARS = "search.maxContentChars";
    private static final String KEY_TIMEOUT_SECONDS = "search.timeoutSeconds";

    private final AppSettingRepository repository;
    private final AppProperties appProperties;
    private final SecretCipher secretCipher;

    public SearchSettingsService(AppSettingRepository repository,
                                 AppProperties appProperties,
                                 SecretCipher secretCipher) {
        this.repository = repository;
        this.appProperties = appProperties;
        this.secretCipher = secretCipher;
    }

    @PostConstruct
    public void reload() {
        try {
            Map<String, String> stored = new LinkedHashMap<>();
            repository.findAll().forEach(item -> stored.put(item.getKey(), item.getValue()));
            applyStored(stored);
            log.info("联网搜索配置已加载，来源={}", stored.keySet().stream().anyMatch(key -> key.startsWith("search.")) ? "数据库+环境变量" : "环境变量");
        } catch (RuntimeException ex) {
            log.warn("加载联网搜索配置失败，使用环境变量默认值: {}", ex.getMessage());
        }
    }

    public SearchSettingsView view() {
        AppProperties.Search search = appProperties.getLlm().getSearch();
        String apiKey = search.getApiKey();
        String backupApiKey = search.getBackupApiKey();
        return new SearchSettingsView(
                search.isEnabled(),
                search.resolvedMode().name(),
                search.getBaseUrl(),
                !blank(apiKey),
                hint(apiKey),
                !blank(backupApiKey),
                hint(backupApiKey),
                search.getCount(),
                search.isNeedContent(),
                search.getMaxResults(),
                search.getMaxContentChars(),
                search.getTimeoutSeconds());
    }

    @Transactional
    public SearchSettingsView update(SearchSettingsUpdate request) {
        String baseUrl = requireHttpUrl(request.baseUrl());
        String mode = normalizeMode(request.mode());
        int count = clamp(request.count(), 1, 20, "count");
        int maxResults = clamp(request.maxResults(), 1, 10, "maxResults");
        int maxContentChars = clamp(request.maxContentChars(), 100, 10000, "maxContentChars");
        int timeoutSeconds = clamp(request.timeoutSeconds(), 5, 120, "timeoutSeconds");

        save(KEY_ENABLED, String.valueOf(request.enabled()));
        save(KEY_MODE, mode);
        save(KEY_BASE_URL, baseUrl);
        save(KEY_COUNT, String.valueOf(count));
        save(KEY_NEED_CONTENT, String.valueOf(request.needContent()));
        save(KEY_MAX_RESULTS, String.valueOf(maxResults));
        save(KEY_MAX_CONTENT_CHARS, String.valueOf(maxContentChars));
        save(KEY_TIMEOUT_SECONDS, String.valueOf(timeoutSeconds));

        AppProperties.Search search = appProperties.getLlm().getSearch();
        search.setEnabled(request.enabled());
        search.setMode(mode);
        search.setBaseUrl(baseUrl);
        search.setCount(count);
        search.setNeedContent(request.needContent());
        search.setMaxResults(maxResults);
        search.setMaxContentChars(maxContentChars);
        search.setTimeoutSeconds(timeoutSeconds);

        if (request.clearApiKey()) {
            save(KEY_API_KEY, "");
            search.setApiKey("");
        } else if (!blank(request.apiKey())) {
            save(KEY_API_KEY, secretCipher.encrypt(request.apiKey().trim()));
            search.setApiKey(request.apiKey().trim());
        }
        if (request.clearBackupApiKey()) {
            save(KEY_BACKUP_API_KEY, "");
            search.setBackupApiKey("");
        } else if (!blank(request.backupApiKey())) {
            save(KEY_BACKUP_API_KEY, secretCipher.encrypt(request.backupApiKey().trim()));
            search.setBackupApiKey(request.backupApiKey().trim());
        }

        log.info("联网搜索配置已更新: enabled={} mode={} baseUrl={} key={} backupKey={}",
                request.enabled(), mode, baseUrl, !blank(search.getApiKey()), !blank(search.getBackupApiKey()));
        return view();
    }

    private void applyStored(Map<String, String> stored) {
        AppProperties.Search search = appProperties.getLlm().getSearch();
        if (stored.containsKey(KEY_ENABLED)) {
            search.setEnabled(Boolean.parseBoolean(stored.get(KEY_ENABLED)));
        }
        if (stored.containsKey(KEY_MODE)) {
            search.setMode(normalizeMode(stored.get(KEY_MODE)));
        }
        if (stored.containsKey(KEY_BASE_URL) && !blank(stored.get(KEY_BASE_URL))) {
            search.setBaseUrl(requireHttpUrl(stored.get(KEY_BASE_URL)));
        }
        if (stored.containsKey(KEY_COUNT)) {
            search.setCount(clamp(parseInt(stored.get(KEY_COUNT), search.getCount()), 1, 20, "count"));
        }
        if (stored.containsKey(KEY_NEED_CONTENT)) {
            search.setNeedContent(Boolean.parseBoolean(stored.get(KEY_NEED_CONTENT)));
        }
        if (stored.containsKey(KEY_MAX_RESULTS)) {
            search.setMaxResults(clamp(parseInt(stored.get(KEY_MAX_RESULTS), search.getMaxResults()), 1, 10, "maxResults"));
        }
        if (stored.containsKey(KEY_MAX_CONTENT_CHARS)) {
            search.setMaxContentChars(clamp(parseInt(stored.get(KEY_MAX_CONTENT_CHARS), search.getMaxContentChars()), 100, 10000, "maxContentChars"));
        }
        if (stored.containsKey(KEY_TIMEOUT_SECONDS)) {
            search.setTimeoutSeconds(clamp(parseInt(stored.get(KEY_TIMEOUT_SECONDS), search.getTimeoutSeconds()), 5, 120, "timeoutSeconds"));
        }
        if (stored.containsKey(KEY_API_KEY)) {
            search.setApiKey(decrypt(stored.get(KEY_API_KEY)));
        }
        if (stored.containsKey(KEY_BACKUP_API_KEY)) {
            search.setBackupApiKey(decrypt(stored.get(KEY_BACKUP_API_KEY)));
        }
    }

    private String decrypt(String encrypted) {
        if (blank(encrypted)) {
            return "";
        }
        try {
            return secretCipher.decrypt(encrypted);
        } catch (RuntimeException ex) {
            log.warn("联网搜索 Key 解密失败，请检查 APP_SECRET_KEY: {}", ex.getMessage());
            return "";
        }
    }

    private void save(String key, String value) {
        repository.save(new AppSetting(key, value == null ? "" : value));
    }

    private static String requireHttpUrl(String value) {
        String url = value == null ? "" : value.trim().replaceAll("/+$", "");
        if (!url.matches("^https?://.+")) {
            throw ApiException.badRequest("联网搜索 Base URL 必须以 http:// 或 https:// 开头");
        }
        return url;
    }

    private static String normalizeMode(String value) {
        String mode = value == null ? "HEURISTIC" : value.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "OFF", "HEURISTIC", "ALWAYS" -> mode;
            default -> throw ApiException.badRequest("搜索模式必须是 OFF、HEURISTIC 或 ALWAYS");
        };
    }

    private static int clamp(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw ApiException.badRequest(name + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String hint(String key) {
        if (blank(key)) {
            return null;
        }
        return "****" + key.substring(Math.max(0, key.length() - 4));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SearchSettingsView(
            boolean enabled,
            String mode,
            String baseUrl,
            boolean hasApiKey,
            String apiKeyHint,
            boolean hasBackupApiKey,
            String backupApiKeyHint,
            int count,
            boolean needContent,
            int maxResults,
            int maxContentChars,
            int timeoutSeconds
    ) {}

    public record SearchSettingsUpdate(
            boolean enabled,
            String mode,
            String baseUrl,
            String apiKey,
            String backupApiKey,
            boolean clearApiKey,
            boolean clearBackupApiKey,
            int count,
            boolean needContent,
            int maxResults,
            int maxContentChars,
            int timeoutSeconds
    ) {}
}


