package com.example.wechatbot.web;

import com.example.wechatbot.flow.FlowTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时流水：一条消息从进来到发出去，每一步在哪个阶段、等了多久、现在谁在排队。
 *
 * <p>刻意用轮询而不是长连接：管理端页面 1~2 秒拉一次就够「实时」了，
 * 而 SSE / WebSocket 要额外处理鉴权、重连、代理缓冲，为这点数据量不值当。</p>
 */
@RestController
@RequestMapping("/api/admin/flow")
public class FlowController {

    private final FlowTraceService flowTrace;

    public FlowController(FlowTraceService flowTrace) {
        this.flowTrace = flowTrace;
    }

    /**
     * @param since 只要 seq 大于它的新事件；管理端增量追加，避免每次全量重绘
     * @param limit 本次最多多少条
     */
    @GetMapping
    public Map<String, Object> flow(@RequestParam(defaultValue = "0") long since,
                                    @RequestParam(defaultValue = "120") int limit) {
        FlowTraceService.Snapshot snapshot = flowTrace.snapshot(since, limit);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("generatedAt", snapshot.now());
        view.put("cursor", snapshot.cursor());
        view.put("counters", snapshot.counters());
        view.put("waiting", snapshot.waiting().stream().map(this::waitView).toList());
        view.put("events", snapshot.events().stream().map(this::eventView).toList());
        view.put("capacity", FlowTraceService.CAPACITY);
        return view;
    }

    private Map<String, Object> eventView(FlowTraceService.Event event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", event.seq());
        item.put("at", event.at());
        item.put("stage", event.stage());
        item.put("stageLabel", event.stageLabel());
        item.put("chatId", event.chatId());
        item.put("chatName", event.chatName());
        item.put("detail", event.detail());
        item.put("waitMs", event.waitMs());
        item.put("durationMs", event.durationMs());
        item.put("preview", event.preview());
        return item;
    }

    private Map<String, Object> waitView(FlowTraceService.Wait wait) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chatId", wait.chatId());
        item.put("chatName", wait.chatName());
        item.put("stage", wait.stage());
        item.put("stageLabel", wait.stageLabel());
        item.put("reason", wait.reason());
        item.put("since", wait.since());
        item.put("until", wait.until());
        return item;
    }

    /** 各阶段当前状态：留给以后做「概览卡片」用，也方便自己 curl 一眼。 */
    @GetMapping("/stages")
    public List<String> stages() {
        return java.util.Arrays.stream(FlowTraceService.Stage.values())
                .map(stage -> stage.name() + "=" + stage.label())
                .toList();
    }
}
