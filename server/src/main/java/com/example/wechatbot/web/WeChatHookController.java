package com.example.wechatbot.web;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.wechat.WeChatHookClient;
import com.example.wechatbot.wechat.WeChatHookException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/wechat")
public class WeChatHookController {
    private final WeChatHookProperties properties;
    private final WeChatHookClient client;

    public WeChatHookController(WeChatHookProperties properties, WeChatHookClient client) {
        this.properties = properties;
        this.client = client;
    }

    @GetMapping("/hook")
    public HookStatusView hookStatus() {
        List<String> chats = properties.allowedChatIds();
        if (!properties.isEnabled()) {
            return new HookStatusView(false, false, false, "", "", "", "", chats, "Hook 集成已关闭", Instant.now());
        }
        try {
            WeChatHookClient.HookHealth health = client.verify(true);
            return new HookStatusView(true, true, true, health.protocol(), health.version(), health.account(),
                    health.startupState(), chats, "", health.checkedAt());
        } catch (RuntimeException ex) {
            return new HookStatusView(true, !chats.isEmpty(), false, client.protocol().name(), "", "", "",
                    chats, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), Instant.now());
        }
    }

@GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(defaultValue = "200") int limit) {
        client.verify(true);
        String sessionDb = findDatabase("session.db");
        List<Map<String, Object>> tables = client.query(sessionDb,
                "SELECT name FROM sqlite_master WHERE type='table' AND lower(name)='session'");
        if (tables.isEmpty()) {
            throw new WeChatHookException("session 表不存在，请先登录微信并等待会话同步");
        }
        String table = stringValue(valueIgnoreCase(tables.getFirst(), "name"));
        List<String> columns = columns(sessionDb, table).stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        List<String> selected = new ArrayList<>();
        for (String candidate : List.of("username", "sort_timestamp", "last_timestamp", "unread_count")) {
            if (columns.contains(candidate)) {
                selected.add(candidate);
            }
        }
        if (!selected.contains("username")) {
            throw new WeChatHookException("session 表未暴露 username 列");
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return client.query(sessionDb, "SELECT " + quoted(selected) + " FROM \"" + table + "\" LIMIT " + safeLimit);
    }

    @GetMapping("/contacts")
    public List<Map<String, Object>> contacts(@RequestParam(defaultValue = "200") int limit) {
        client.verify(true);
        String contactDb = findDatabase("contact.db");
        List<String> columns = columns(contactDb, "contact").stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        List<String> selected = new ArrayList<>();
        for (String candidate : List.of("username", "remark", "nick_name", "alias")) {
            if (columns.contains(candidate)) {
                selected.add(candidate);
            }
        }
        if (!selected.contains("username")) {
            throw new WeChatHookException("contact 表未暴露 username 列");
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return client.query(contactDb, "SELECT " + quoted(selected) + " FROM \"contact\" LIMIT " + safeLimit);
    }

    private String findDatabase(String expected) {
        return client.databases().stream()
                .filter(name -> expected.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new WeChatHookException(expected + " 尚未打开，请登录微信并等待数据同步"));
    }

    private List<String> columns(String database, String table) {
        return client.query(database, "PRAGMA TABLE_INFO(\"" + table + "\")").stream()
                .map(row -> stringValue(valueIgnoreCase(row, "name")))
                .filter(name -> !name.isBlank())
                .toList();
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }    public record HookStatusView(
            boolean enabled,
            boolean configured,
            boolean connected,
            String protocol,
            String version,
            String account,
            String startupState,
            List<String> allowedChats,
            String message,
            Instant checkedAt
    ) {}
}




