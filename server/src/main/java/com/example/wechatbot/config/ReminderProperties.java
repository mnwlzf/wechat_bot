package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提醒任务（{@code app.reminder.*}）：聊天里用一句话建、按间隔催、回话就停、有次数上限。
 *
 * <p>默认值按「别变成骚扰」来挑：最短 5 分钟一次、最多 6 次、单聊同时最多 3 个在等回执。
 * 这几个值是硬约束，聊天里说得再夸张也会被服务端夹住。</p>
 */
@ConfigurationProperties(prefix = "app.reminder")
public class ReminderProperties {
    /** 总开关：关掉后聊天里不再识别提醒指令，已有的提醒也不再发送。 */
    private boolean enabled = true;

    /** 是否允许在聊天里用自然语言创建 / 取消 / 查询提醒。 */
    private boolean createInChat = true;

    /** 回执是否允许用模型判断（否则只认停止词）。 */
    private boolean nlAck = true;

    /** 默认间隔（分钟）。 */
    private int defaultIntervalMinutes = 5;

    /** 默认次数上限。 */
    private int defaultMaxTimes = 6;

    /** 次数上限的硬顶：聊天里说「提醒我一万次」也只能到这里。 */
    private int hardMaxTimes = 20;

    /** 间隔下限（分钟），防止把对方刷屏。 */
    private int minIntervalMinutes = 5;

    /** 单个私聊同时「等待回执」的提醒数量上限。 */
    private int maxWaitingPerChat = 3;

    /** 单个私聊每天最多新建几个提醒。 */
    private int maxPerDayPerChat = 5;

    /** 次数用完时是否补一句收尾（「6 次都没回，我先不催了」）。 */
    private boolean finalMessage = true;

    /** 解析含糊时追问的状态保留多久（秒）。 */
    private int askBackSeconds = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isCreateInChat() { return createInChat; }
    public void setCreateInChat(boolean createInChat) { this.createInChat = createInChat; }
    public boolean isNlAck() { return nlAck; }
    public void setNlAck(boolean nlAck) { this.nlAck = nlAck; }
    public int getDefaultIntervalMinutes() { return defaultIntervalMinutes; }
    public void setDefaultIntervalMinutes(int defaultIntervalMinutes) { this.defaultIntervalMinutes = defaultIntervalMinutes; }
    public int getDefaultMaxTimes() { return defaultMaxTimes; }
    public void setDefaultMaxTimes(int defaultMaxTimes) { this.defaultMaxTimes = defaultMaxTimes; }
    public int getHardMaxTimes() { return hardMaxTimes; }
    public void setHardMaxTimes(int hardMaxTimes) { this.hardMaxTimes = hardMaxTimes; }
    public int getMinIntervalMinutes() { return minIntervalMinutes; }
    public void setMinIntervalMinutes(int minIntervalMinutes) { this.minIntervalMinutes = minIntervalMinutes; }
    public int getMaxWaitingPerChat() { return maxWaitingPerChat; }
    public void setMaxWaitingPerChat(int maxWaitingPerChat) { this.maxWaitingPerChat = maxWaitingPerChat; }
    public int getMaxPerDayPerChat() { return maxPerDayPerChat; }
    public void setMaxPerDayPerChat(int maxPerDayPerChat) { this.maxPerDayPerChat = maxPerDayPerChat; }
    public boolean isFinalMessage() { return finalMessage; }
    public void setFinalMessage(boolean finalMessage) { this.finalMessage = finalMessage; }
    public int getAskBackSeconds() { return askBackSeconds; }
    public void setAskBackSeconds(int askBackSeconds) { this.askBackSeconds = askBackSeconds; }
}
