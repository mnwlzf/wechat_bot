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

        public int getMaxContextMessages() { return maxContextMessages; }
        public void setMaxContextMessages(int maxContextMessages) { this.maxContextMessages = maxContextMessages; }
        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public void setFallbackEnabled(boolean fallbackEnabled) { this.fallbackEnabled = fallbackEnabled; }
    }
}
