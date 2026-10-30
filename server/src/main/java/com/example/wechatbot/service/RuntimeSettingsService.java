package com.example.wechatbot.service;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可在管理端实时修改的策略项（会话白名单、群聊开关、模型回退、联网搜索）。
 *
 * <p>数据库优先，没有记录时回落到环境变量默认值；写库后立即更新内存缓存，
 * 因此改配置不需要重启进程，也不需要改 .env。</p>
 */
@Service
public class RuntimeSettingsService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsService.class);

    public static final String KEY_ALLOWED_CHATS = "wechat.allowedChats";
    public static final String KEY_ACCEPT_ALL = "wechat.acceptAll";
    public static final String KEY_GROUPS_ENABLED = "wechat.groupsEnabled";
    public static final String KEY_LLM_FALLBACK = "llm.fallbackEnabled";
    public static final String KEY_SEARCH_ENABLED = "llm.searchEnabled";
    public static final String KEY_FILTER_WORDS = "filter.bannedWords";
    public static final String KEY_FILTER_MODE = "filter.mode";
    public static final String KEY_MEDIA_ENABLED = "media.enabled";
    public static final String KEY_MEDIA_REPLY_MODE = "media.replyMode";
    public static final String KEY_MEDIA_ACK_TEXT = "media.ackText";
    public static final String KEY_PRICE_PROMPT = "usage.promptPricePerK";
    public static final String KEY_PRICE_COMPLETION = "usage.completionPricePerK";

    private final AppSettingRepository repository;
    private final WeChatHookProperties hookProperties;
    private final AppProperties appProperties;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public RuntimeSettingsService(AppSettingRepository repository,
                                  WeChatHookProperties hookProperties,
                                  AppProperties appProperties) {
        this.repository = repository;
        this.hookProperties = hookProperties;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void reload() {
        cache.clear();
        try {
            repository.findAll().forEach(setting -> cache.put(setting.getKey(), setting.getValue()));
            log.info("运行时配置已加载 {} 项: {}", cache.size(), cache.keySet());
        } catch (RuntimeException ex) {
            log.warn("加载运行时配置失败，全部使用环境变量默认值: {}", ex.getMessage());
        }
    }

    // ---------- 会话策略 ----------

    public List<String> allowedChats() {
        String raw = value(KEY_ALLOWED_CHATS, String.join(",", hookProperties.allowedChatIds()));
        return parseList(raw);
    }

    public boolean acceptAll() {
        return bool(KEY_ACCEPT_ALL, hookProperties.isCallbackAcceptAll());
    }

    public boolean groupsEnabled() {
        return bool(KEY_GROUPS_ENABLED, hookProperties.isCallbackGroupsEnabled());
    }

    public void updateChatPolicy(List<String> allowedChats, boolean acceptAll, boolean groupsEnabled) {
        save(KEY_ALLOWED_CHATS, String.join(",", sanitize(allowedChats)));
        save(KEY_ACCEPT_ALL, String.valueOf(acceptAll));
        save(KEY_GROUPS_ENABLED, String.valueOf(groupsEnabled));
        log.info("会话策略已更新: allowedChats={} acceptAll={} groupsEnabled={}",
                allowedChats(), acceptAll, groupsEnabled);
    }

    // ---------- 模型策略 ----------

    public boolean llmFallbackEnabled() {
        return bool(KEY_LLM_FALLBACK, appProperties.getLlm().isFallbackEnabled());
    }

    public boolean searchEnabled() {
        return bool(KEY_SEARCH_ENABLED, appProperties.getLlm().getSearch().isEnabled());
    }

    public void updateLlmPolicy(boolean fallbackEnabled, boolean searchEnabled) {
        save(KEY_LLM_FALLBACK, String.valueOf(fallbackEnabled));
        save(KEY_SEARCH_ENABLED, String.valueOf(searchEnabled));
        log.info("模型策略已更新: fallbackEnabled={} searchEnabled={}", fallbackEnabled, searchEnabled);
    }

    // ---------- 内容过滤 ----------

    /** 敏感词模式：off 关闭；rewrite 用 * 遮蔽；block 直接不发送。 */
    public String filterMode() {
        String raw = value(KEY_FILTER_MODE, "off");
        String mode = raw == null ? "off" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "rewrite", "block" -> mode;
            default -> "off";
        };
    }

    public List<String> bannedWords() {
        return parseList(value(KEY_FILTER_WORDS, ""));
    }

    public void updateFilterPolicy(List<String> words, String mode) {
        save(KEY_FILTER_WORDS, String.join(",", sanitize(words)));
        save(KEY_FILTER_MODE, mode == null ? "off" : mode.trim().toLowerCase(java.util.Locale.ROOT));
        log.info("内容过滤已更新: mode={} words={}", filterMode(), bannedWords().size());
    }

    // ---------- 媒体消息 ----------

    /** 是否接收并记录图片 / 语音 / 文件等非文本消息。 */
    public boolean mediaEnabled() {
        return bool(KEY_MEDIA_ENABLED, true);
    }

    /** 媒体消息回复策略：ignore 只记录；ack 固定话术；llm 把占位文本交给模型。 */
    public String mediaReplyMode() {
        String raw = value(KEY_MEDIA_REPLY_MODE, "ignore");
        String mode = raw == null ? "ignore" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "ack", "llm" -> mode;
            default -> "ignore";
        };
    }

    public String mediaAckText() {
        String text = value(KEY_MEDIA_ACK_TEXT, "收到，我看下～");
        return text == null || text.isBlank() ? "收到，我看下～" : text.trim();
    }

    public void updateMediaPolicy(boolean enabled, String mode, String ackText) {
        save(KEY_MEDIA_ENABLED, String.valueOf(enabled));
        save(KEY_MEDIA_REPLY_MODE, mode == null ? "ignore" : mode.trim().toLowerCase(java.util.Locale.ROOT));
        save(KEY_MEDIA_ACK_TEXT, ackText == null ? "" : ackText.trim());
        log.info("媒体策略已更新: enabled={} mode={}", mediaEnabled(), mediaReplyMode());
    }

    // ---------- 用量计价 ----------

    public double promptPricePerK() {
        return number(KEY_PRICE_PROMPT, 0d);
    }

    public double completionPricePerK() {
        return number(KEY_PRICE_COMPLETION, 0d);
    }

    public void updateUsagePricing(double promptPricePerK, double completionPricePerK) {
        save(KEY_PRICE_PROMPT, String.valueOf(Math.max(0d, promptPricePerK)));
        save(KEY_PRICE_COMPLETION, String.valueOf(Math.max(0d, completionPricePerK)));
        log.info("用量计价已更新: prompt={}/K completion={}/K", promptPricePerK(), completionPricePerK());
    }

    // ---------- 管理端视图 ----------

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowedChats", view(KEY_ALLOWED_CHATS, allowedChats()));
        result.put("acceptAll", view(KEY_ACCEPT_ALL, acceptAll()));
        result.put("groupsEnabled", view(KEY_GROUPS_ENABLED, groupsEnabled()));
        result.put("fallbackEnabled", view(KEY_LLM_FALLBACK, llmFallbackEnabled()));
        result.put("searchEnabled", view(KEY_SEARCH_ENABLED, searchEnabled()));
        result.put("bannedWords", view(KEY_FILTER_WORDS, bannedWords()));
        result.put("filterMode", view(KEY_FILTER_MODE, filterMode()));
        result.put("mediaEnabled", view(KEY_MEDIA_ENABLED, mediaEnabled()));
        result.put("mediaReplyMode", view(KEY_MEDIA_REPLY_MODE, mediaReplyMode()));
        result.put("mediaAckText", view(KEY_MEDIA_ACK_TEXT, mediaAckText()));
        result.put("promptPricePerK", view(KEY_PRICE_PROMPT, promptPricePerK()));
        result.put("completionPricePerK", view(KEY_PRICE_COMPLETION, completionPricePerK()));
        return result;
    }

    private Map<String, Object> view(String key, Object effective) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", effective);
        item.put("source", cache.containsKey(key) ? "db" : "env");
        return item;
    }

    // ---------- 内部 ----------

    private String value(String key, String fallback) {
        String stored = cache.get(key);
        return stored == null ? fallback : stored;
    }

    private boolean bool(String key, boolean fallback) {
        String stored = cache.get(key);
        if (stored == null || stored.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(stored.trim());
    }

    private double number(String key, double fallback) {
        String stored = cache.get(key);
        if (stored == null || stored.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(stored.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void save(String key, String value) {
        cache.put(key, value);
        try {
            repository.save(new AppSetting(key, value));
        } catch (RuntimeException ex) {
            log.error("运行时配置写库失败（内存已生效，重启会丢失）: {}={}", key, value, ex);
        }
    }

    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return Arrays.stream(values.toArray(new String[0]))
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
