package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives the message events that the WeChat hook posts to {@code CallBackURL}.
 * The hook speaks plain HTTP/1.0 to {@code 127.0.0.1}, so this endpoint is
 * unauthenticated but restricted to loopback callers.
 */
@RestController
public class HookCallbackController {
    private static final Logger log = LoggerFactory.getLogger(HookCallbackController.class);

    private final HookCallbackService service;
    private final WeChatHookProperties properties;
    private final HookCallbackStats stats;
    private final HookCallbackBuffer buffer;

    public HookCallbackController(HookCallbackService service,
                                  WeChatHookProperties properties,
                                  HookCallbackStats stats,
                                  HookCallbackBuffer buffer) {
        this.service = service;
        this.properties = properties;
        this.stats = stats;
        this.buffer = buffer;
    }

    @PostMapping(path = "/api/wechat/hook/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HookCallbackService.Result> receive(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (properties.isCallbackRequireLoopback() && !isLoopback(remote)) {
            log.warn("拒绝非本机 Hook 回调: {}", remote);
            HookCallbackService.Result rejected =
                    new HookCallbackService.Result("REJECTED", "仅允许本机回调", null, null);
            stats.record(rejected);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(rejected);
        }
        HookCallbackService.Result result = service.handle(payload);
        stats.record(result);
        buffer.record(payload, result);
        return ResponseEntity.ok(result);
    }

    private boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }
}
