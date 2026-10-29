package com.example.wechatbot.wechat;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters for the hook callback channel. They make "is the WeChat
 * side actually pushing?" answerable without enabling HTTP access logs.
 */
@Component
public class HookCallbackStats {
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    private volatile Instant lastEventAt;
    private volatile String lastChatId = "";
    private volatile String lastStatus = "";
    private volatile String lastReason = "";

    public void record(HookCallbackService.Result result) {
        total.incrementAndGet();
        if (result != null && "ACCEPTED".equalsIgnoreCase(result.status())) {
            accepted.incrementAndGet();
        } else {
            ignored.incrementAndGet();
        }
        lastEventAt = Instant.now();
        lastChatId = result == null || result.chatId() == null ? "" : result.chatId();
        lastStatus = result == null || result.status() == null ? "" : result.status();
        lastReason = result == null || result.reason() == null ? "" : result.reason();
    }

    public Snapshot snapshot() {
        return new Snapshot(total.get(), accepted.get(), ignored.get(),
                lastEventAt, lastChatId, lastStatus, lastReason);
    }

    public record Snapshot(
            long total,
            long accepted,
            long ignored,
            Instant lastEventAt,
            String lastChatId,
            String lastStatus,
            String lastReason
    ) {}
}
