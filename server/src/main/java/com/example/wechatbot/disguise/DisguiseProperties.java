package com.example.wechatbot.disguise;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 拟人化伪装（anti-detection）参数。
 *
 * <p>所有阈值都按“像真人”而不是“效率最高”来取值：宁可慢，也不要出现
 * 固定周期、恒定延迟、超长段落、模板化措辞这些机器指纹。</p>
 */
@ConfigurationProperties(prefix = "app.disguise")
public class DisguiseProperties {

    /** 总开关。关闭后回复立即入队，不做任何节奏与风格处理（仅调试用）。 */
    private boolean enabled = true;

    /** 作息时区。 */
    private String zone = "Asia/Shanghai";

    /** 睡眠窗口起点，HH:mm，支持跨零点。 */
    private String quietStart = "00:30";

    /** 睡眠窗口终点，HH:mm。 */
    private String quietEnd = "07:30";

    /** 单条回复最多顺延多少分钟；超过则丢弃而不是排到很久以后。 */
    private int maxDeferMinutes = 480;

    /** 是否启用作息调度（睡眠窗口与离线段都受此总开关控制）。 */
    private boolean scheduleEnabled = true;

    /** 是否启用“离开手机”的手动离线块。 */
    private boolean awayBlocksEnabled = true;

    /** 每日手动离线块，格式 HH:mm-HH:mm；结束时间不晚于开始时间时视为跨零点。 */
    private List<String> awayBlocks = new ArrayList<>(List.of("10:00-10:40", "15:00-15:40"));

    // ---------------- 延迟模型 ----------------

    private int readDelayMinMs = 1200;

    private int readDelayMaxMs = 7000;

    /** 中文输入法下的平均打字速度（字/秒）。 */
    private double charsPerSecond = 3.4;

    /** 打字速度抖动比例（高斯）。 */
    private double typingJitter = 0.30;

    private int thinkBaseMs = 500;

    /** 单条消息从收到到发出的最大延迟。 */
    private int maxDelayMs = 90000;

    /** 偶发“去忙别的了”的长停顿概率。 */
    private double longPauseProbability = 0.08;

    private int longPauseMaxMs = 180000;

    /** 偶发“离开手机”的概率与时长。 */
    private double awayProbability = 0.02;

    private int awayMinMs = 240000;

    private int awayMaxMs = 900000;

    /** 群聊需要先看上下文，整体延迟放大系数。 */
    private double groupDelayFactor = 1.35;

    // ---------------- 分条 ----------------

    private boolean splitEnabled = true;

    /** 一次最多发几条气泡。 */
    private int maxParts = 3;

    /** 单条气泡的目标字数上限。 */
    private int maxPartChars = 90;

    /** 单次回复总字数上限，超出按句截断。 */
    private int maxReplyChars = 420;

    private int partGapMinMs = 600;

    private int partGapMaxMs = 2600;

    // ---------------- 配额 ----------------

    private int perMinute = 3;

    private int per10Minutes = 8;

    private int perHour = 20;

    private int perDay = 120;

    private int globalPerHour = 40;

    private int globalPerDay = 220;

    /** 同一会话两条消息之间的最小间隔。 */
    private int minGapMs = 1200;

    /** 连续爆发多少条后强制休息。 */
    private int burstSize = 5;

    private int burstCooldownMinMs = 90000;

    private int burstCooldownMaxMs = 420000;

    // ---------------- 去重 ----------------

    /** 与同会话历史回复的 3-gram Jaccard 相似度阈值。 */
    private double similarityThreshold = 0.62;

    private int similarityHistory = 12;

    /** 跨会话（群发特征）相似度阈值。 */
    private double crossChatSimilarityThreshold = 0.92;

    /** 命中重复时是否让模型换个说法重答一次。 */
    private boolean regenerateOnRepeat = true;

    // ---------------- 风格 ----------------

    private boolean styleEnabled = true;

    /** 单条消息允许的表情数量上限。 */
    private int emojiLimit = 1;

    /** 概率性不回复（模拟“看到了没回”）。默认关闭。 */
    private double dropRate = 0.0;

    /** 是否向模型追加微信口语风格约束。 */
    private boolean promptEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getQuietStart() { return quietStart; }
    public void setQuietStart(String quietStart) { this.quietStart = quietStart; }
    public String getQuietEnd() { return quietEnd; }
    public void setQuietEnd(String quietEnd) { this.quietEnd = quietEnd; }
    public int getMaxDeferMinutes() { return maxDeferMinutes; }
    public void setMaxDeferMinutes(int maxDeferMinutes) { this.maxDeferMinutes = maxDeferMinutes; }
    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
    public boolean isAwayBlocksEnabled() { return awayBlocksEnabled; }
    public void setAwayBlocksEnabled(boolean awayBlocksEnabled) { this.awayBlocksEnabled = awayBlocksEnabled; }
    public List<String> getAwayBlocks() { return awayBlocks; }
    public void setAwayBlocks(List<String> awayBlocks) {
        this.awayBlocks = awayBlocks == null ? new ArrayList<>() : new ArrayList<>(awayBlocks);
    }
    public int getReadDelayMinMs() { return readDelayMinMs; }
    public void setReadDelayMinMs(int readDelayMinMs) { this.readDelayMinMs = readDelayMinMs; }
    public int getReadDelayMaxMs() { return readDelayMaxMs; }
    public void setReadDelayMaxMs(int readDelayMaxMs) { this.readDelayMaxMs = readDelayMaxMs; }
    public double getCharsPerSecond() { return charsPerSecond; }
    public void setCharsPerSecond(double charsPerSecond) { this.charsPerSecond = charsPerSecond; }
    public double getTypingJitter() { return typingJitter; }
    public void setTypingJitter(double typingJitter) { this.typingJitter = typingJitter; }
    public int getThinkBaseMs() { return thinkBaseMs; }
    public void setThinkBaseMs(int thinkBaseMs) { this.thinkBaseMs = thinkBaseMs; }
    public int getMaxDelayMs() { return maxDelayMs; }
    public void setMaxDelayMs(int maxDelayMs) { this.maxDelayMs = maxDelayMs; }
    public double getLongPauseProbability() { return longPauseProbability; }
    public void setLongPauseProbability(double longPauseProbability) { this.longPauseProbability = longPauseProbability; }
    public int getLongPauseMaxMs() { return longPauseMaxMs; }
    public void setLongPauseMaxMs(int longPauseMaxMs) { this.longPauseMaxMs = longPauseMaxMs; }
    public double getAwayProbability() { return awayProbability; }
    public void setAwayProbability(double awayProbability) { this.awayProbability = awayProbability; }
    public int getAwayMinMs() { return awayMinMs; }
    public void setAwayMinMs(int awayMinMs) { this.awayMinMs = awayMinMs; }
    public int getAwayMaxMs() { return awayMaxMs; }
    public void setAwayMaxMs(int awayMaxMs) { this.awayMaxMs = awayMaxMs; }
    public double getGroupDelayFactor() { return groupDelayFactor; }
    public void setGroupDelayFactor(double groupDelayFactor) { this.groupDelayFactor = groupDelayFactor; }
    public boolean isSplitEnabled() { return splitEnabled; }
    public void setSplitEnabled(boolean splitEnabled) { this.splitEnabled = splitEnabled; }
    public int getMaxParts() { return maxParts; }
    public void setMaxParts(int maxParts) { this.maxParts = maxParts; }
    public int getMaxPartChars() { return maxPartChars; }
    public void setMaxPartChars(int maxPartChars) { this.maxPartChars = maxPartChars; }
    public int getMaxReplyChars() { return maxReplyChars; }
    public void setMaxReplyChars(int maxReplyChars) { this.maxReplyChars = maxReplyChars; }
    public int getPartGapMinMs() { return partGapMinMs; }
    public void setPartGapMinMs(int partGapMinMs) { this.partGapMinMs = partGapMinMs; }
    public int getPartGapMaxMs() { return partGapMaxMs; }
    public void setPartGapMaxMs(int partGapMaxMs) { this.partGapMaxMs = partGapMaxMs; }
    public int getPerMinute() { return perMinute; }
    public void setPerMinute(int perMinute) { this.perMinute = perMinute; }
    public int getPer10Minutes() { return per10Minutes; }
    public void setPer10Minutes(int per10Minutes) { this.per10Minutes = per10Minutes; }
    public int getPerHour() { return perHour; }
    public void setPerHour(int perHour) { this.perHour = perHour; }
    public int getPerDay() { return perDay; }
    public void setPerDay(int perDay) { this.perDay = perDay; }
    public int getGlobalPerHour() { return globalPerHour; }
    public void setGlobalPerHour(int globalPerHour) { this.globalPerHour = globalPerHour; }
    public int getGlobalPerDay() { return globalPerDay; }
    public void setGlobalPerDay(int globalPerDay) { this.globalPerDay = globalPerDay; }
    public int getMinGapMs() { return minGapMs; }
    public void setMinGapMs(int minGapMs) { this.minGapMs = minGapMs; }
    public int getBurstSize() { return burstSize; }
    public void setBurstSize(int burstSize) { this.burstSize = burstSize; }
    public int getBurstCooldownMinMs() { return burstCooldownMinMs; }
    public void setBurstCooldownMinMs(int burstCooldownMinMs) { this.burstCooldownMinMs = burstCooldownMinMs; }
    public int getBurstCooldownMaxMs() { return burstCooldownMaxMs; }
    public void setBurstCooldownMaxMs(int burstCooldownMaxMs) { this.burstCooldownMaxMs = burstCooldownMaxMs; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public int getSimilarityHistory() { return similarityHistory; }
    public void setSimilarityHistory(int similarityHistory) { this.similarityHistory = similarityHistory; }
    public double getCrossChatSimilarityThreshold() { return crossChatSimilarityThreshold; }
    public void setCrossChatSimilarityThreshold(double crossChatSimilarityThreshold) { this.crossChatSimilarityThreshold = crossChatSimilarityThreshold; }
    public boolean isRegenerateOnRepeat() { return regenerateOnRepeat; }
    public void setRegenerateOnRepeat(boolean regenerateOnRepeat) { this.regenerateOnRepeat = regenerateOnRepeat; }
    public boolean isStyleEnabled() { return styleEnabled; }
    public void setStyleEnabled(boolean styleEnabled) { this.styleEnabled = styleEnabled; }
    public int getEmojiLimit() { return emojiLimit; }
    public void setEmojiLimit(int emojiLimit) { this.emojiLimit = emojiLimit; }
    public double getDropRate() { return dropRate; }
    public void setDropRate(double dropRate) { this.dropRate = dropRate; }
    public boolean isPromptEnabled() { return promptEnabled; }
    public void setPromptEnabled(boolean promptEnabled) { this.promptEnabled = promptEnabled; }
}
