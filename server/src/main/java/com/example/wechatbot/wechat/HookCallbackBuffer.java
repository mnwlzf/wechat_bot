package com.example.wechatbot.wechat;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最近若干条 Hook 回调的内存环形缓冲，用于排查“群里 @ 了却没回复”这类问题：
 * 直接看微信推过来的原始报文长什么样，不用翻日志、不用加断点。
 */
@Component
public class HookCallbackBuffer {
    private static final int LIMIT = 20;

    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public void record(Map<String, Object> payload, HookCallbackService.Result result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("receivedAt", OffsetDateTime.now());
        item.put("status", result == null ? "" : result.status());
        item.put("reason", result == null ? "" : result.reason());
        item.put("chatId", result == null ? "" : result.chatId());
        item.put("payload", payload);
        synchronized (recent) {
            recent.addFirst(item);
            while (recent.size() > LIMIT) {
                recent.removeLast();
            }
        }
    }

    public List<Map<String, Object>> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }
}
