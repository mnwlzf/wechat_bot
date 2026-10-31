package com.example.wechatbot.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 原始回调报文日志：每条回调写一行，内容原样（含图片/语音的 XML）。
 *
 * <p>排版给排查用，不参与业务：语音到底给了哪些属性（{@code voiceurl}、{@code bufid}、{@code md5}）、
 * 图片 XML 长什么样，都能在 {@code logs/hook-raw.log} 里直接搜 {@code voicemsg} 看到。
 * 单独一份文件 + 单独 logger，不混进人类可读的消息日志。</p>
 */
public final class HookRawPayloadLog {
    public static final String LOGGER_NAME = "wechat.hook.raw";
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
    private static final int MAX_CHARS = 4000;

    private HookRawPayloadLog() {}

    public static void record(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            String line = payload.toString().replace("\r", " ").replace("\n", " ");
            if (line.length() > MAX_CHARS) {
                line = line.substring(0, MAX_CHARS) + "…";
            }
            LOGGER.info(line);
        } catch (RuntimeException ex) {
            LOGGER.info("（原始报文无法输出: {}）", ex.getClass().getSimpleName());
        }
    }
}
