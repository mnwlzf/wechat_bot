package com.example.wechatbot.web;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.wechat.HookCallbackBuffer;
import com.example.wechatbot.wechat.HookCallbackStats;
import com.example.wechatbot.wechat.WeChatHookClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated diagnostics for the WeChat integration: hook reachability,
 * process fingerprint, callback counters and the guard daemon status file.
 */
@RestController
@RequestMapping("/api/admin/wechat")
public class WeChatHookDiagnosticsController {
    private final WeChatHookProperties properties;
    private final WeChatHookClient client;
    private final HookCallbackStats stats;
    private final HookCallbackBuffer buffer;
    private final ObjectMapper objectMapper;

    public WeChatHookDiagnosticsController(WeChatHookProperties properties,
                                           WeChatHookClient client,
                                           HookCallbackStats stats,
                                           HookCallbackBuffer buffer,
                                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.stats = stats;
        this.buffer = buffer;
        this.objectMapper = objectMapper;
    }

    /** 最近 20 条回调原文，排查“群里 @ 了没反应”最快的方式。 */
    @GetMapping("/hook/recent")
    public java.util.List<Map<String, Object>> recentCallbacks() {
        return buffer.recent();
    }

    @GetMapping("/hook/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hook", probeHook());
        result.put("callback", callback());
        result.put("guard", guardStatus());
        result.put("allowedChats", properties.allowedChatIds());
        return result;
    }

    private Map<String, Object> probeHook() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("baseUrl", properties.getBaseUrl());
        long started = System.nanoTime();
        try {
            Map<String, Object> status = client.status();
            view.put("reachable", true);
            view.put("isLogin", status.get("IsLogin"));
            Object moduleBase = status.get("hWeixin");
            view.put("moduleBase", moduleBase == null ? "" : String.valueOf(moduleBase));
            view.put("instance", fingerprint(moduleBase));
            view.put("error", "");
        } catch (RuntimeException ex) {
            view.put("reachable", false);
            view.put("isLogin", null);
            view.put("moduleBase", "");
            view.put("instance", "");
            view.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        view.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        view.put("checkedAt", Instant.now());
        return view;
    }

    private String fingerprint(Object moduleBase) {
        if (moduleBase == null) {
            return "";
        }
        String raw = String.valueOf(moduleBase).trim();
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return "0x" + Long.toHexString(Long.parseLong(raw));
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private Map<String, Object> callback() {
        HookCallbackStats.Snapshot snapshot = stats.snapshot();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", properties.isCallbackEnabled());
        view.put("acceptAll", properties.isCallbackAcceptAll());
        view.put("groupsEnabled", properties.isCallbackGroupsEnabled());
        view.put("requireLoopback", properties.isCallbackRequireLoopback());
        view.put("selfWxid", properties.getSelfWxid());
        view.put("total", snapshot.total());
        view.put("accepted", snapshot.accepted());
        view.put("ignored", snapshot.ignored());
        view.put("lastEventAt", snapshot.lastEventAt());
        view.put("lastChatId", snapshot.lastChatId());
        view.put("lastStatus", snapshot.lastStatus());
        view.put("lastReason", snapshot.lastReason());
        return view;
    }

    private Object guardStatus() {
        String configured = properties.getGuardStatusFile();
        if (configured == null || configured.isBlank()) {
            return Map.of("configured", false);
        }
        Path path = Path.of(configured.trim());
        if (!Files.isRegularFile(path)) {
            return Map.of("configured", true, "file", path.toString(), "present", false);
        }
        try {
            Object parsed = objectMapper.readValue(Files.readString(path), Object.class);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("configured", true);
            view.put("file", path.toString());
            view.put("present", true);
            view.put("status", parsed);
            return view;
        } catch (Exception ex) {
            return Map.of("configured", true, "file", path.toString(), "present", true,
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
}
