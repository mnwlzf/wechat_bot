package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.service.OutboxService;
import com.example.wechatbot.web.dto.AgentDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "app.wechat.hook", name = "outbox-enabled", havingValue = "true", matchIfMissing = true)
public class WeChatHookOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WeChatHookOutboxDispatcher.class);
    private final WeChatHookClient client;
    private final OutboxService outboxService;
    private final WeChatHookProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeChatHookOutboxDispatcher(WeChatHookClient client,
                                      OutboxService outboxService,
                                      WeChatHookProperties properties) {
        this.client = client;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.wechat.hook.outbox-interval-ms:1000}",
            initialDelayString = "${app.wechat.hook.initial-delay-ms:5000}")
    public void dispatch() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            int batch = Math.max(1, Math.min(properties.getBatchSize(), 20));
            List<AgentDtos.OutboxView> messages = outboxService.lease(batch);
            for (AgentDtos.OutboxView message : messages) {
                try {
                    pause(ThreadLocalRandom.current().nextLong(0, 350));
                    client.sendText(message.chatId(), message.content());
                    outboxService.ack(message.id(), true, null);
                    log.info("微信回复已发送，outbox={}", message.id());
                } catch (RuntimeException ex) {
                    outboxService.ack(message.id(), false, ex.getMessage());
                    log.warn("微信回复发送失败，outbox={}: {}", message.id(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Hook 发件箱调度失败: {}", ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
