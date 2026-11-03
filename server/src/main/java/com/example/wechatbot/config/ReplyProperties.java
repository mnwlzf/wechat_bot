package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群消息聚合（防连发）的开关与节奏（{@code app.reply.*}）。
 *
 * <p>群里经常一秒好几条：如果每条都单独触发一次生成，机器人就会像复读机一样连发，
 * 而且每条都要一次模型调用。所以先攒一小会儿（{@code debounceSeconds} 内没有新消息）再回一次，
 * 让一次回复覆盖整段对话；持续刷屏的群最多攒 {@code maxWaitSeconds} 就必须开口。</p>
 */
@ConfigurationProperties(prefix = "app.reply")
public class ReplyProperties {
    /** 关掉后恢复「每条消息单独触发」（老行为）。 */
    private boolean enabled = true;

    /** 最后一条消息之后静默多久才开始生成（秒）。 */
    private int debounceSeconds = 3;

    /** 同一个会话最多攒多久必须回一次（秒），避免刷屏群里永远等不到静默；0 = 不封顶。 */
    private int maxWaitSeconds = 12;

    /** 同时生成回复的会话数上限；同一个会话内部永远串行，不会两条回复抢跑。 */
    private int workers = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDebounceSeconds() {
        return debounceSeconds;
    }

    public void setDebounceSeconds(int debounceSeconds) {
        this.debounceSeconds = debounceSeconds;
    }

    public int getMaxWaitSeconds() {
        return maxWaitSeconds;
    }

    public void setMaxWaitSeconds(int maxWaitSeconds) {
        this.maxWaitSeconds = maxWaitSeconds;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }
}
