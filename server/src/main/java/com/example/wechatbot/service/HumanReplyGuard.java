package com.example.wechatbot.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拟人模式的判定节流：同一个群在冷却时间内只问一次模型。
 *
 * <p>群里几秒钟就好几条消息，逐条都判断一次既贵又不像人（真人也不会每句话都在心里过一遍）。
 * 冷却期内的消息照常入库，只是不再触发判定；等冷却结束，用最新几条对话判一次即可。</p>
 *
 * <p>状态放内存：重启后最多多判一次，不值得为它落库。</p>
 */
@Component
public class HumanReplyGuard {

    private final Map<String, Long> lastDecision = new ConcurrentHashMap<>();

    /**
     * 申请一次判定机会。
     *
     * @return {@code true} 表示可以判定（并且已经把这次的时间记下来）；{@code false} 表示还在冷却中
     */
    public boolean acquire(String chatId, int cooldownSeconds) {
        return acquire(chatId, cooldownSeconds, System.currentTimeMillis());
    }

    /** package-private：便于单测直接注入时间。 */
    boolean acquire(String chatId, int cooldownSeconds, long nowMillis) {
        long cooldown = Math.max(0, cooldownSeconds) * 1000L;
        if (cooldown == 0) {
            return true;
        }
        String key = chatId == null ? "" : chatId;
        boolean[] granted = {false};
        lastDecision.compute(key, (ignored, previous) -> {
            if (previous != null && nowMillis - previous < cooldown) {
                return previous;
            }
            granted[0] = true;
            return nowMillis;
        });
        return granted[0];
    }

    /** 清空冷却状态（改配置或测试用）。 */
    public void reset() {
        lastDecision.clear();
    }
}
