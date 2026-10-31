package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.service.HookExtras;
import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.service.MediaMessageParser;
import com.example.wechatbot.web.dto.AgentDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * Consumes the JSON events that the WeChat hook pushes to
 * {@code CallBackURL=...} (see {@code WeChatHookInboxPoller} for the polling
 * alternative). The callback path keeps working when the hook cannot report
 * {@code IsLogin}, because it does not depend on the SQLite database handles.
 */
@Service
public class HookCallbackService {
    private static final Logger log = LoggerFactory.getLogger(HookCallbackService.class);

    static final int EVENT_MESSAGE = 1001;

    private final WeChatHookProperties properties;
    private final ChatAccessPolicy accessPolicy;
    private final InboxService inboxService;

    public HookCallbackService(WeChatHookProperties properties,
                               ChatAccessPolicy accessPolicy,
                               InboxService inboxService) {
        this.properties = properties;
        this.accessPolicy = accessPolicy;
        this.inboxService = inboxService;
    }

    public Result handle(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Result.ignored("空回调报文", null, null);
        }
        if (!properties.isCallbackEnabled()) {
            return Result.ignored("回调接收未启用", null, null);
        }
        int eventType = intValue(payload.get("event_type"));
        if (eventType != EVENT_MESSAGE) {
            return Result.ignored("忽略事件类型 " + eventType, null, null);
        }
        int type = intValue(payload.get("type"));
        String rawContent = text(payload.get("content"));
        MediaMessageParser.Parsed media = null;
        MediaMessageParser.Quoted quote = null;
        String content;
        if (MediaMessageParser.isText(type)) {
            content = rawContent;
        } else if ((quote = MediaMessageParser.parseQuote(type, rawContent)) != null) {
            // 引用回复本质是文本：用本次说的话当内容，被引用原文单独带走
            content = quote.text();
        } else {
            media = MediaMessageParser.parse(type, rawContent);
            content = media.placeholder();
            if (media.kind() == com.example.wechatbot.domain.MediaKind.IMAGE) {
                // 图片定位/解密出问题时，唯一能复盘的就是这条原文；图片消息量很小，直接记 INFO
                log.info("图片消息原文: md5={} thumbMd5={} aeskey={} thumbAeskey={} raw={}",
                        media.md5(), media.thumbMd5(), media.aeskey(), media.thumbAeskey(),
                        truncate(rawContent, 900));
            } else if (media.kind() == com.example.wechatbot.domain.MediaKind.OTHER) {
                // 认不出来的类型只可能是微信又加了新格式，留一条 INFO 方便照着补解析
                log.info("未识别的消息类型: type={} placeholder={} raw={}", type,
                        media.placeholder(), truncate(rawContent, 600));
            } else {
                log.debug("媒体消息: type={} kind={} raw={}", type, media.kind(),
                        truncate(rawContent, 400));
            }
        }
        if (content.isBlank()) {
            return Result.ignored("空消息内容", null, null);
        }

        String roomId = text(payload.get("roomid"));
        String wxid = text(payload.get("wxid"));
        String chatId = roomId.isBlank() ? wxid : roomId;
        if (chatId.isBlank()) {
            return Result.ignored("缺少会话 ID", null, null);
        }
        String sender = text(payload.get("sender"));
        if (sender.isBlank()) {
            sender = chatId;
        }

        String selfWxid = properties.getSelfWxid();
        if (!selfWxid.isBlank() && sender.equalsIgnoreCase(selfWxid)) {
            return Result.ignored("忽略账号自身消息", chatId, null);
        }

        // 群聊 / 私聊 / 公众号 / 系统会话统一走准入策略：公众号和机器人永远不回复
        ChatAccessPolicy.Decision decision = accessPolicy.decide(chatId);
        if (!decision.allowed()) {
            return Result.ignored(decision.reason(), chatId, null);
        }
        boolean group = decision.kind() == ChatIdentity.Kind.GROUP;
        String eventId = eventId(payload, chatId);
        AgentDtos.AgentEventRequest event = new AgentDtos.AgentEventRequest(
                eventId,
                null,
                "message",
                truncate(chatId, 180),
                truncate(chatId, 180),
                group ? ChatType.GROUP : ChatType.PRIVATE,
                truncate(sender, 180),
                truncate(sender, 180),
                truncate(content.trim(), 10_000),
                timestamp(payload.get("timestamp")),
                false,
                media == null ? null : media.kind().name(),
                media == null ? null : media.name(),
                media == null ? null : media.size(),
                media == null ? null : media.url(),
                media == null && quote == null ? null : type
        );

        AgentDtos.EventAccepted accepted = inboxService.accept(event, HookExtras.of(quote, media));
        if (!"ACCEPTED".equalsIgnoreCase(accepted.status())) {
            log.debug("Hook 回调事件未进入回复流程: chat={} status={} reason={}",
                    chatId, accepted.status(), accepted.reason());
        } else {
            log.info("Hook 回调收到消息，chat={} sender={} type={} eventId={}",
                    chatId, sender, type, eventId);
        }
        return new Result(accepted.status(), accepted.reason(), chatId, eventId);
    }

    private String eventId(Map<String, Object> payload, String chatId) {
        long msgId = longValue(payload.get("msgid"));
        if (msgId > 0) {
            return "hook_" + msgId;
        }
        String raw = chatId + ":" + text(payload.get("sender")) + ":" + text(payload.get("timestamp"))
                + ":" + text(payload.get("content"));
        return "hook_" + sha256Short(raw);
    }

    private static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(40);
            for (int i = 0; i < 20; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static OffsetDateTime timestamp(Object value) {
        long timestamp = longValue(value);
        if (timestamp > 10_000_000_000L) {
            timestamp /= 1000;
        }
        if (timestamp <= 0) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
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

    private static String truncate(String value, int maxUnits) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxUnits) {
            return safe;
        }
        int end = maxUnits;
        if (Character.isHighSurrogate(safe.charAt(end - 1)) && end < safe.length()) {
            end--;
        }
        return safe.substring(0, end);
    }

    public record Result(String status, String reason, String chatId, String eventId) {
        static Result ignored(String reason, String chatId, String eventId) {
            return new Result("IGNORED", reason, chatId, eventId);
        }
    }
}
