package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

@ConfigurationProperties(prefix = "app.wechat.hook")
public class WeChatHookProperties {
    private boolean enabled = true;
    private boolean outboxEnabled = true;
    private String baseUrl = "http://127.0.0.1:30001";
    private String account = "";
    private String token = "";
    private String tokenFile = "";
    private List<String> allowedChats = new ArrayList<>();
    private String selfWxid = "";
    private boolean callbackEnabled = false;
    private boolean callbackAcceptAll = true;
    private boolean callbackRequireLoopback = true;
    private boolean callbackGroupsEnabled = false;
    private String guardStatusFile = "logs/guard-status.json";
    private boolean skipHistory = true;
    private long pollIntervalMs = 2000;
    private long initialDelayMs = 5000;
    private long outboxIntervalMs = 1000;
    private int batchSize = 100;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(8);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isOutboxEnabled() { return outboxEnabled; }
    public void setOutboxEnabled(boolean outboxEnabled) { this.outboxEnabled = outboxEnabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
    public List<String> getAllowedChats() { return allowedChats; }
    public void setAllowedChats(List<String> allowedChats) { this.allowedChats = allowedChats; }
    public String getSelfWxid() { return selfWxid; }
    public void setSelfWxid(String selfWxid) { this.selfWxid = selfWxid; }
    public boolean isCallbackEnabled() { return callbackEnabled; }
    public void setCallbackEnabled(boolean callbackEnabled) { this.callbackEnabled = callbackEnabled; }
    public boolean isCallbackAcceptAll() { return callbackAcceptAll; }
    public void setCallbackAcceptAll(boolean callbackAcceptAll) { this.callbackAcceptAll = callbackAcceptAll; }
    public boolean isCallbackRequireLoopback() { return callbackRequireLoopback; }
    public void setCallbackRequireLoopback(boolean callbackRequireLoopback) { this.callbackRequireLoopback = callbackRequireLoopback; }
    public boolean isCallbackGroupsEnabled() { return callbackGroupsEnabled; }
    public void setCallbackGroupsEnabled(boolean callbackGroupsEnabled) { this.callbackGroupsEnabled = callbackGroupsEnabled; }
    public String getGuardStatusFile() { return guardStatusFile; }
    public void setGuardStatusFile(String guardStatusFile) { this.guardStatusFile = guardStatusFile; }
    public boolean isSkipHistory() { return skipHistory; }
    public void setSkipHistory(boolean skipHistory) { this.skipHistory = skipHistory; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public long getOutboxIntervalMs() { return outboxIntervalMs; }
    public void setOutboxIntervalMs(long outboxIntervalMs) { this.outboxIntervalMs = outboxIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public List<String> allowedChatIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String configured : allowedChats) {
            if (configured == null) {
                continue;
            }
            Arrays.stream(configured.split("[,，\\n]"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .filter(value -> value.length() <= 180)
                    .forEach(ids::add);
        }
        return List.copyOf(ids);
    }

    public String resolvedToken() {
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        if (tokenFile == null || tokenFile.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(tokenFile.trim())).trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
