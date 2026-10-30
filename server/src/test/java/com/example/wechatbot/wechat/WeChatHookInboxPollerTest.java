package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.example.wechatbot.domain.WeChatHookCursor;
import com.example.wechatbot.repository.WeChatHookCursorRepository;
import com.example.wechatbot.service.InboxService;
import com.example.wechatbot.web.dto.AgentDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeChatHookInboxPollerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToPrebuiltProtocolAndAdvancesPostgresBackedCursor() throws Exception {
        String chatId = "wxid_friend";
        String table = "Msg_" + md5(chatId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> route(exchange, table));
        server.start();

        WeChatHookProperties properties = new WeChatHookProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAllowedChats(List.of(chatId));
        properties.setSkipHistory(true);

        WeChatHookCursorRepository cursorRepository = mock(WeChatHookCursorRepository.class);
        Map<String, WeChatHookCursor> cursors = new LinkedHashMap<>();
        when(cursorRepository.findByAccountAndChatId(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(cursors.get(invocation.getArgument(0) + "|" + invocation.getArgument(1))));
        when(cursorRepository.save(any(WeChatHookCursor.class))).thenAnswer(invocation -> {
            WeChatHookCursor cursor = invocation.getArgument(0);
            cursors.put(cursor.getAccount() + "|" + cursor.getChatId(), cursor);
            return cursor;
        });

        InboxService inboxService = mock(InboxService.class);
        when(inboxService.accept(any(), any())).thenReturn(new AgentDtos.EventAccepted("ACCEPTED", "ok", null));

        WeChatHookClient client = new WeChatHookClient(properties);
        WeChatHookInboxPoller poller = new WeChatHookInboxPoller(properties, client, cursorRepository, inboxService);

        poller.poll();
        assertThat(cursors.get("wxid_me|" + chatId).getCursorValue()).isEqualTo(5);

        poller.poll();
        verify(inboxService, times(1)).accept(any(), any());
        ArgumentCaptor<AgentDtos.AgentEventRequest> event = ArgumentCaptor.forClass(AgentDtos.AgentEventRequest.class);
        verify(inboxService).accept(event.capture(), any());
        assertThat(event.getValue().content()).isEqualTo("新消息");
        assertThat(event.getValue().chatName()).isEqualTo("朋友A");
        assertThat(cursors.get("wxid_me|" + chatId).getCursorValue()).isEqualTo(6);
    }

    private void route(HttpExchange exchange, String table) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("/WeChatAgent/health".equals(path)) {
            send(exchange, 404, Map.of("error", "not found"));
            return;
        }
        if ("/QueryDB/status".equals(path)) {
            send(exchange, 200, Map.of("IsLogin", true, "hWeixin", 123456L));
            return;
        }
        if ("/GetSelfProfile".equals(path)) {
            send(exchange, 200, Map.of("wxid", "wxid_me"));
            return;
        }
        if ("/QueryDB/GetAllDBName".equals(path)) {
            send(exchange, 200, List.of(Map.of("dbName", "message_0.db"), Map.of("dbName", "contact.db")));
            return;
        }
        if ("/QueryDB/execute".equals(path)) {
            Map<?, ?> request = objectMapper.readValue(body, Map.class);
            String sql = String.valueOf(request.get("SQL"));
            send(exchange, 200, queryResponse(table, sql));
            return;
        }
        send(exchange, 404, Map.of("error", "not found"));
    }

    private Map<String, Object> queryResponse(String table, String sql) {
        List<Map<String, Object>> rows;
        if (sql.startsWith("PRAGMA TABLE_INFO(\"contact\")")) {
            rows = List.of(Map.of("name", "username"), Map.of("name", "remark"),
                    Map.of("name", "nick_name"), Map.of("name", "alias"));
        } else if (sql.contains(" FROM \"contact\"")) {
            rows = List.of(Map.of("username", "wxid_friend", "remark", "朋友A", "nick_name", "", "alias", ""));
        } else if (sql.contains("sqlite_master")) {
            rows = List.of(Map.of("name", table));
        } else if (sql.startsWith("PRAGMA TABLE_INFO(\"" + table + "\")")) {
            rows = List.of(Map.of("name", "local_id"), Map.of("name", "server_id"),
                    Map.of("name", "local_type"), Map.of("name", "real_sender_id"),
                    Map.of("name", "create_time"), Map.of("name", "message_content"));
        } else if (sql.contains("ORDER BY \"local_id\" DESC")) {
            rows = List.of(Map.of("local_id", "5"));
        } else if (sql.contains("WHERE \"local_id\" > 5")) {
            rows = List.of(Map.of("local_id", "6", "server_id", "9", "local_type", "1",
                    "real_sender_id", "1", "create_time", "1760000000",
                    "message_content", "新消息", "content_type", "text"));
        } else if (sql.contains("Name2Id")) {
            rows = List.of(Map.of("rowid", "1", "user_name", "wxid_friend"));
        } else {
            rows = List.of();
        }
        return Map.of("status", 0, "desc", "", "data", rows);
    }

    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String md5(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
