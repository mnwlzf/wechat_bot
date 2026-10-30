package com.example.wechatbot.web;

import com.example.wechatbot.domain.OutboxMessage;
import com.example.wechatbot.service.OutboxService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 诊断中心：把“钩子连通性 / 回调计数 / 最近回调原文 / 失败投递”集中到一处，
 * 便于不重启、不看日志就判断链路卡在哪一段。
 */
@RestController
@RequestMapping("/api/admin/diagnostics")
public class DiagnosticsController {
    private final WeChatHookDiagnosticsController hookDiagnostics;
    private final OutboxService outboxService;

    public DiagnosticsController(WeChatHookDiagnosticsController hookDiagnostics,
                                 OutboxService outboxService) {
        this.hookDiagnostics = hookDiagnostics;
        this.outboxService = outboxService;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now());
        result.put("wechat", hookDiagnostics.diagnostics());
        result.put("recentCallbacks", hookDiagnostics.recentCallbacks());
        result.put("outbox", outbox());
        return result;
    }

    /** 失败投递列表（诊断页右栏）。 */
    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("failedCount", outboxService.failedCount());
        view.put("failed", outboxService.failed(20).stream().map(this::toView).toList());
        return view;
    }

    @PostMapping("/outbox/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        return toView(outboxService.retry(id));
    }

    private Map<String, Object> toView(OutboxMessage message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", message.getId());
        item.put("chatId", message.getChatId());
        item.put("content", message.getContent());
        item.put("status", message.getStatus().name());
        item.put("attempts", message.getAttempts());
        item.put("lastError", message.getLastError());
        item.put("createdAt", message.getCreatedAt());
        item.put("nextAttemptAt", message.getNextAttemptAt());
        return item;
    }
}
