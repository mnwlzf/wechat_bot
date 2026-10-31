package com.example.wechatbot.wechat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HookRawPayloadLogTest {

    private static ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(HookRawPayloadLog.LOGGER_NAME)).addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(HookRawPayloadLog.LOGGER_NAME)).detachAppender(appender);
    }

    @Test
    @DisplayName("语音报文的 XML 原样进日志：能搜到 voicemsg，方便排查为什么拿不到音频")
    void writesVoiceXmlVerbatim() {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "34");
            payload.put("fromUser", "wxid_abc");
            payload.put("content", "<msg><voicemsg voicelength=\"2000\" length=\"8000\" voiceurl=\"http://x/y\"/></msg>");

            HookRawPayloadLog.record(payload);

            assertThat(appender.list).hasSize(1);
            String line = appender.list.getFirst().getFormattedMessage();
            assertThat(line).contains("voicemsg").contains("voicelength").contains("voiceurl");
        } finally {
            detach(appender);
        }
    }

    @Test
    @DisplayName("换行被压成一行，保证一条消息一行方便 grep")
    void collapsesNewlines() {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            HookRawPayloadLog.record(Map.of("content", "<msg>\n<a/>\r\n</msg>"));

            assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("\n").doesNotContain("\r");
        } finally {
            detach(appender);
        }
    }

    @Test
    @DisplayName("空报文不写日志，避免刷屏")
    void skipsEmptyPayload() {
        ListAppender<ILoggingEvent> appender = attach();
        try {
            HookRawPayloadLog.record(null);
            HookRawPayloadLog.record(Map.of());

            assertThat(appender.list).isEmpty();
        } finally {
            detach(appender);
        }
    }
}
