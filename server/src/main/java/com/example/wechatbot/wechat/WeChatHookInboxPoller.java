package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.ChatType;
import com.example.wechatbot.domain.WeChatHookCursor;
import com.example.wechatbot.repository.WeChatHookCursorRepository;
import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.service.HookExtras;
import com.example.wechatbot.service.MediaMessageParser;
import com.example.wechatbot.web.dto.AgentDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "app.wechat.hook", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WeChatHookInboxPoller {
    private static final Logger log = LoggerFactory.getLogger(WeChatHookInboxPoller.class);
    private static final Pattern GROUP_SENDER_PREFIX =
            Pattern.compile("^([A-Za-z0-9_@.-]{5,64})\\s*[:：]\\s");
    private static final long DISCOVERY_TTL_MILLIS = 30_000;
    private static final long CONTACT_TTL_MILLIS = 300_000;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 30_000;

    private final WeChatHookProperties properties;
    private final WeChatHookClient client;
    private final WeChatHookCursorRepository cursorRepository;
    private final InboxService inboxService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile List<String> shards = List.of();
    private volatile Set<String> databaseNames = Set.of();
    private volatile Map<String, String> contacts = Map.of();
    private volatile long lastDiscoveryAt;
    private volatile long lastContactScanAt;
    private volatile long lastErrorLogAt;
    private volatile String lastError = "";

    private final Map<String, String> tableCache = new HashMap<>();
    private final Map<String, Long> missingTableCache = new HashMap<>();
    private final Map<String, Map<String, String>> senderCache = new HashMap<>();

    public WeChatHookInboxPoller(WeChatHookProperties properties,
                                 WeChatHookClient client,
                                 WeChatHookCursorRepository cursorRepository,
                                 InboxService inboxService) {
        this.properties = properties;
        this.client = client;
        this.cursorRepository = cursorRepository;
        this.inboxService = inboxService;
    }

    @Scheduled(fixedDelayString = "${app.wechat.hook.poll-interval-ms:2000}",
            initialDelayString = "${app.wechat.hook.initial-delay-ms:5000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (properties.isCallbackEnabled()) {
                // 回调模式由 HookCallbackController 接收消息，避免与数据库轮询重复投递
                return;
            }
            List<String> allowedChats = properties.allowedChatIds();
            if (allowedChats.isEmpty()) {
                logErrorThrottled("WECHAT_ALLOWED_CHATS 未配置，Hook 收消息轮询未启动");
                return;
            }
            WeChatHookClient.HookHealth health = client.verify(false);
            refreshDiscoveryIfNeeded();
            refreshContactsIfNeeded();
            for (String chatId : allowedChats) {
                pollChat(chatId, health.account());
            }
            lastError = "";
        } catch (RuntimeException ex) {
            logErrorThrottled(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void pollChat(String chatId, String account) {
        TableRef tableRef = findTable(chatId);
        if (tableRef == null) {
            return;
        }
        Set<String> columns = columns(tableRef.database(), tableRef.table());
        String anchor = anchor(columns);
        if (anchor == null) {
            return;
        }

        WeChatHookCursor cursor = cursorRepository.findByAccountAndChatId(account, chatId).orElse(null);
        if (cursor == null || !cursor.isInitialized()) {
            cursor = cursor == null ? newCursor(account, chatId, anchor) : cursor;
            long initialValue = properties.isSkipHistory() ? latestAnchor(tableRef, anchor) : 0;
            cursor.setAnchor(anchor);
            cursor.setCursorValue(initialValue);
            cursor.setInitialized(true);
            cursorRepository.save(cursor);
            if (properties.isSkipHistory()) {
                return;
            }
        }

        long cursorValue = cursor.getCursorValue();
        if (!anchor.equals(cursor.getAnchor())) {
            cursor.setAnchor(anchor);
            cursor.setCursorValue(properties.isSkipHistory() ? latestAnchor(tableRef, anchor) : 0);
            cursorRepository.save(cursor);
            if (properties.isSkipHistory()) {
                return;
            }
            cursorValue = cursor.getCursorValue();
        }

        List<Map<String, Object>> rows = rowsAfter(tableRef, columns, anchor, cursorValue);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> senderMap = senderMap(tableRef.database());
        int accepted = 0;
        for (Map<String, Object> row : rows) {
            long rowAnchor = longValue(getIgnoreCase(row, anchor), cursor.getCursorValue());
            Converted converted = convert(chatId, tableRef.database(), row, senderMap, account);
            if (converted != null) {
                inboxService.accept(converted.event(), converted.extras());
                accepted++;
            }
            cursor.setCursorValue(Math.max(cursor.getCursorValue(), rowAnchor));
            cursorRepository.save(cursor);
        }
        if (accepted > 0) {
            log.info("Hook 接收到 {} 条文本消息，会话 {}", accepted, chatId);
        }
    }

    /** 一条消息转换后的事件 + 微信侧附加信息（引用原文、图片密钥）。 */
    record Converted(AgentDtos.AgentEventRequest event, HookExtras extras) {}

    AgentDtos.AgentEventRequest convertRow(String chatId,
                                                    String database,
                                                    Map<String, Object> row,
                                                    Map<String, String> senderMap,
                                                    String selfWxid) {
        Converted converted = convert(chatId, database, row, senderMap, selfWxid);
        return converted == null ? null : converted.event();
    }

    Converted convert(String chatId,
                      String database,
                      Map<String, Object> row,
                      Map<String, String> senderMap,
                      String selfWxid) {
        int localType = intValue(getIgnoreCase(row, "local_type"), 0);
        if (localType <= 0) {
            return null;
        }
        String content = stringValue(getIgnoreCase(row, "message_content"));
        MediaMessageParser.Parsed media = null;
        MediaMessageParser.Quoted quote = null;
        if (localType == MediaMessageParser.TYPE_TEXT) {
            String contentType = stringValue(getIgnoreCase(row, "content_type")).toLowerCase(Locale.ROOT);
            if (!contentType.isEmpty() && !"text".equals(contentType)) {
                return null;
            }
            if (content.isBlank()) {
                return null;
            }
        } else if ((quote = MediaMessageParser.parseQuote(localType, content)) != null) {
            // 引用回复本质是文本：用本次说的话当内容，被引用原文单独带走
            content = quote.text();
        } else {
            media = MediaMessageParser.parse(localType, content);
            content = media.placeholder();
        }

        boolean group = chatId.contains("@chatroom");
        String senderId = senderMap.getOrDefault(stringValue(getIgnoreCase(row, "real_sender_id")), "");
        Matcher prefix = GROUP_SENDER_PREFIX.matcher(content);
        boolean hasSenderPrefix = group && prefix.find();
        if (hasSenderPrefix && senderId.isBlank()) {
            senderId = prefix.group(1);
        }
        if (hasSenderPrefix && !senderId.isBlank() && isKnownSender(prefix.group(1), senderId, senderMap)) {
            content = content.substring(prefix.end());
        }
        if (senderId.isBlank() && !group) {
            senderId = chatId;
        }
        if (selfWxid != null && !selfWxid.isBlank()
                && (senderId.equalsIgnoreCase(selfWxid) || senderId.startsWith(selfWxid))) {
            return null;
        }
        if (senderId.isBlank()) {
            senderId = chatId;
        }

        String anchorValue = firstNonBlank(
                stringValue(getIgnoreCase(row, "local_id")),
                stringValue(getIgnoreCase(row, "server_id")),
                stringValue(getIgnoreCase(row, "create_time")));
        if (anchorValue.isBlank()) {
            return null;
        }
        String eventId = "wxh_" + sha256Short(database + ":" + chatId + ":" + anchorValue);
        OffsetDateTime sentAt = timestamp(getIgnoreCase(row, "create_time"));

        AgentDtos.AgentEventRequest event = new AgentDtos.AgentEventRequest(
                eventId,
                null,
                "message",
                truncate(chatId, 180),
                truncate(displayName(chatId), 180),
                group ? ChatType.GROUP : ChatType.PRIVATE,
                truncate(senderId, 180),
                truncate(displayName(senderId), 180),
                truncate(content.trim(), 10_000),
                sentAt,
                false,
                media == null ? null : media.kind().name(),
                media == null ? null : media.name(),
                media == null ? null : media.size(),
                media == null ? null : media.url(),
                media == null && quote == null ? null : localType
        );
        return new Converted(event, HookExtras.of(quote, media));
    }

    private boolean isKnownSender(String prefixSender, String senderId, Map<String, String> senderMap) {
        return prefixSender.equals(senderId) || senderMap.containsValue(prefixSender);
    }

    private void refreshDiscoveryIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryAt < DISCOVERY_TTL_MILLIS) {
            return;
        }
        List<String> names = client.databases();
        databaseNames = Set.copyOf(names);
        List<String> discoveredShards = names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("message_"))
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".db"))
                .sorted()
                .toList();
        if (discoveredShards.isEmpty()) {
            throw new WeChatHookException("Hook 未发现已打开的 message_*.db 数据库");
        }
        boolean changed = !discoveredShards.equals(shards);
        shards = discoveredShards;
        tableCache.clear();
        missingTableCache.clear();
        senderCache.clear();
        lastDiscoveryAt = now;
        if (changed) {
            log.info("Hook 数据库发现完成: {} 个消息分片", shards.size());
        }
    }

    private void refreshContactsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastContactScanAt < CONTACT_TTL_MILLIS) {
            return;
        }
        lastContactScanAt = now;
        String contactDb = databaseNames.stream()
                .filter(name -> "contact.db".equalsIgnoreCase(name))
                .findFirst()
                .orElse("");
        if (contactDb.isBlank()) {
            return;
        }
        try {
            Set<String> contactColumns = columns(contactDb, "contact");
            List<String> selected = new ArrayList<>();
            for (String candidate : List.of("username", "remark", "nick_name", "alias")) {
                if (contactColumns.contains(candidate)) {
                    selected.add(candidate);
                }
            }
            if (!selected.contains("username")) {
                return;
            }
            String sql = "SELECT " + quotedList(selected) + " FROM \"contact\" LIMIT 5000";
            List<Map<String, Object>> rows = client.query(contactDb, sql);
            Map<String, String> loaded = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String username = stringValue(getIgnoreCase(row, "username"));
                if (username.isBlank()) {
                    continue;
                }
                String display = firstNonBlank(
                        stringValue(getIgnoreCase(row, "remark")),
                        stringValue(getIgnoreCase(row, "nick_name")),
                        stringValue(getIgnoreCase(row, "alias")),
                        username);
                loaded.put(username, display);
            }
            contacts = Map.copyOf(loaded);
        } catch (RuntimeException ex) {
            log.debug("联系人读取失败，将使用 wxid 作为显示名: {}", ex.getMessage());
        }
    }

    private TableRef findTable(String chatId) {
        String cached = tableCache.get(chatId);
        if (cached != null) {
            int separator = cached.indexOf('|');
            if (separator > 0) {
                return new TableRef(cached.substring(0, separator), cached.substring(separator + 1));
            }
        }
        Long missCheckedAt = missingTableCache.get(chatId);
        if (missCheckedAt != null && System.currentTimeMillis() - missCheckedAt < DISCOVERY_TTL_MILLIS) {
            return null;
        }
        String table = "Msg_" + md5(chatId);
        for (String shard : shards) {
            try {
                List<Map<String, Object>> rows = client.query(shard,
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
                if (!rows.isEmpty()) {
                    tableCache.put(chatId, shard + "|" + table);
                    missingTableCache.remove(chatId);
                    return new TableRef(shard, table);
                }
            } catch (RuntimeException ex) {
                log.debug("查询消息分片 {} 失败: {}", shard, ex.getMessage());
            }
        }
        missingTableCache.put(chatId, System.currentTimeMillis());
        return null;
    }

    private Set<String> columns(String database, String table) {
        List<Map<String, Object>> rows = client.query(database, "PRAGMA TABLE_INFO(\"" + table + "\")");
        Set<String> columns = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String name = stringValue(getIgnoreCase(row, "name")).toLowerCase(Locale.ROOT);
            if (!name.isBlank()) {
                columns.add(name);
            }
        }
        return columns;
    }

    private String anchor(Set<String> columns) {
        for (String candidate : List.of("local_id", "server_id", "create_time")) {
            if (columns.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private long latestAnchor(TableRef tableRef, String anchor) {
        List<Map<String, Object>> rows = client.query(tableRef.database(),
                "SELECT \"" + anchor + "\" FROM \"" + tableRef.table() + "\" ORDER BY \"" + anchor + "\" DESC LIMIT 1");
        return rows.isEmpty() ? 0 : longValue(getIgnoreCase(rows.getFirst(), anchor), 0);
    }

    private List<Map<String, Object>> rowsAfter(TableRef tableRef, Set<String> columns, String anchor, long cursor) {
        List<String> selected = new ArrayList<>();
        for (String candidate : List.of("local_id", "server_id", "local_type", "real_sender_id", "create_time", "message_content")) {
            if (columns.contains(candidate)) {
                selected.add(candidate);
            }
        }
        if (!selected.contains("message_content")) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(properties.getBatchSize(), 500));
        String sql = "SELECT " + quotedList(selected)
                + ", typeof(\"message_content\") AS content_type FROM \"" + tableRef.table() + "\""
                + " WHERE \"" + anchor + "\" > " + cursor
                + " ORDER BY \"" + anchor + "\" ASC LIMIT " + limit;
        return client.query(tableRef.database(), sql);
    }

    private Map<String, String> senderMap(String database) {
        return senderCache.computeIfAbsent(database, ignored -> {
            try {
                List<Map<String, Object>> rows = client.query(database, "SELECT rowid, user_name FROM \"Name2Id\"");
                Map<String, String> mapping = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    mapping.put(stringValue(getIgnoreCase(row, "rowid")),
                            stringValue(getIgnoreCase(row, "user_name")));
                }
                return Map.copyOf(mapping);
            } catch (RuntimeException ex) {
                return Map.of();
            }
        });
    }

    private WeChatHookCursor newCursor(String account, String chatId, String anchor) {
        WeChatHookCursor cursor = new WeChatHookCursor();
        cursor.setAccount(account);
        cursor.setChatId(chatId);
        cursor.setAnchor(anchor);
        return cursor;
    }

    private String displayName(String wxid) {
        if (wxid == null || wxid.isBlank()) {
            return "";
        }
        return contacts.getOrDefault(wxid, wxid);
    }

    private void logErrorThrottled(String message) {
        long now = System.currentTimeMillis();
        if (!message.equals(lastError) || now - lastErrorLogAt >= ERROR_LOG_INTERVAL_MILLIS) {
            log.warn("微信 Hook 轮询不可用: {}", message);
            lastError = message;
            lastErrorLogAt = now;
        }
    }

    private static String quotedList(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(32);
            for (byte b : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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

    private static Object getIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static OffsetDateTime timestamp(Object value) {
        long timestamp = longValue(value, 0);
        if (timestamp > 10_000_000_000L) {
            timestamp /= 1000;
        }
        if (timestamp <= 0) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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

    private record TableRef(String database, String table) {}
}







