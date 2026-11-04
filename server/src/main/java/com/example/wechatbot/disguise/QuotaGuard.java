package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发送配额与爆发冷却。
 *
 * <p>风控最先看的就是频率：秒回、连发、跨会话同一秒、全天均匀输出。
 * 这里同时限制 单会话/全局 的 分钟、10 分钟、小时、天 四个窗口，
 * 并在一轮对话里发得太多时强制"歇一会儿"。</p>
 *
 * <p>单实例内存实现，进程重启后归零；对"账号级"长期画像而言，
 * 重启本身也会让节奏归零，这是可接受的。</p>
 */
@Component
public class QuotaGuard {

    private static final long MINUTE = 60_000L;
    private static final long TEN_MINUTES = 600_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private final DisguiseProperties properties;
    private final Map<String, Deque<Long>> perChat = new ConcurrentHashMap<>();
    private final Deque<Long> global = new ArrayDeque<>();
    private final Object globalLock = new Object();

    public QuotaGuard(DisguiseProperties properties) {
        this.properties = properties;
    }

    /** 返回该会话允许发送的最早时刻（epoch millis），已可发送则返回 now。 */
    public long earliestAllowed(String chatId, long now) {
        return earliestAllowed(properties, chatId, now);
    }

    /** 按该会话生效的策略算配额：某些群可以放宽或收紧频率。 */
    public long earliestAllowed(DisguiseProperties policy, String chatId, long now) {
        Deque<Long> sends = perChat.computeIfAbsent(chatId == null ? "" : chatId, key -> new ArrayDeque<>());
        long chatEarliest;
        synchronized (sends) {
            prune(sends, now);
            chatEarliest = earliestFor(policy, sends, now);
        }
        long globalEarliest;
        synchronized (globalLock) {
            prune(global, now);
            globalEarliest = earliestGlobal(now);
        }
        return Math.max(now, Math.max(chatEarliest, globalEarliest));
    }

    /** 排队原因，管理端「实时流水」用来说明「为什么这条还没发」。 */
    public record Explanation(long earliest, long waitMs, String reason, String limit) {}

    /**
     * 和 {@link #earliestAllowed} 同样的规则，但同时告诉调用方「是谁把它压住的」：
     * 每分钟上限、最小间隔、爆发冷却、还是全局每小时上限。
     */
    public Explanation explain(DisguiseProperties policy, String chatId, long now) {
        Deque<Long> sends = perChat.computeIfAbsent(chatId == null ? "" : chatId, key -> new ArrayDeque<>());
        long chatEarliest;
        String chatReason = null;
        String chatLimit = null;
        synchronized (sends) {
            prune(sends, now);
            chatEarliest = now;
            long[][] windows = {
                    {MINUTE, policy.getPerMinute()},
                    {TEN_MINUTES, policy.getPer10Minutes()},
                    {HOUR, policy.getPerHour()},
                    {DAY, policy.getPerDay()},
            };
            String[] labels = {"每分钟上限", "10 分钟上限", "每小时上限", "每天上限"};
            for (int i = 0; i < windows.length; i++) {
                long boundary = window(sends, now, windows[i][0], (int) windows[i][1]);
                if (boundary > chatEarliest) {
                    chatEarliest = boundary;
                    chatReason = labels[i] + " " + windows[i][1] + " 条";
                    chatLimit = labels[i];
                }
            }
            if (!sends.isEmpty()) {
                long gap = sends.peekLast() + Math.max(0, policy.getMinGapMs());
                if (gap > chatEarliest) {
                    chatEarliest = gap;
                    chatReason = "最小发送间隔 " + Math.max(0, policy.getMinGapMs()) + "ms";
                    chatLimit = "最小发送间隔";
                }
            }
            long burst = burstCooldown(policy, sends, now);
            if (burst > chatEarliest) {
                chatEarliest = burst;
                chatReason = "连发太快，强制冷却";
                chatLimit = "爆发冷却";
            }
        }
        long globalEarliest;
        String globalReason = null;
        synchronized (globalLock) {
            prune(global, now);
            globalEarliest = now;
            long[][] windows = {
                    {HOUR, properties.getGlobalPerHour()},
                    {DAY, properties.getGlobalPerDay()},
            };
            String[] labels = {"全局每小时上限", "全局每天上限"};
            for (int i = 0; i < windows.length; i++) {
                long boundary = window(global, now, windows[i][0], (int) windows[i][1]);
                if (boundary > globalEarliest) {
                    globalEarliest = boundary;
                    globalReason = labels[i] + " " + windows[i][1] + " 条";
                }
            }
        }
        long earliest = Math.max(now, Math.max(chatEarliest, globalEarliest));
        boolean globalWins = globalEarliest > chatEarliest;
        String reason = globalWins ? globalReason : chatReason;
        String limit = globalWins ? "账号级上限" : chatLimit;
        return new Explanation(earliest, Math.max(0L, earliest - now),
                reason == null ? "无需排队" : reason, limit);
    }

    /** 记录一次（可以是未来时刻的）发送计划。 */
    public void record(String chatId, long atMillis) {
        Deque<Long> sends = perChat.computeIfAbsent(chatId == null ? "" : chatId, key -> new ArrayDeque<>());
        synchronized (sends) {
            sends.addLast(atMillis);
        }
        synchronized (globalLock) {
            global.addLast(atMillis);
        }
    }

    public int sentInLastDay(String chatId) {
        Deque<Long> sends = perChat.get(chatId == null ? "" : chatId);
        if (sends == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (sends) {
            prune(sends, now);
            return sends.size();
        }
    }

    public int globalSentInLastDay() {
        synchronized (globalLock) {
            prune(global, System.currentTimeMillis());
            return global.size();
        }
    }

    private long earliestFor(DisguiseProperties policy, Deque<Long> sends, long now) {
        long earliest = now;
        earliest = Math.max(earliest, window(sends, now, MINUTE, policy.getPerMinute()));
        earliest = Math.max(earliest, window(sends, now, TEN_MINUTES, policy.getPer10Minutes()));
        earliest = Math.max(earliest, window(sends, now, HOUR, policy.getPerHour()));
        earliest = Math.max(earliest, window(sends, now, DAY, policy.getPerDay()));

        if (!sends.isEmpty()) {
            long last = sends.peekLast();
            earliest = Math.max(earliest, last + Math.max(0, policy.getMinGapMs()));
        }
        earliest = Math.max(earliest, burstCooldown(policy, sends, now));
        return earliest;
    }

    private long earliestGlobal(long now) {
        long earliest = now;
        earliest = Math.max(earliest, window(global, now, HOUR, properties.getGlobalPerHour()));
        earliest = Math.max(earliest, window(global, now, DAY, properties.getGlobalPerDay()));
        return earliest;
    }

    /**
     * 最近 5 分钟内已经发满 burstSize 条时，强制冷却一段时间。
     * 冷却长度按会话稳定取值，避免每次都在同一秒恢复。
     */
    private long burstCooldown(DisguiseProperties policy, Deque<Long> sends, long now) {
        int burst = Math.max(2, policy.getBurstSize());
        int recent = 0;
        long oldestRecent = Long.MAX_VALUE;
        for (Long at : sends) {
            if (at > now) {
                continue;
            }
            if (now - at < TEN_MINUTES) {
                recent++;
                oldestRecent = Math.min(oldestRecent, at);
            }
        }
        if (recent < burst || oldestRecent == Long.MAX_VALUE) {
            return now;
        }
        int min = Math.max(1_000, properties.getBurstCooldownMinMs());
        int max = Math.max(min + 1, properties.getBurstCooldownMaxMs());
        return oldestRecent + min + (long) ((max - min) * 0.5);
    }

    private long window(Deque<Long> sends, long now, long span, int limit) {
        if (limit <= 0) {
            return now;
        }
        int count = 0;
        long boundary = Long.MAX_VALUE;
        for (Long at : sends) {
            if (now - at < span) {
                count++;
                if (count == limit) {
                    boundary = at;
                }
            }
        }
        if (count < limit) {
            return now;
        }
        return boundary + span;
    }

    private void prune(Deque<Long> sends, long now) {
        while (!sends.isEmpty() && now - sends.peekFirst() > DAY) {
            sends.pollFirst();
        }
        while (!sends.isEmpty() && sends.peekFirst() > now + DAY) {
            sends.pollFirst();
        }
    }
}
