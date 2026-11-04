package com.example.wechatbot.disguise;

import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.flow.FlowTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DisguiseService.class);

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
    private final SendPolicyResolver policies;
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

    /** 只有全局配置时使用（单测、以及没接数据库的场景）。 */
    public DisguiseService(DisguiseProperties properties,
                           DisguiseClock clock,
                           StyleNormalizer normalizer,
                           ReplyComposer composer,
                           PacingModel pacing,
                           QuotaGuard quota,
                           RepetitionGuard repetition) {
        this(properties, clock, normalizer, composer, pacing, quota, repetition, chatId -> properties);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DisguiseService(DisguiseProperties properties,
                           DisguiseClock clock,
                           StyleNormalizer normalizer,
                           ReplyComposer composer,
                           PacingModel pacing,
                           QuotaGuard quota,
                           RepetitionGuard repetition,
                           SendPolicyResolver policies) {
        this.properties = properties;
        this.clock = clock;
        this.normalizer = normalizer;
        this.composer = composer;
        this.pacing = pacing;
        this.quota = quota;
        this.repetition = repetition;
        this.policies = policies;
    }

    public Plan plan(String chatId, ChatType chatType, String rawReply, Instant now) {
        return build(chatId, chatType, rawReply, now, false);
    }

    /** 调试预览：走完全部逻辑，但不占用配额、不写入去重历史。 */
    public Plan preview(String chatId, ChatType chatType, String rawReply, Instant now) {
        return build(chatId, chatType, rawReply, now, true);
    }

    /**
     * 控制类消息（人设名单、编号提示等）：<b>原样单条</b>发出。
     *
     * <p>正常回复要过文风清洗 + 分条，那是为了像真人；但编号名单被拆成几条独立的微信消息后，
     * 手机上就只剩一串没有编号的碎片。所以这条路径只保留「延迟」这一层拟人，
     * 不清洗（不清空行、不合并空格）、不拆条，一条消息发完。</p>
     */
    public Plan planVerbatim(String chatId, ChatType chatType, String rawReply, Instant now) {
        if (rawReply == null || rawReply.isBlank()) {
            return drop(chatId, now, "EMPTY", "内容为空", false);
        }
        String text = rawReply.strip();
        DisguiseProperties policy = properties.isEnabled() ? policies.forChat(chatId) : properties;
        long baseDelay = properties.isEnabled()
                ? pacing.delayMs(policy, chatId, text, chatType == ChatType.GROUP, now)
                : 0L;
        long cap = Math.max(1000, policy.getMaxFirstDelayMs());
        Instant first = now.plusMillis(Math.min(Math.max(0L, baseDelay), cap));
        QuotaGuard.Explanation explanation = quota.explain(policy, chatId, now.toEpochMilli());
        Instant earliest = Instant.ofEpochMilli(explanation.earliest());
        if (earliest.isAfter(first)) {
            first = now.plusMillis(cap);
            FlowTraceService.mark(FlowTraceService.Stage.QUOTA, chatId, null,
                    "配额排队：" + explanation.reason() + "（原样回复已压到上限）",
                    null, explanation.waitMs(), null);
        }
        quota.record(chatId, first.toEpochMilli());
        return new Plan(List.of(new PlannedPart(text, first)), "SEND", "原样单条发送");
    }

    public String previewStyle(String rawReply) {
        return capTotal(properties, normalizer.normalize(properties, rawReply));
    }

    /** 用该会话生效的策略预览文风清洗结果。 */
    public String previewStyle(String chatId, String rawReply) {
        DisguiseProperties policy = policies.forChat(chatId);
        return capTotal(policy, normalizer.normalize(policy, rawReply));
    }

    private Plan build(String chatId, ChatType chatType, String rawReply, Instant now, boolean dryRun) {
        if (rawReply == null || rawReply.isBlank()) {
            return drop(chatId, now, "EMPTY", "模型返回空内容", dryRun);
        }
        if (!properties.isEnabled()) {
            String text = rawReply.strip();
            return new Plan(List.of(new PlannedPart(text, now)), "DIRECT", "伪装层已关闭");
        }

        // 该会话生效的发信策略：默认策略与按群覆盖在 SendPolicyService 里已经合并好
        DisguiseProperties policy = policies.forChat(chatId);
        String styled = capTotal(policy, normalizer.normalize(policy, rawReply));
        if (styled.isBlank()) {
            return drop(chatId, now, "EMPTY", "清洗后为空", dryRun);
        }

        double dropRate = Math.max(0, Math.min(1, policy.getDropRate()));
        if (dropRate > 0 && ThreadLocalRandom.current().nextDouble() < dropRate) {
            return drop(chatId, now, "DROP_RANDOM", "模拟看到了没回", dryRun);
        }

        List<String> parts = composer.split(policy, styled);
        if (parts.isEmpty()) {
            return drop(chatId, now, "EMPTY", "分条后为空", dryRun);
        }

        boolean group = chatType == ChatType.GROUP;
        long baseDelay = pacing.delayMs(policy, chatId, parts.getFirst(), group, now);
        QuotaGuard.Explanation explanation = quota.explain(policy, chatId, now.toEpochMilli());
        Instant earliest = Instant.ofEpochMilli(explanation.earliest());
        Instant wake = clock.nextActiveAt(now);
        if (wake.isAfter(now)) {
            wake = clock.resumeWithJitter(wake);
        }

        Instant first = now.plusMillis(baseDelay);
        if (earliest.isAfter(first)) {
            first = earliest;
        }

        // 硬上限：配额排队算出来的等待不能超过 maxFirstDelayMs（默认 15 秒）。
        // 一个活跃群很容易撞上「每分钟 N 条」的配额，不封顶的话回复会被顺延到一分钟以后，人早聊别的话题了。
        // 注意顺序：先压上限，再让作息/免打扰往后推——半夜不发就是半夜不发。
        Instant hardCap = now.plusMillis(Math.max(1000, policy.getMaxFirstDelayMs()));
        boolean capped = false;
        if (first.isAfter(hardCap)) {
            capped = true;
            if (!dryRun) {
                log.info("伪装层排期被硬上限截断：{} 秒 → {} 秒（chat={}，配额排队导致）",
                        Duration.between(now, first).toSeconds(),
                        Duration.between(now, hardCap).toSeconds(), chatId);
            }
            first = hardCap;
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
                cursor = cursor.plusMillis(pacing.partGapMs(policy, chatId, text));
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
            traceQueue(chatId, now, explanation, baseDelay, capped, first, deferSeconds, scheduled.size());
        }
        return new Plan(List.copyOf(scheduled), "SEND",
                "延迟 " + deferSeconds + " 秒，分 " + scheduled.size() + " 条"
                        + (capped ? "（配额排队已压到上限）" : ""));
    }

    /**
     * 把「这条为什么没马上发」写进实时流水：打字延迟、配额排队（含是哪条上限）、
     * 还是被硬上限截断了。管理端「实时流水」页就是靠这些看排队的。
     */
    private void traceQueue(String chatId, Instant now, QuotaGuard.Explanation explanation,
                            long baseDelay, boolean capped, Instant first, long deferSeconds, int parts) {
        if (explanation.waitMs() > 0) {
            String reason = explanation.reason() + (capped ? "（已压到上限）" : "");
            FlowTraceService.mark(FlowTraceService.Stage.QUOTA, chatId, null,
                    "配额排队：" + reason + "，顺延 " + (deferSeconds == 0 ? 1 : deferSeconds) + " 秒",
                    null, explanation.waitMs(), null);
            FlowTraceService.waiting(FlowTraceService.Stage.QUOTA, chatId, null, reason, first);
        } else {
            FlowTraceService.mark(FlowTraceService.Stage.PACING, chatId, null,
                    "打字延迟 " + Math.max(0, baseDelay / 100) / 10.0 + " 秒后发第一条",
                    null, baseDelay, null);
        }
        if (deferSeconds > 20) {
            FlowTraceService.waiting(FlowTraceService.Stage.PACING, chatId, null,
                    "共 " + parts + " 条，顺延 " + deferSeconds + " 秒", first);
        }
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
        return promptSuffix(properties);
    }

    /** 按该会话生效的策略决定是否追加口语约束（有的群不需要这段）。 */
    public String promptSuffix(String chatId) {
        return promptSuffix(policies.forChat(chatId));
    }

    private String promptSuffix(DisguiseProperties policy) {
        if (!policy.isPromptEnabled() || !properties.isEnabled()) {
            return "";
        }
        // 这一段只管「格式」，不管「性格」。
        // 早先这里还写了「短句、句末不加句号、语气词自然一点」，结果是所有人设都被拉成同一个腔调：
        // 换成高冷人设也照样「嗯、行、哈」地贫，用户会觉得「人设切换没生效」。性格交给人设文件。
        return """
                微信聊天格式约束（只管格式，不管性格：口吻、称呼、句子长短、标点习惯、语气词一律以人设设定为准；
                下面这些要求和人设冲突时，听人设的）：
                - 一条消息只讲一个意思，能一句说完就别展开，别写成段落。
                - 不要 Markdown：不用 #、**、- 列表、代码块、表格。
                - 不要“首先/其次/最后/总之/综上/需要注意的是”这类结构词。
                - 不要说“作为AI/作为语言模型/我是助手”，不要解释自己是怎么工作的。
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

    private String capTotal(DisguiseProperties policy, String text) {
        int max = Math.max(60, policy.getMaxReplyChars());
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
