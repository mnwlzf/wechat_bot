package com.example.wechatbot.flow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时流水：把「一条微信消息进来 → 排队 → 调模型 → 排期 → 发出去」的每一步记在内存环形缓冲里。
 *
 * <p>为什么要这个：配额排队、群消息聚合、作息免打扰都会把回复往后推，但推开之后界面上只有一条
 * 静止的「已接收」，看不出到底是哪一层在等、还要等多久。这里把这些等待显式记下来，
 * 管理端「实时流水」页轮询就能看到当前的排队情况和数据流动。</p>
 *
 * <p>刻意做成旁路：全部是内存操作、固定容量、不落库、不重试。装配不上（单测里手动 new 出来的对象）
 * 就什么都不做，绝不因为「看日志」这件事影响正常回复。</p>
 */
@Component
public class FlowTraceService {

    /** 保留最近多少条事件。够看清一轮对话，也不至于吃掉内存。 */
    public static final int CAPACITY = 400;
    /** 一次最多返回多少条。 */
    public static final int MAX_PAGE = 200;

    /** 流水阶段；顺序大致等于一条消息在链路里的先后。 */
    public enum Stage {
        RECEIVED("收到消息"),
        SKIP("没回"),
        BUFFER("攒消息"),
        MERGE("被合并"),
        QUOTA("配额排队"),
        PACING("打字延迟"),
        LLM("调模型"),
        OUTBOX("进发送队列"),
        SENT("已发出"),
        FAILED("发送失败");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param waitMs     因为排队/延迟产生的等待（毫秒），可为 null
     * @param durationMs 这一步本身的耗时（比如模型调用），可为 null
     */
    public record Event(long seq, Instant at, String stage, String stageLabel, String chatId,
                        String chatName, String detail, Long waitMs, Long durationMs,
                        String preview) {}

    /** 当前正在等的东西：某个会话卡在哪个阶段、为什么、等到什么时候。 */
    public record Wait(String chatId, String chatName, String stage, String stageLabel,
                       String reason, Instant since, Instant until) {
        public long remainingMs(Instant now) {
            return until == null ? 0L : Math.max(0L, until.toEpochMilli() - now.toEpochMilli());
        }
    }

    public record Snapshot(Instant now, long cursor, Map<String, Long> counters,
                           List<Wait> waiting, List<Event> events) {}

    private final Deque<Event> events = new ArrayDeque<>();
    private final Map<String, Wait> waiting = new ConcurrentHashMap<>();
    /** chatId → 最近一次见到的名字，用来给只有 ID 的排队项补显示名。 */
    private final Map<String, String> names = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };
    private final Map<Stage, AtomicLong> counters = new EnumMap<>(Stage.class);
    private final AtomicLong sequence = new AtomicLong();

    private static final AtomicReference<FlowTraceService> INSTANCE = new AtomicReference<>();

    public FlowTraceService() {
        for (Stage stage : Stage.values()) {
            counters.put(stage, new AtomicLong());
        }
    }

    @PostConstruct
    void attach() {
        INSTANCE.set(this);
    }

    @PreDestroy
    void detach() {
        INSTANCE.compareAndSet(this, null);
    }

    // ------------------------------------------------------------------ 静态旁路

    /** 没装配（单测、非 Spring 场景）时返回 null，调用方直接跳过，连字符串都不用拼。 */
    public static FlowTraceService active() {
        return INSTANCE.get();
    }

    public static void mark(Stage stage, String chatId, String chatName, String detail, String preview) {
        mark(stage, chatId, chatName, detail, preview, null, null);
    }

    public static void mark(Stage stage, String chatId, String chatName, String detail, String preview,
                            Long waitMs, Long durationMs) {
        FlowTraceService service = INSTANCE.get();
        if (service != null) {
            service.record(stage, chatId, chatName, detail, preview, waitMs, durationMs);
        }
    }

    /** 记一条「正在等」的状态；同一会话同一阶段只留最新一条。 */
    public static void waiting(Stage stage, String chatId, String chatName, String reason, Instant until) {
        FlowTraceService service = INSTANCE.get();
        if (service != null) {
            service.setWaiting(stage, chatId, chatName, reason, until);
        }
    }

    public static void clearWaiting(String chatId, Stage stage) {
        FlowTraceService service = INSTANCE.get();
        if (service != null) {
            service.dropWaiting(chatId, stage);
        }
    }

    // ------------------------------------------------------------------ 写入

    public void record(Stage stage, String chatId, String chatName, String detail, String preview,
                       Long waitMs, Long durationMs) {
        long seq = sequence.incrementAndGet();
        Event event = new Event(seq, Instant.now(), stage.name(), stage.label(),
                blankToNull(chatId), blankToNull(chatName), detail, waitMs, durationMs,
                abbreviate(preview, 120));
        synchronized (events) {
            events.addLast(event);
            while (events.size() > CAPACITY) {
                events.pollFirst();
            }
        }
        counters.get(stage).incrementAndGet();
        backfillName(chatId, chatName);
    }

    /** 前面记的等待可能只有 chatId（比如伪装层不知道群名），后面知道了就补上。 */
    private void backfillName(String chatId, String chatName) {
        if (chatId == null || chatName == null || chatName.isBlank()) {
            return;
        }
        synchronized (names) {
            names.put(chatId, chatName);
        }
        for (Map.Entry<String, Wait> entry : waiting.entrySet()) {
            Wait wait = entry.getValue();
            if (chatId.equals(wait.chatId()) && wait.chatName() == null) {
                waiting.put(entry.getKey(), new Wait(wait.chatId(), chatName, wait.stage(),
                        wait.stageLabel(), wait.reason(), wait.since(), wait.until()));
            }
        }
    }

    /** 这个会话最近一次被看到的名字，没有就返回 null。 */
    private String knownName(String chatId) {
        if (chatId == null) {
            return null;
        }
        synchronized (names) {
            return names.get(chatId);
        }
    }

    public void setWaiting(Stage stage, String chatId, String chatName, String reason, Instant until) {
        String key = key(chatId, stage);
        Wait previous = waiting.get(key);
        Instant since = previous == null ? Instant.now() : previous.since();
        String resolvedName = blankToNull(chatName);
        if (resolvedName == null) {
            resolvedName = knownName(chatId);
        }
        waiting.put(key, new Wait(blankToNull(chatId), resolvedName, stage.name(),
                stage.label(), reason, since, until));
    }

    public void dropWaiting(String chatId, Stage stage) {
        waiting.remove(key(chatId, stage));
    }

    private static String key(String chatId, Stage stage) {
        return (chatId == null ? "" : chatId) + "|" + stage.name();
    }

    // ------------------------------------------------------------------ 读取

    /**
     * @param since 只要 seq 大于它的新事件（前端增量追加）；0 表示给最近一批
     * @param limit 这次最多返回多少条
     */
    public Snapshot snapshot(long since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Event> picked = new ArrayList<>();
        long cursor;
        synchronized (events) {
            cursor = events.isEmpty() ? sequence.get() : events.peekLast().seq();
            for (Event event : events) {
                if (event.seq() > since) {
                    picked.add(event);
                }
            }
        }
        if (picked.size() > safeLimit) {
            picked = new ArrayList<>(picked.subList(picked.size() - safeLimit, picked.size()));
        }
        Instant now = Instant.now();
        List<Wait> waits = new ArrayList<>(waiting.values());
        // 已经过期的等待不再显示（比如闹钟到点了但那一轮回复已经结束）
        waits.removeIf(item -> item.until() != null && item.until().isBefore(now.minusSeconds(2)));
        waits.sort(Comparator.comparing(Wait::until, Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, Long> counterView = new LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            counterView.put(stage.name(), counters.get(stage).get());
        }
        return new Snapshot(now, cursor, counterView, List.copyOf(waits), List.copyOf(picked));
    }

    /** 单测用：清空。 */
    void reset() {
        synchronized (events) {
            events.clear();
        }
        waiting.clear();
        sequence.set(0);
        counters.values().forEach(counter -> counter.set(0));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.replace('\n', ' ').strip();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
