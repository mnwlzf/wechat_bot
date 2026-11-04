package com.example.wechatbot.flow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时流水的内存环形缓冲：容量、增量游标、等待项、以及「后面知道了名字要补上」。
 *
 * <p>这些在界面上就是「流水滚动 + 正在等谁」，出错会直接表现为「排队看不见」，
 * 所以单独锁一遍，不依赖 Spring 上下文。</p>
 */
class FlowTraceServiceTest {

    @Test
    void recordsEventsWithCountersAndAscendingSeq() {
        FlowTraceService service = new FlowTraceService();
        service.record(FlowTraceService.Stage.RECEIVED, "wxid_a", "张三", "新消息", "在吗", null, null);
        service.record(FlowTraceService.Stage.SENT, "wxid_a", "张三", "已发出", "在的", null, null);

        FlowTraceService.Snapshot snapshot = service.snapshot(0, 50);

        assertThat(snapshot.events()).hasSize(2);
        assertThat(snapshot.events().get(0).seq()).isLessThan(snapshot.events().get(1).seq());
        assertThat(snapshot.cursor()).isEqualTo(snapshot.events().get(1).seq());
        assertThat(snapshot.counters()).containsEntry("RECEIVED", 1L).containsEntry("SENT", 1L);
        assertThat(snapshot.events().get(0).stageLabel()).isEqualTo("收到消息");
    }

    @Test
    void sinceReturnsOnlyNewerEvents() {
        FlowTraceService service = new FlowTraceService();
        service.record(FlowTraceService.Stage.RECEIVED, "wxid_a", null, "第一条", null, null, null);
        service.record(FlowTraceService.Stage.RECEIVED, "wxid_a", null, "第二条", null, null, null);
        long cursor = service.snapshot(0, 50).cursor();

        service.record(FlowTraceService.Stage.RECEIVED, "wxid_a", null, "第三条", null, null, null);
        List<FlowTraceService.Event> fresh = service.snapshot(cursor, 50).events();

        assertThat(fresh).hasSize(1);
        assertThat(fresh.getFirst().detail()).isEqualTo("第三条");
    }

    @Test
    void ringStopsAtCapacityAndSnapshotRespectsMaxPage() {
        FlowTraceService service = new FlowTraceService();
        for (int i = 0; i < FlowTraceService.CAPACITY + 20; i++) {
            service.record(FlowTraceService.Stage.RECEIVED, "wxid_a", null, "第 " + i + " 条",
                    null, null, null);
        }

        // 缓冲有上限，一次返回也有上限（防止一次把 400 条全塞给浏览器）
        FlowTraceService.Snapshot snapshot = service.snapshot(0, 10_000);

        assertThat(snapshot.events()).hasSize(FlowTraceService.MAX_PAGE);
        // 留下的是最近的：最后一条就是最后写入的那条
        assertThat(snapshot.events().getLast().detail())
                .isEqualTo("第 " + (FlowTraceService.CAPACITY + 19) + " 条");
        assertThat(snapshot.events().getFirst().detail())
                .isEqualTo("第 " + (FlowTraceService.CAPACITY + 20 - FlowTraceService.MAX_PAGE) + " 条");
    }

    @Test
    void waitingSurvivesUntilClearedAndCountsDown() {
        FlowTraceService service = new FlowTraceService();
        Instant until = Instant.now().plusSeconds(20);
        service.setWaiting(FlowTraceService.Stage.QUOTA, "wxid_a", null, "每分钟上限 3 条", until);

        FlowTraceService.Wait wait = service.snapshot(0, 10).waiting().getFirst();
        assertThat(wait.stage()).isEqualTo("QUOTA");
        assertThat(wait.reason()).contains("每分钟上限");
        assertThat(wait.remainingMs(Instant.now())).isBetween(15_000L, 20_000L);

        service.dropWaiting("wxid_a", FlowTraceService.Stage.QUOTA);
        assertThat(service.snapshot(0, 10).waiting()).isEmpty();
    }

    @Test
    void laterEventFillsInMissingChatName() {
        FlowTraceService service = new FlowTraceService();
        // 伪装层只知道 chatId，不知道群名
        service.setWaiting(FlowTraceService.Stage.QUOTA, "123@chatroom", null, "排队中",
                Instant.now().plusSeconds(5));

        service.record(FlowTraceService.Stage.OUTBOX, "123@chatroom", "城南客户群", "进发送队列",
                null, null, null);

        FlowTraceService.Wait wait = service.snapshot(0, 10).waiting()
                .stream().filter(item -> "QUOTA".equals(item.stage())).findFirst().orElseThrow();
        assertThat(wait.chatName()).isEqualTo("城南客户群");
    }

    @Test
    void expiredWaitIsNotShownForever() {
        FlowTraceService service = new FlowTraceService();
        service.setWaiting(FlowTraceService.Stage.BUFFER, "wxid_a", null, "攒消息",
                Instant.now().minusSeconds(30));

        assertThat(service.snapshot(0, 10).waiting()).isEmpty();
    }

    @Test
    void staticBridgeIsInertWithoutAnAttachedService() {
        // 单测里没有 Spring，静态旁路必须什么都不做，不能 NPE
        org.junit.jupiter.api.Assumptions.assumeTrue(FlowTraceService.active() == null,
                "同一 JVM 里已经有 Spring 上下文装配过流水，跳过「未装配」用例");
        FlowTraceService.mark(FlowTraceService.Stage.RECEIVED, "wxid_a", null, "x", null);
        FlowTraceService.waiting(FlowTraceService.Stage.BUFFER, "wxid_a", null, "x", Instant.now());
        FlowTraceService.clearWaiting("wxid_a", FlowTraceService.Stage.BUFFER);

        assertThat(FlowTraceService.active()).isNull();
    }
}
