package com.example.wechatbot.wechat;

import com.example.wechatbot.config.WeChatHookProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeChatHookClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void detectsAuthenticatedOverlayProtocol() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/WeChatAgent/health", exchange -> send(exchange,
                "{\"backend\":\"wechat-hook\",\"integration\":\"WeChatBot-1\",\"version\":\"4.1.10.27\"," +
                        "\"startupState\":\"ready\",\"nativeQuery\":true,\"databaseAccounts\":[\"wxid_account\"]}"));
        server.createContext("/WeChatAgent/self", exchange -> send(exchange, "{\"wxid\":\"wxid_me\"}"));
        server.start();

        WeChatHookProperties properties = new WeChatHookProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAccount("wxid_account");
        WeChatHookClient client = new WeChatHookClient(properties);

        WeChatHookClient.HookHealth health = client.verify(true);

        assertThat(health.protocol()).isEqualTo("OVERLAY");
        assertThat(health.account()).isEqualTo("wxid_me");
        assertThat(client.protocol()).isEqualTo(WeChatHookClient.Protocol.OVERLAY);
    }

    @Test
    void rejectsNonLoopbackHookUrl() {
        WeChatHookProperties properties = new WeChatHookProperties();
        properties.setBaseUrl("http://192.168.1.20:30001");

        assertThatThrownBy(() -> new WeChatHookClient(properties))
                .isInstanceOf(WeChatHookException.class)
                .hasMessageContaining("回环地址");
    }

    private void send(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
