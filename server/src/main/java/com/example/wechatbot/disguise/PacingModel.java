package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 回复延迟模型：看懂 → 想一下 → 打字。
 *
 * <p>固定延迟、整秒对齐、恒定速度都是典型的机器特征。这里用
 * “读消息 + 思考 + 按字数打字 + 昼夜节律 + 偶发长停顿”叠加，
 * 每条消息的延迟分布都不同，但同一个人对同一个聊天的手速保持稳定。</p>
 */
@Component
public class PacingModel {

    private final DisguiseProperties properties;
    private final ZoneId zone;

    public PacingModel(DisguiseProperties properties) {
        this.properties = properties;
        this.zone = ZoneId.of(properties.getZone() == null || properties.getZone().isBlank()
                ? "Asia/Shanghai" : properties.getZone());
    }

    public long delayMs(String chatId, String text, boolean group, Instant now) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int length = visibleLength(text);
        double speed = stableSpeed(chatId);

        double typing = length / speed * 1000.0;
        double jitter = clamp(random.nextGaussian() * properties.getTypingJitter(), -0.45, 0.6);
        typing *= 1.0 + jitter;

        int min = Math.max(300, properties.getReadDelayMinMs());
        int max = Math.max(min + 1, properties.getReadDelayMaxMs());
        double read = min + (max - min) * Math.pow(random.nextDouble(), 2.0);
        double think = properties.getThinkBaseMs() + Math.min(4500, length * 9.0);

        double total = read + think + typing;
        total *= circadian(now);
        if (group) {
            total *= Math.max(0.5, properties.getGroupDelayFactor());
        }
        if (random.nextDouble() < properties.getLongPauseProbability()) {
            total += random.nextDouble(15_000, Math.max(16_000, properties.getLongPauseMaxMs()));
        }
        if (random.nextDouble() < properties.getAwayProbability()) {
            total += random.nextDouble(properties.getAwayMinMs(), Math.max(properties.getAwayMinMs() + 1, properties.getAwayMaxMs()));
        }
        long capped = Math.min((long) total, properties.getMaxDelayMs());
        return Math.max(800, capped);
    }

    /** 两条气泡之间的间隔：手比脑快，通常 1~3 秒。 */
    public long partGapMs(String chatId, String nextPart) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int min = Math.max(200, properties.getPartGapMinMs());
        int max = Math.max(min + 1, properties.getPartGapMaxMs());
        double gap = min + (max - min) * random.nextDouble();
        gap += Math.min(1200, visibleLength(nextPart) * 8.0);
        return (long) gap;
    }

    /** 同一个人对同一会话的打字速度稳定在 ±15% 内，避免每条消息速度都不一样。 */
    private double stableSpeed(String chatId) {
        double factor = 0.85 + 0.30 * hash01(chatId == null ? "" : chatId);
        double speed = properties.getCharsPerSecond() * factor;
        return Math.max(1.1, Math.min(9.0, speed));
    }

    private double circadian(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        int hour = local.getHour();
        if (hour >= 23 || hour < 7) {
            return 1.65;
        }
        if (hour < 9) {
            return 1.35;
        }
        if (hour == 12 || hour == 13) {
            return 1.25;
        }
        if (hour >= 22) {
            return 1.3;
        }
        return 1.0;
    }

    private static int visibleLength(String text) {
        if (text == null) {
            return 0;
        }
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                length++;
            }
        }
        return Math.min(length, 600);
    }

    static double hash01(String value) {
        long h = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) {
            h = 31 * h + value.charAt(i);
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
