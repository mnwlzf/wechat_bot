package com.example.wechatbot.service;

import com.example.wechatbot.config.ReplyProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群消息聚合：把一串连发的消息攒成一次回复。
 *
 * <p>群里经常一秒好几条，如果每条都单独触发一次生成，机器人会像复读机一样连着回好几段，
 * 而且每条都要一次模型调用。这里的规矩是：</p>
 * <ul>
 *   <li><b>静默才回</b>：最后一条消息之后安静 {@code debounceSeconds} 才真正生成，
 *       期间来的新消息只是把等待重置；</li>
 *   <li><b>但不会一直等</b>：从这串消息的第一条算起，攒满 {@code maxWaitSeconds} 就必须回一次，
 *       刷屏的群也不会永远不吭声；</li>
 *   <li><b>同一会话串行</b>：上一条回复还在生成时，新请求排队等它结束，不会两条抢跑；</li>
 *   <li><b>被合并的消息不留悬空状态</b>：触发回调让调用方把那条消息标成「已合并」。</li>
 * </ul>
 *
 * <p>生成放在自己的线程池里跑，不占用 {@code chatExecutor}（那里还有媒体确认等固定回复）。</p>
 */
@Component
public class ReplyDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ReplyDispatcher.class);
    /** 上一个回复还没生成完时，隔多久再看一眼。 */
    private static final long RETRY_MILLIS = 500L;

    private final ReplyProperties properties;
    private final ScheduledExecutorService timer;
    private final ExecutorService workers;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param future     定时任务句柄，用来在会话被合并时取消
     * @param firstAt    这串消息的第一条时间，用来兜住 {@code maxWaitSeconds}
     * @param superseded 这次请求被新消息合并掉时执行（把那条消息标成已合并）
     */
    private record Pending(long seq, ScheduledFuture<?> future, long firstAt, Runnable superseded) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ReplyDispatcher(ReplyProperties properties) {
        this(properties,
                Executors.newScheduledThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "reply-timer");
                    thread.setDaemon(true);
                    return thread;
                }),
                Executors.newFixedThreadPool(Math.max(1, properties.getWorkers()), runnable -> {
                    Thread thread = new Thread(runnable, "reply-worker");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** 便于单测注入自己的调度器与线程池。 */
    ReplyDispatcher(ReplyProperties properties, ScheduledExecutorService timer, ExecutorService workers) {
        this.properties = properties;
        this.timer = timer;
        this.workers = workers;
    }

    /**
     * 提交一次回复生成请求。
     *
     * @param chatId     会话标识：同一个 chatId 的请求永远串行
     * @param generate   真正干活的同步任务
     * @param superseded 上一次还没执行的请求被这次合并时触发（可以为 null）
     */
    public void submit(String chatId, Runnable generate, Runnable superseded) {
        String key = chatId == null || chatId.isBlank() ? "default" : chatId;
        if (!properties.isEnabled()) {
            runSerialized(key, generate);
            return;
        }
        long now = System.currentTimeMillis();
        long debounce = Math.max(0, properties.getDebounceSeconds()) * 1000L;
        // 封顶时间到了就必须回；0 表示不封顶（只在完全安静的群里才回）
        long capSeconds = Math.max(0, properties.getMaxWaitSeconds());
        long maxWait = capSeconds == 0 ? Long.MAX_VALUE / 4 : capSeconds * 1000L;
        long seq = sequence.incrementAndGet();

        pending.compute(key, (ignored, previous) -> {
            long firstAt = now;
            if (previous != null) {
                firstAt = previous.firstAt();
                previous.future().cancel(false);
                notifySuperseded(key, previous);
            }
            // 攒够 maxWait 就立刻回；否则再等 debounce，看还有没有新消息
            long wait = Math.min(debounce, Math.max(0, maxWait - (now - firstAt)));
            ScheduledFuture<?> future = timer.schedule(
                    () -> fire(key, seq, generate), wait, TimeUnit.MILLISECONDS);
            return new Pending(seq, future, firstAt, superseded);
        });
    }

    private void fire(String key, long seq, Runnable generate) {
        Pending current = pending.get(key);
        if (current != null && current.seq() == seq) {
            pending.remove(key, current);
        }
        runSerialized(key, generate);
    }

    /**
     * 同一个会话同一时刻只跑一个生成任务；正在跑就过一会儿再来，不丢请求。
     */
    private void runSerialized(String key, Runnable task) {
        if (!running.add(key)) {
            timer.schedule(() -> runSerialized(key, task), RETRY_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    task.run();
                } catch (Exception ex) {
                    log.error("回复生成失败: chat={}", key, ex);
                } finally {
                    running.remove(key);
                }
            });
        } catch (RejectedExecutionException ex) {
            running.remove(key);
            log.warn("回复线程池已关闭，丢弃本次生成: chat={}", key);
        }
    }

    private void notifySuperseded(String key, Pending previous) {
        if (previous.superseded() == null) {
            return;
        }
        try {
            previous.superseded().run();
        } catch (Exception ex) {
            log.debug("标记合并消息失败: chat={} ({})", key, ex.getMessage());
        }
    }

    /** 当前还在等待或生成中的会话数（诊断用）。 */
    public int busy() {
        return pending.size() + running.size();
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
