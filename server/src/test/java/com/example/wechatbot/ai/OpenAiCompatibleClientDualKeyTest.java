package com.example.wechatbot.ai;

import com.example.wechatbot.domain.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientDualKeyTest {

    @Test
    void switchesToBackupKeyWhenPrimaryRejected() throws IOException {
        List<String> seenKeys = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            seenKeys.add(auth == null ? "" : auth);
            boolean primary = "Bearer sk-primary".equals(auth);
            int status = primary ? 401 : 200;
            String body = primary
                    ? "{\"error\":{\"message\":\"invalid api key\"}}"
                    : """
                      {"choices":[{"message":{"role":"assistant","content":"备用 Key 已生效"}}],
                       "usage":{"prompt_tokens":1,"completion_tokens":2}}
                      """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProvider provider = provider(server.getAddress().getPort());
            LlmReply reply = new OpenAiCompatibleClient(new ObjectMapper())
                    .complete(provider, List.of("sk-primary", "sk-backup"),
                            List.of(ChatMessage.user("hi")), 0.5, 64);

            assertThat(reply.content()).isEqualTo("备用 Key 已生效");
            assertThat(seenKeys).containsExactly("Bearer sk-primary", "Bearer sk-backup");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsBothKeysWhenAllRejected() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":{\"message\":\"nope\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProvider provider = provider(server.getAddress().getPort());
            assertThatThrownBy(() -> new OpenAiCompatibleClient(new ObjectMapper())
                    .complete(provider, List.of("sk-a", "sk-b"), List.of(ChatMessage.user("hi")), 0.5, 64))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("主 Key")
                    .hasMessageContaining("备用 Key 1")
                    .hasMessageContaining("401");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsBlankKeysAndKeepsSingleKeyBehaviour() throws IOException {
        List<String> seenKeys = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seenKeys.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProvider provider = provider(server.getAddress().getPort());
            new OpenAiCompatibleClient(new ObjectMapper())
                    .complete(provider, List.of("  ", "sk-only"), List.of(ChatMessage.user("hi")), 0.5, 64);
            assertThat(seenKeys).containsExactly("Bearer sk-only");
        } finally {
            server.stop(0);
        }
    }

    private ModelProvider provider(int port) {
        ModelProvider provider = new ModelProvider();
        provider.setName("local-test");
        provider.setBaseUrl("http://127.0.0.1:" + port);
        provider.setModel("test-model");
        provider.setTimeoutSeconds(5);
        return provider;
    }
}
