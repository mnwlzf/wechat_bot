package com.example.wechatbot.service;

import com.example.wechatbot.config.ReplyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群消息连发时的聚合行为：攒成一次、被合并的有回调、刷屏也不会一直等、同群永远串行。
 */
class ReplyDispatcherTest {

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
        workers.shutdownNow();
    }

    private ReplyDispatcher dispatcher(int debounceSeconds, int maxWaitSeconds) {
        ReplyProperties properties = new ReplyProperties();
        properties.setDebounceSeconds(debounceSeconds);
        properties.setMaxWaitSeconds(maxWaitSeconds);
        return new ReplyDispatcher(properties, timer, workers);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void burstCollapsesIntoASingleRun() {
        ReplyDispatcher dispatcher = dispatcher(1, 5);
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 6; i++) {
            dispatcher.submit("123@chatroom", runs::incrementAndGet, null);
            sleep(30);
        }

        await(() -> runs.get() == 1);
        sleep(400);
        // 一口气 6 条消息只回一次，而且回的是攒到最后的完整对话
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void mergedRequestsReportThemselvesAsSuperseded() {
        ReplyDispatcher dispatcher = dispatcher(1, 5);
        AtomicInteger superseded = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            dispatcher.submit("123@chatroom", runs::incrementAndGet, superseded::incrementAndGet);
            sleep(30);
        }

        await(() -> runs.get() == 1);
        sleep(200);

        assertThat(runs.get()).isEqualTo(1);
        // 前两条被第三条合并，各自回调一次，页面不会留下「已接收」的孤儿消息
        assertThat(superseded.get()).isEqualTo(2);
    }

    @Test
    void supersededRequestNeverRepliesOnItsOwn() {
        ReplyDispatcher dispatcher = dispatcher(1, 5);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        // 第一条还没等到静默，第二条就来了：第一条只当上下文，不能再单独回
        dispatcher.submit("123@chatroom", first::incrementAndGet, null);
        dispatcher.submit("123@chatroom", second::incrementAndGet, null);

        await(() -> second.get() == 1);
        sleep(300);

        assertThat(first.get()).isZero();
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    void chatsAreIsolatedFromEachOther() {
        ReplyDispatcher dispatcher = dispatcher(1, 5);
        AtomicInteger runs = new AtomicInteger();

        dispatcher.submit("a@chatroom", runs::incrementAndGet, null);
        dispatcher.submit("b@chatroom", runs::incrementAndGet, null);

        await(() -> runs.get() == 2);
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void endlessChatStillGetsAReplyWhenMaxWaitIsReached() {
        // 攒 10 秒才回的配置，但最多 1 秒就必须吭一声
        ReplyDispatcher dispatcher = dispatcher(10, 1);
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 20; i++) {
            dispatcher.submit("123@chatroom", runs::incrementAndGet, null);
            sleep(50);
        }

        // 1 秒时强制开口一次
        await(() -> runs.get() >= 1);
        assertThat(runs.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameChatNeverGeneratesTwiceInParallel() {
        ReplyDispatcher dispatcher = dispatcher(0, 0);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();

        Runnable generate = () -> {
            int now = concurrent.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            runs.incrementAndGet();
            sleep(400);
            concurrent.decrementAndGet();
        };

        // 第一条已经开跑（不在等待窗口里，不会被合并），第二条只能排队等它结束
        dispatcher.submit("123@chatroom", generate, null);
        sleep(150);
        dispatcher.submit("123@chatroom", generate, null);

        await(() -> runs.get() >= 2);
        sleep(200);

        assertThat(runs.get()).isEqualTo(2);
        // 上一条没生成完时新请求排队等待，不会两条回复抢跑（否则群里会同时冒出两段话）
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void disabledDebounceGeneratesForEveryMessage() {
        ReplyProperties properties = new ReplyProperties();
        properties.setEnabled(false);
        ReplyDispatcher dispatcher = new ReplyDispatcher(properties, timer, workers);
        AtomicInteger runs = new AtomicInteger();

        dispatcher.submit("123@chatroom", runs::incrementAndGet, null);
        dispatcher.submit("123@chatroom", runs::incrementAndGet, null);

        await(() -> runs.get() == 2);
        assertThat(runs.get()).isEqualTo(2);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
