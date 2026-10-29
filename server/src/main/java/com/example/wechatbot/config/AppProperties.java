package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Security security = new Security();
    private final Cors cors = new Cors();
    private final Llm llm = new Llm();

    public Security getSecurity() { return security; }
    public Cors getCors() { return cors; }
    public Llm getLlm() { return llm; }

    public static class Security {
        private String adminToken = "change-me-admin-token";
        private String agentToken = "change-me-agent-token";
        private String secretKey = "dev-only-change-me-before-production";

        public String getAdminToken() { return adminToken; }
        public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
        public String getAgentToken() { return agentToken; }
        public void setAgentToken(String agentToken) { this.agentToken = agentToken; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173";

        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public List<String> originList() {
            return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public static class Llm {
        private int maxContextMessages = 30;
        private boolean fallbackEnabled = true;
        private Search search = new Search();

        public int getMaxContextMessages() { return maxContextMessages; }
        public void setMaxContextMessages(int maxContextMessages) { this.maxContextMessages = maxContextMessages; }
        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public void setFallbackEnabled(boolean fallbackEnabled) { this.fallbackEnabled = fallbackEnabled; }
        public Search getSearch() { return search; }
        public void setSearch(Search search) { this.search = search; }
    }

    /** DeepSeek 官方联网搜索（{@code POST {baseUrl}/v1/tools/search}）。 */
    public static class Search {
        public enum Mode { OFF, HEURISTIC, ALWAYS }

        private boolean enabled = true;
        private String mode = "HEURISTIC";
        private String baseUrl = "https://api.deepseek.com";
        /** 搜索专用 Key；留空则复用模型 Provider 的主/备 Key。 */
        private String apiKey = "";
        private String backupApiKey = "";
        private int count = 5;
        private boolean needContent = false;
        private int maxResults = 4;
        private int maxContentChars = 1200;
        private int timeoutSeconds = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBackupApiKey() { return backupApiKey; }
        public void setBackupApiKey(String backupApiKey) { this.backupApiKey = backupApiKey; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public boolean isNeedContent() { return needContent; }
        public void setNeedContent(boolean needContent) { this.needContent = needContent; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getMaxContentChars() { return maxContentChars; }
        public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public Mode resolvedMode() {
            if (!enabled) {
                return Mode.OFF;
            }
            try {
                return Mode.valueOf(mode == null ? "HEURISTIC" : mode.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return Mode.HEURISTIC;
            }
        }
    }
}
