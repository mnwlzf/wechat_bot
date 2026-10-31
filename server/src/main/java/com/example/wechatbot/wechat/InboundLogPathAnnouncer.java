package com.example.wechatbot.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 启动时把「消息检测日志」的绝对路径打出来。
 *
 * <p>踩过的坑：从 IDEA 启动时工作目录是 {@code server/}，从脚本启动是仓库根目录，
 * 于是 {@code logs/inbound.log} 会落在两个地方，看错文件就会以为“日志没更新”。</p>
 */
@Component
public class InboundLogPathAnnouncer {
    private static final Logger log = LoggerFactory.getLogger(InboundLogPathAnnouncer.class);

    private final String logFile;

    public InboundLogPathAnnouncer(@Value("${INBOUND_LOG_FILE:logs/inbound.log}") String logFile) {
        this.logFile = logFile;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        Path path = Path.of(logFile).toAbsolutePath().normalize();
        log.info("消息检测日志: {}（每检测到一条消息写一行：类型 | 会话 ID | 发送者 | 处理结果 | 内容）", path);
    }
}
