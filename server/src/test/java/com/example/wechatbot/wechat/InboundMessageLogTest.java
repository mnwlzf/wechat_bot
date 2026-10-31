package com.example.wechatbot.wechat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMessageLogTest {

    @Test
    void groupLineCarriesChatIdAndContent() {
        String line = InboundMessageLog.format("49878296302@chatroom", "wxid_rsje67b7hozm22",
                "IGNORED", "群聊不在白名单", "下班！");

        assertThat(line).isEqualTo("群聊 | 49878296302@chatroom | 发送者 wxid_rsje67b7hozm22 | 忽略：群聊不在白名单 | 下班！");
    }

    @Test
    void privateLineUsesWxidAndStatusOnly() {
        assertThat(InboundMessageLog.format("wxid_rsje67b7hozm22", "wxid_rsje67b7hozm22", "ACCEPTED", "消息已接收，正在生成回复", "在吗"))
                .isEqualTo("私聊 | wxid_rsje67b7hozm22 | 发送者 wxid_rsje67b7hozm22 | 接收：消息已接收，正在生成回复 | 在吗");
        assertThat(InboundMessageLog.format("gh_62c4ce184e73", "gh_62c4ce184e73", "IGNORED", "公众号会话不自动回复", "活动"))
                .startsWith("公众号 | gh_62c4ce184e73");
        assertThat(InboundMessageLog.format("filehelper", "filehelper", "IGNORED", "系统会话不自动回复", "文件"))
                .startsWith("系统会话 | filehelper");
    }

    @Test
    void contentIsCollapsedToSingleLineAndTruncated() {
        String line = InboundMessageLog.format("wxid_a", "wxid_a", "ACCEPTED", "", "第一行\n第二行\r\n第三行");

        assertThat(line).endsWith("| 接收 | 第一行 ⏎ 第二行 ⏎ 第三行");

        String longLine = InboundMessageLog.format("wxid_a", "wxid_a", "ACCEPTED", "", "字".repeat(400));
        assertThat(longLine).endsWith("…");
        assertThat(longLine.length()).isLessThan(320);
    }

    @Test
    void blankSenderAndContentFallBackToDash() {
        String line = InboundMessageLog.format("wxid_a", "  ", "IGNORED", "", "   ");

        assertThat(line).isEqualTo("私聊 | wxid_a | 发送者 - | 忽略 | -");
    }

    @Test
    void callbackPayloadIsLoggedWithChatIdAndMediaPlaceholder() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomid", "55555@chatroom");
        payload.put("wxid", "55555@chatroom");
        payload.put("sender", "wxid_rsje67b7hozm22");
        payload.put("type", 3);
        payload.put("content", "<msg><img aeskey=\"abc\" md5=\"def\"/></msg>");

        String line = capture(() -> InboundMessageLog.record(payload,
                new HookCallbackService.Result("IGNORED", "群聊不在白名单", "55555@chatroom", null)));

        assertThat(line).isEqualTo("群聊 | 55555@chatroom | 发送者 wxid_rsje67b7hozm22 | 忽略：群聊不在白名单 | [图片]");
    }

    @Test
    void quotedMessageLogsTheTypedTextAndTheQuotedOne() {
        String xml = "<msg><appmsg><title>可以，我十点到</title><type>57</type>"
                + "<refermsg><displayname>张三</displayname><chatusr>wxid_zhangsan</chatusr>"
                + "<type>1</type><content>明天上午十点开会</content></refermsg></appmsg></msg>";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomid", "49878296302@chatroom");
        payload.put("sender", "wxid_rsje67b7hozm22");
        payload.put("type", 49);
        payload.put("content", xml);

        String line = capture(() -> InboundMessageLog.record(payload,
                new HookCallbackService.Result("IGNORED", "未命中群聊唤醒词", "49878296302@chatroom", null)));

        assertThat(line).isEqualTo("群聊 | 49878296302@chatroom | 发送者 wxid_rsje67b7hozm22 | 忽略：未命中群聊唤醒词"
                + " | 可以，我十点到（引用：明天上午十点开会）");
    }

    @Test
    void callbackPayloadFallsBackToRoomIdWhenResultHasNoChatId() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("wxid", "wxid_rsje67b7hozm22");
        payload.put("sender", "wxid_rsje67b7hozm22");
        payload.put("content", "在吗");

        String line = capture(() -> InboundMessageLog.record(payload,
                new HookCallbackService.Result("IGNORED", "回调接收未启用", null, null)));

        assertThat(line).isEqualTo("私聊 | wxid_rsje67b7hozm22 | 发送者 wxid_rsje67b7hozm22 | 忽略：回调接收未启用 | 在吗");
    }

    /** 抓一条 wechat.inbound 日志的内容（record 直接写 logger，只能这样验证）。 */
    private static String capture(Runnable action) {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InboundMessageLog.LOGGER_NAME);
        ch.qos.logback.classic.Logger logback = (ch.qos.logback.classic.Logger) logger;
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logback.addAppender(appender);
        try {
            action.run();
        } finally {
            logback.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst().getFormattedMessage();
    }
}
