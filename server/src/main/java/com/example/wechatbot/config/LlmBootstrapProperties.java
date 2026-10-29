package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型 Provider 的环境变量引导配置。
 *
 * <p>目标：不打开管理端、不碰数据库，只靠 .env 就能把模型接上。
 * primary / fallback 两个槽位对应 LlmGateway 的优先级回退。</p>
 */
@ConfigurationProperties(prefix = "app.llm.bootstrap")
public class LlmBootstrapProperties {

    /** 关闭后完全忽略环境变量，只使用管理端维护的 provider。 */
    private boolean enabled = true;

    /** true：每次启动用环境变量覆盖同名 provider；false：只补空字段，保留管理端修改。 */
    private boolean overwrite = true;

    /** 是否把环境变量创建的 provider 自动挂到还没有模型的机器人上。 */
    private boolean assignToBot = true;

    private Provider primary = new Provider(10);

    private Provider fallback = new Provider(20);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isOverwrite() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }
    public boolean isAssignToBot() { return assignToBot; }
    public void setAssignToBot(boolean assignToBot) { this.assignToBot = assignToBot; }
    public Provider getPrimary() { return primary; }
    public void setPrimary(Provider primary) { this.primary = primary; }
    public Provider getFallback() { return fallback; }
    public void setFallback(Provider fallback) { this.fallback = fallback; }

    public static class Provider {
        private String name = "";
        private String baseUrl = "";
        private String apiKey = "";
        private String backupApiKey = "";
        private String model = "";
        private int priority = 100;
        private int timeoutSeconds = 60;

        public Provider() {}

        public Provider(int priority) {
            this.priority = priority;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBackupApiKey() { return backupApiKey; }
        public void setBackupApiKey(String backupApiKey) { this.backupApiKey = backupApiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
