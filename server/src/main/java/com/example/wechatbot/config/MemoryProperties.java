package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群聊共享长期记忆的开关与阈值（{@code app.memory.*}）。
 *
 * <p>默认值按「个人微信机器人」的量级挑：触发阈值比机器人上下文窗口略大，
 * 保证摘要只在真的聊了很多、窗口装不下时才生成，不额外浪费 token。</p>
 */
@ConfigurationProperties(prefix = "app.memory")
public class MemoryProperties {
    /** 总开关：关掉后既不注入记忆，也不自动生成摘要。 */
    private boolean enabled = true;

    /** 是否自动生成滚动摘要（人工条目仍然可用）。 */
    private boolean summaryEnabled = true;

    /** 新增的历史消息达到这个条数才触发一次摘要。 */
    private int triggerMessages = 24;

    /** 生成摘要时保留多少条最近消息不进摘要（0 = 用机器人的 contextWindow）。 */
    private int keepRecent = 0;

    /** 单次摘要最多读取多少条历史消息。 */
    private int batchLimit = 200;

    /** 摘要正文长度上限（字符）。 */
    private int maxSummaryChars = 600;

    /** 注入提示词的人工记忆条数上限。 */
    private int maxNotes = 8;

    /** 单条人工记忆的长度上限（字符）。 */
    private int noteMaxChars = 500;

    /** 同一会话两次自动摘要之间的最小间隔（分钟）。 */
    private int minIntervalMinutes = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSummaryEnabled() { return summaryEnabled; }
    public void setSummaryEnabled(boolean summaryEnabled) { this.summaryEnabled = summaryEnabled; }
    public int getTriggerMessages() { return triggerMessages; }
    public void setTriggerMessages(int triggerMessages) { this.triggerMessages = triggerMessages; }
    public int getKeepRecent() { return keepRecent; }
    public void setKeepRecent(int keepRecent) { this.keepRecent = keepRecent; }
    public int getBatchLimit() { return batchLimit; }
    public void setBatchLimit(int batchLimit) { this.batchLimit = batchLimit; }
    public int getMaxSummaryChars() { return maxSummaryChars; }
    public void setMaxSummaryChars(int maxSummaryChars) { this.maxSummaryChars = maxSummaryChars; }
    public int getMaxNotes() { return maxNotes; }
    public void setMaxNotes(int maxNotes) { this.maxNotes = maxNotes; }
    public int getNoteMaxChars() { return noteMaxChars; }
    public void setNoteMaxChars(int noteMaxChars) { this.noteMaxChars = noteMaxChars; }
    public int getMinIntervalMinutes() { return minIntervalMinutes; }
    public void setMinIntervalMinutes(int minIntervalMinutes) { this.minIntervalMinutes = minIntervalMinutes; }
}
