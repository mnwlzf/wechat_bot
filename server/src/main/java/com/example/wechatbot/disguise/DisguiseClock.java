package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 在线作息模型。
 *
 * <p>真人的在线状态不是“永远秒回”：有睡眠窗口，也有由管理员手动配置的
 * “离开手机”时段。离线段按每日时间重复，支持跨零点。</p>
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

    public DisguiseClock(DisguiseProperties properties) {
        this.properties = properties;
        this.zone = zoneOf(properties.getZone());
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
        List<Window> windows = new ArrayList<>(1 + properties.getAwayBlocks().size());
        LocalTime quietStart = parseTime(properties.getQuietStart(), LocalTime.of(0, 30));
        LocalTime quietEnd = parseTime(properties.getQuietEnd(), LocalTime.of(7, 30));
        ZonedDateTime sleepStart = date.atTime(quietStart).atZone(zone);
        ZonedDateTime sleepEnd = quietEnd.isAfter(quietStart)
                ? date.atTime(quietEnd).atZone(zone)
                : date.plusDays(1).atTime(quietEnd).atZone(zone);
        windows.add(new Window(sleepStart.toInstant(), sleepEnd.toInstant(), "SLEEP"));

        if (!properties.isAwayBlocksEnabled()) {
            return windows;
        }
        for (String raw : properties.getAwayBlocks()) {
            AwayRange range = parseAwayRange(raw);
            if (range == null) {
                continue;
            }
            ZonedDateTime start = date.atTime(range.start()).atZone(zone);
            ZonedDateTime end = range.end().isAfter(range.start())
                    ? date.atTime(range.end()).atZone(zone)
                    : date.plusDays(1).atTime(range.end()).atZone(zone);
            windows.add(new Window(start.toInstant(), end.toInstant(), "AWAY"));
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

    private static AwayRange parseAwayRange(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split("-", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            return start.equals(end) ? null : new AwayRange(start, end);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private record AwayRange(LocalTime start, LocalTime end) {}
}
