package com.example.wechatbot.wechat.db;

import com.example.wechatbot.config.WeChatDbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时体检：密钥还能不能打开本地库，不能就自动重新截获。
 *
 * <p>密钥在「一次安装 + 同一个账号」内是不变的（微信不会为了换密钥去重加密整个库），
 * 所以这里绝大多数时候只是空转。真正会失效的场景是换账号、重装微信、清了数据——
 * 那时微信会在下次登录时开库，自动截获正好能接住。</p>
 */
@Component
public class WeChatDbKeyHealth {
    private static final Logger log = LoggerFactory.getLogger(WeChatDbKeyHealth.class);
    private static final long WARN_INTERVAL_MILLIS = 3_600_000L;

    private final WeChatDbProperties properties;
    private final WeChatDbKeyStore keyStore;
    private final WeChatKeyCapture capture;
    private final AtomicLong lastWarnAt = new AtomicLong();
    private volatile String lastResult = "";

    public WeChatDbKeyHealth(WeChatDbProperties properties, WeChatDbKeyStore keyStore,
                             WeChatKeyCapture capture) {
        this.properties = properties;
        this.keyStore = keyStore;
        this.capture = capture;
    }

    public String lastResult() {
        return lastResult;
    }

    @Scheduled(initialDelayString = "${app.wechat.db.check-initial-delay-ms:120000}",
            fixedDelayString = "${app.wechat.db.check-interval-ms:300000}")
    public void verify() {
        if (!properties.isEnabled()) {
            return;
        }
        WeChatDbKeyStore.Check check = keyStore.check();
        if (check.valid()) {
            // 第一次跑起来时把 .env 里的密钥落到密钥文件，之后一律以文件为准：
            // 自动截获到的新密钥只要重写文件就生效，不用再去改 .env。
            if ("env".equals(check.source()) && !Files.exists(keyStore.keyFile())) {
                try {
                    keyStore.save(keyStore.key());
                    lastResult = "已把 .env 里的密钥落到密钥文件";
                } catch (IOException ex) {
                    log.debug("写密钥文件失败: {}", ex.getMessage());
                }
            }
            return;
        }
        if (!properties.isAutoCapture()) {
            warn("微信数据库密钥不可用，且自动截获已关闭: " + check.error());
            return;
        }
        if (!capture.available()) {
            warn("微信数据库密钥不可用，暂时也没法自动截获（脚本/DLL 缺失或微信没开）: " + check.error());
            return;
        }
        if (capture.running()) {
            return;
        }
        log.warn("微信数据库密钥校验失败，开始自动截获: {}", check.error());
        CompletableFuture.runAsync(() -> {
            WeChatKeyCapture.Result result = capture.capture(properties.getCaptureTimeoutSeconds());
            lastResult = result.ok()
                    ? "自动截获成功 " + result.maskedKey()
                    : "自动截获失败: " + result.message();
            if (result.ok()) {
                log.info("微信数据库密钥已自动更新（{}），语音转写恢复", result.maskedKey());
            } else {
                log.warn("微信数据库密钥自动截获失败: {}", result.message());
            }
        });
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last < WARN_INTERVAL_MILLIS) {
            log.debug(message);
            return;
        }
        if (lastWarnAt.compareAndSet(last, now)) {
            log.warn(message);
        }
    }
}
