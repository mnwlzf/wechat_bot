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
        Deque<Long> sends = perChat.computeIfAbsent(chatId == null ? "" : chatId, key -> new ArrayDeque<>());
        long chatEarliest;
        synchronized (sends) {
            prune(sends, now);
            chatEarliest = earliestFor(sends, now);
        }
        long globalEarliest;
        synchronized (globalLock) {
            prune(global, now);
            globalEarliest = earliestGlobal(now);
        }
        return Math.max(now, Math.max(chatEarliest, globalEarliest));
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

    private long earliestFor(Deque<Long> sends, long now) {
        long earliest = now;
        earliest = Math.max(earliest, window(sends, now, MINUTE, properties.getPerMinute()));
        earliest = Math.max(earliest, window(sends, now, TEN_MINUTES, properties.getPer10Minutes()));
        earliest = Math.max(earliest, window(sends, now, HOUR, properties.getPerHour()));
        earliest = Math.max(earliest, window(sends, now, DAY, properties.getPerDay()));

        if (!sends.isEmpty()) {
            long last = sends.peekLast();
            earliest = Math.max(earliest, last + Math.max(0, properties.getMinGapMs()));
        }
        earliest = Math.max(earliest, burstCooldown(sends, now));
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
    private long burstCooldown(Deque<Long> sends, long now) {
        int burst = Math.max(2, properties.getBurstSize());
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
