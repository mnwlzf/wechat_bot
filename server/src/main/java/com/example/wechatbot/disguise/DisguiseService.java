package com.example.wechatbot.disguise;

import com.example.wechatbot.domain.ChatType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 伪装层门面：把一条"模型回复"变成"真人会怎么发"。
 *
 * <p>顺序很重要：先洗文风 → 再分条 → 再看在线状态 → 再排队配额 →
 * 最后算打字延迟。任何一步都可能把消息推迟甚至丢弃，这是刻意的：
 * 秒回、长文、固定节奏才是最先被识别的特征。</p>
 */
@Service
public class DisguiseService {

    public record PlannedPart(String text, Instant sendAt) {}

    public record Plan(List<PlannedPart> parts, String decision, String note) {
        public boolean dropped() {
            return parts.isEmpty();
        }

        public Instant firstSendAt() {
            return parts.isEmpty() ? null : parts.getFirst().sendAt();
        }

        public String joinedText() {
            return String.join("\n\n", parts.stream().map(PlannedPart::text).toList());
        }
    }

    public record Decision(String at, String chatId, String state, String decision,
                           int parts, long delaySeconds, String preview) {}

    public record Status(boolean enabled, String state, String zone, String quietHours,
                         String nextActiveAt, int partsLimit, int perMinute, int per10Minutes,
                         int perHour, int perDay, long planned, long dropped, long deferred,
                         long repetitive, int globalSentToday, List<Decision> recent) {}

    private static final int RECENT_LIMIT = 40;

    private final DisguiseProperties properties;
    private final DisguiseClock clock;
    private final StyleNormalizer normalizer;
    private final ReplyComposer composer;
    private final PacingModel pacing;
    private final QuotaGuard quota;
    private final RepetitionGuard repetition;

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong repetitive = new AtomicLong();
    private final Deque<Decision> recent = new ArrayDeque<>();

    public DisguiseService(DisguiseProperties properties,
                           DisguiseClock clock,
                           StyleNormalizer normalizer,
                           ReplyComposer composer,
                           PacingModel pacing,
                           QuotaGuard quota,
                           RepetitionGuard repetition) {
        this.properties = properties;
        this.clock = clock;
        this.normalizer = normalizer;
        this.composer = composer;
        this.pacing = pacing;
        this.quota = quota;
        this.repetition = repetition;
    }

    public Plan plan(String chatId, ChatType chatType, String rawReply, Instant now) {
        return build(chatId, chatType, rawReply, now, false);
    }

    /** 调试预览：走完全部逻辑，但不占用配额、不写入去重历史。 */
    public Plan preview(String chatId, ChatType chatType, String rawReply, Instant now) {
        return build(chatId, chatType, rawReply, now, true);
    }

    public String previewStyle(String rawReply) {
        return capTotal(normalizer.normalize(rawReply));
    }

    private Plan build(String chatId, ChatType chatType, String rawReply, Instant now, boolean dryRun) {
        if (rawReply == null || rawReply.isBlank()) {
            return drop(chatId, now, "EMPTY", "模型返回空内容", dryRun);
        }
        if (!properties.isEnabled()) {
            String text = rawReply.strip();
            return new Plan(List.of(new PlannedPart(text, now)), "DIRECT", "伪装层已关闭");
        }

        String styled = previewStyle(rawReply);
        if (styled.isBlank()) {
            return drop(chatId, now, "EMPTY", "清洗后为空", dryRun);
        }

        double dropRate = Math.max(0, Math.min(1, properties.getDropRate()));
        if (dropRate > 0 && ThreadLocalRandom.current().nextDouble() < dropRate) {
            return drop(chatId, now, "DROP_RANDOM", "模拟看到了没回", dryRun);
        }

        List<String> parts = composer.split(styled);
        if (parts.isEmpty()) {
            return drop(chatId, now, "EMPTY", "分条后为空", dryRun);
        }

        boolean group = chatType == ChatType.GROUP;
        long baseDelay = pacing.delayMs(chatId, parts.getFirst(), group, now);
        Instant earliest = Instant.ofEpochMilli(quota.earliestAllowed(chatId, now.toEpochMilli()));
        Instant wake = clock.nextActiveAt(now);
        if (wake.isAfter(now)) {
            wake = clock.resumeWithJitter(wake);
        }

        Instant first = now.plusMillis(baseDelay);
        if (earliest.isAfter(first)) {
            first = earliest;
        }
        if (wake.isAfter(first)) {
            first = wake;
        }

        long deferSeconds = Duration.between(now, first).toSeconds();
        long maxDefer = Math.max(60, properties.getMaxDeferMinutes()) * 60L;
        if (deferSeconds > maxDefer) {
            return drop(chatId, now, "DEFER_TOO_LONG",
                    "需顺延 " + deferSeconds + " 秒，超过上限 " + maxDefer + " 秒", dryRun);
        }
        if (!dryRun && deferSeconds > 20) {
            deferred.incrementAndGet();
        }

        List<PlannedPart> scheduled = new ArrayList<>(parts.size());
        Instant cursor = first;
        for (int i = 0; i < parts.size(); i++) {
            String text = parts.get(i);
            if (i > 0) {
                cursor = cursor.plusMillis(pacing.partGapMs(chatId, text));
            }
            scheduled.add(new PlannedPart(text, cursor));
            if (!dryRun) {
                quota.record(chatId, cursor.toEpochMilli());
            }
        }

        Decision decision = new Decision(now.toString(), chatId, clock.state(now).name(), "SEND",
                scheduled.size(), deferSeconds, preview(styled));
        if (!dryRun) {
            repetition.record(chatId, styled);
            planned.incrementAndGet();
            remember(decision);
        }
        return new Plan(List.copyOf(scheduled), "SEND",
                "延迟 " + deferSeconds + " 秒，分 " + scheduled.size() + " 条");
    }

    public boolean isRepetitive(String chatId, String text) {
        boolean hit = repetition.isRepetitive(chatId, text);
        if (hit) {
            repetitive.incrementAndGet();
        }
        return hit;
    }

    /** 追加到系统提示词的口语约束。 */
    public String promptSuffix() {
        if (!properties.isPromptEnabled() || !properties.isEnabled()) {
            return "";
        }
        return """
                微信聊天风格约束（优先级高于书面语习惯，违反会被对方觉得像机器人）：
                - 像用手机打字：短句、1~2 行一条，一条只讲一个意思；能一句说完就别展开。
                - 不要 Markdown：不用 #、**、- 列表、代码块、表格。
                - 不要“首先/其次/最后/总之/综上/需要注意的是”这类结构词。
                - 不要说“作为AI/作为语言模型/我是助手”，不要解释自己是怎么工作的。
                - 句末通常不加句号；少用感叹号；语气词自然一点（嗯、行、好、哈）。
                - 不要复述对方原话，不要每句都反问，不要写“希望对你有帮助”。
                - 内容确实需要分两条时，用空行隔开，每条都是完整的口语短句。
                - 不确定就直接说“不太确定”，不要编。
                """.strip();
    }

    public Status status() {
        Instant now = Instant.now();
        List<Decision> snapshot;
        synchronized (recent) {
            snapshot = new ArrayList<>(recent);
        }
        return new Status(
                properties.isEnabled(),
                clock.state(now).name(),
                properties.getZone(),
                properties.getQuietStart() + "-" + properties.getQuietEnd(),
                OffsetDateTime.ofInstant(clock.nextActiveAt(now), zone()).toString(),
                properties.getMaxParts(),
                properties.getPerMinute(),
                properties.getPer10Minutes(),
                properties.getPerHour(),
                properties.getPerDay(),
                planned.get(),
                dropped.get(),
                deferred.get(),
                repetitive.get(),
                quota.globalSentInLastDay(),
                snapshot
        );
    }

    private Plan drop(String chatId, Instant now, String decision, String note, boolean dryRun) {
        if (!dryRun) {
            dropped.incrementAndGet();
            remember(new Decision(now.toString(), chatId, clock.state(now).name(), decision, 0, 0, note));
        }
        return new Plan(List.of(), decision, note);
    }

    private void remember(Decision decision) {
        synchronized (recent) {
            recent.addFirst(decision);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeLast();
            }
        }
    }

    private String capTotal(String text) {
        int max = Math.max(60, properties.getMaxReplyChars());
        if (text.length() <= max) {
            return text;
        }
        int cut = max;
        for (int i = max; i > Math.max(0, max - 80); i--) {
            char ch = text.charAt(i - 1);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n') {
                cut = i;
                break;
            }
        }
        return text.substring(0, cut).strip();
    }

    private String preview(String text) {
        String single = text.replace('\n', ' ');
        return single.length() <= 40 ? single : single.substring(0, 40) + "…";
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(properties.getZone());
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
