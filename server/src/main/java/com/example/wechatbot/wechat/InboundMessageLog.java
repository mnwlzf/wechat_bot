package com.example.wechatbot.wechat;

import com.example.wechatbot.service.MediaMessageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * 每检测到一条消息就写一行「消息 + 会话 ID」日志，落到 {@code logs/inbound.log}。
 *
 * <p>为什么要单独一份日志：微信 4.x 的通讯录是加密的、预编译 Hook 的 {@code QueryDB} 又是空的，
 * 管理端搜不到昵称。但只要有人在群里说话、或者私聊发消息，这里就会打印出群号 / wxid 和消息内容，
 * 照着日志里的 ID 直接加进白名单即可，不用再去猜 ID。</p>
 *
 * <p>格式（一行一条，字段位置固定，方便复制 ID）：</p>
 * <pre>
 * 2026-10-31 14:36:02.123 | 群聊 | 49878296302@chatroom | 发送者 wxid_rsje67b7hozm22 | 忽略：群聊不在白名单 | 下班！
 * </pre>
 */
public final class InboundMessageLog {
    /** 独立 logger：logback-spring.xml 里给它配了单独的文件 appender。 */
    public static final String LOGGER_NAME = "wechat.inbound";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);
    private static final int MAX_CONTENT = 200;

    private InboundMessageLog() {
    }

    /** 回调路径：原始报文 + 处理结果，任何分支（包括“回调未启用”）都会记一行。 */
    public static void record(Map<String, Object> payload, HookCallbackService.Result result) {
        if (payload == null) {
            return;
        }
        String roomId = text(payload.get("roomid"));
        String wxid = text(payload.get("wxid"));
        String chatId = result != null && result.chatId() != null && !result.chatId().isBlank()
                ? result.chatId()
                : (roomId.isBlank() ? wxid : roomId);
        record(chatId, text(payload.get("sender")),
                result == null ? "" : result.status(),
                result == null ? "" : result.reason(),
                describe(payload.get("type"), text(payload.get("content"))));
    }

    /**
     * 非纯文本报文的报文是 XML：引用回复要取出“这次说的话”，图片 / 语音等用占位符，
     * 这样日志里能看到人话，而不是一坨 XML 或一句「非文本消息」。
     */
    static String describe(Object typeValue, String raw) {
        String content = raw == null ? "" : raw.trim();
        if (!content.startsWith("<")) {
            return content;
        }
        int type = intValue(typeValue);
        try {
            MediaMessageParser.Quoted quote = MediaMessageParser.parseQuote(type, content);
            if (quote != null && quote.text() != null && !quote.text().isBlank()) {
                String quoted = quote.quoteText() == null ? "" : quote.quoteText().trim();
                return quoted.isEmpty()
                        ? quote.text().trim()
                        : quote.text().trim() + "（引用：" + abbreviate(quoted, 40) + "）";
            }
            MediaMessageParser.Parsed media = MediaMessageParser.parse(type, content);
            if (media != null && media.placeholder() != null && !media.placeholder().isBlank()) {
                return media.placeholder();
            }
        } catch (RuntimeException ex) {
            // 解析失败就退回占位符，日志永远不能把消息处理搞挂
        }
        return "[非文本消息 type=" + (type == 0 ? "-" : type) + "]";
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 轮询路径：已经转换好的事件 + 处理结果。 */
    public static void record(String chatId, String sender, String status, String reason, String content) {
        log.info(format(chatId, sender, status, reason, content));
    }

    /** 拼一行日志（抽出来便于单测，不依赖 logback）。 */
    static String format(String chatId, String sender, String status, String reason, String content) {
        return kindLabel(chatId)
                + " | " + dashIfBlank(chatId)
                + " | 发送者 " + dashIfBlank(sender)
                + " | " + statusLine(status, reason)
                + " | " + oneLine(content);
    }

    private static String kindLabel(String chatId) {
        return switch (ChatIdentity.kindOf(chatId)) {
            case GROUP -> "群聊";
            case PRIVATE -> "私聊";
            case OFFICIAL -> "公众号";
            case SYSTEM -> "系统会话";
        };
    }

    private static String statusLine(String status, String reason) {
        String label = switch (status == null ? "" : status.trim().toUpperCase(Locale.ROOT)) {
            case "ACCEPTED" -> "接收";
            case "IGNORED" -> "忽略";
            case "DUPLICATE" -> "重复";
            case "RATE_LIMITED" -> "限流";
            case "PAUSED" -> "人工接管";
            case "REJECTED" -> "拒绝";
            default -> dashIfBlank(status);
        };
        String detail = reason == null ? "" : reason.trim();
        return detail.isEmpty() ? label : label + "：" + detail;
    }

    private static String oneLine(String content) {
        String value = content == null ? "" : content;
        value = value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", " ⏎ ");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.length() > MAX_CONTENT) {
            value = value.substring(0, MAX_CONTENT) + "…";
        }
        return value.isEmpty() ? "-" : value;
    }

    private static String dashIfBlank(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? "-" : text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
