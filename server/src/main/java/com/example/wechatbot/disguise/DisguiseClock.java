package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * 在线作息模型。
 *
 * <p>真人的在线状态不是“永远秒回”：有睡眠窗口、有随机离开的时段、有午休。
 * 这些窗口按 (机器, 日期) 做确定性随机，所以同一天内反复查询结果一致，
 * 不会出现“刚离开又在线”的抖动，但每天又不一样。</p>
 */
@Component
public class DisguiseClock {

    public enum State {
        ACTIVE,
        AWAY,
        ASLEEP
    }

    public record Window(Instant start, Instant end, String kind) {
        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    private final DisguiseProperties properties;
    private final ZoneId zone;
    private final long seed;

    public DisguiseClock(DisguiseProperties properties) {
        this.properties = properties;
        this.zone = zoneOf(properties.getZone());
        this.seed = seedOf();
    }

    public State state(Instant now) {
        if (!properties.isScheduleEnabled()) {
            return State.ACTIVE;
        }
        Window hit = windowAt(now);
        if (hit == null) {
            return State.ACTIVE;
        }
        return "SLEEP".equals(hit.kind()) ? State.ASLEEP : State.AWAY;
    }

    public boolean canReply(Instant now) {
        return state(now) == State.ACTIVE;
    }

    /**
     * 当前不可回复时，返回最近的“恢复在线”时刻；已在线则原样返回 now。
     */
    public Instant nextActiveAt(Instant now) {
        if (!properties.isScheduleEnabled() || canReply(now)) {
            return now;
        }
        Instant cursor = now;
        for (int i = 0; i < 12; i++) {
            Window hit = windowAt(cursor);
            if (hit == null) {
                return cursor;
            }
            cursor = hit.end().plusSeconds(1);
        }
        return cursor;
    }

    /**
     * 排期时额外加一点“刚拿起手机”的随机余量，避免所有人都在整点同一秒收到消息。
     */
    public Instant resumeWithJitter(Instant resumeAt) {
        long jitter = 5 + (long) (Math.random() * 95);
        return resumeAt.plusSeconds(jitter);
    }

    public List<Window> windowsFor(LocalDate date) {
        List<Window> windows = new ArrayList<>(2 + Math.max(0, properties.getAwayBlocksPerDay()));
        LocalTime quietStart = parseTime(properties.getQuietStart(), LocalTime.of(0, 30));
        LocalTime quietEnd = parseTime(properties.getQuietEnd(), LocalTime.of(7, 30));
        ZonedDateTime sleepStart = date.atTime(quietStart).atZone(zone);
        ZonedDateTime sleepEnd = quietEnd.isAfter(quietStart)
                ? date.atTime(quietEnd).atZone(zone)
                : date.plusDays(1).atTime(quietEnd).atZone(zone);
        windows.add(new Window(sleepStart.toInstant(), sleepEnd.toInstant(), "SLEEP"));

        int blocks = Math.max(0, Math.min(properties.getAwayBlocksPerDay(), 6));
        if (blocks == 0) {
            return windows;
        }
        int min = Math.max(5, properties.getAwayBlockMinMinutes());
        int max = Math.max(min, properties.getAwayBlockMaxMinutes());
        SplittableRandom random = new SplittableRandom(mix(seed, date.toEpochDay()));
        for (int i = 0; i < blocks; i++) {
            int duration = min + random.nextInt(max - min + 1);
            int startMinute = 9 * 60 + random.nextInt(Math.max(1, 13 * 60 - duration));
            Instant start = date.atStartOfDay(zone).plusMinutes(startMinute).toInstant();
            windows.add(new Window(start, start.plusSeconds(duration * 60L), "AWAY"));
        }
        return windows;
    }

    private Window windowAt(Instant instant) {
        LocalDate date = instant.atZone(zone).toLocalDate();
        List<Window> candidates = new ArrayList<>();
        for (LocalDate day : List.of(date.minusDays(1), date, date.plusDays(1))) {
            for (Window window : windowsFor(day)) {
                if (window.contains(instant)) {
                    candidates.add(window);
                }
            }
        }
        return candidates.stream()
                .max(Comparator.comparing(Window::end))
                .orElse(null);
    }

    private ZoneId zoneOf(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value.trim());
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private long seedOf() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "localhost";
        }
        return mix(System.getProperty("user.name", "user").hashCode(), host.toLowerCase(Locale.ROOT).hashCode());
    }

    /** splitmix64 风格的混合，保证同一 (seed, day) 稳定。 */
    private static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
