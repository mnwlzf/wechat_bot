package com.example.wechatbot.ai;

import com.example.wechatbot.domain.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientTest {

    @Test
    void normalizesBaseUrl() {
        assertThat(OpenAiCompatibleClient.normalizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(OpenAiCompatibleClient.normalizeBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(OpenAiCompatibleClient.normalizeBaseUrl("https://api.deepseek.com/v1"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(OpenAiCompatibleClient.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(OpenAiCompatibleClient.normalizeBaseUrl("  ")).isEmpty();
    }

    @Test
    void retriesServerErrorAndJoinsArrayContent() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int call = calls.incrementAndGet();
            String body;
            int status;
            if (call == 1) {
                status = 503;
                body = "{\"error\":{\"message\":\"busy\"}}";
            } else {
                status = 200;
                body = """
                        {"choices":[{"message":{"role":"assistant","content":[
                          {"type":"text","text":"你好"},
                          {"type":"text","text":"，世界"}
                        ]}}],"usage":{"prompt_tokens":3,"completion_tokens":5}}
                        """;
            }
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
                    .complete(provider, "sk-test", List.of(ChatMessage.user("hi")), 0.7, 100);

            assertThat(reply.content()).isEqualTo("你好，世界");
            assertThat(reply.promptTokens()).isEqualTo(3);
            assertThat(reply.completionTokens()).isEqualTo(5);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryUnauthorized() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProvider provider = provider(server.getAddress().getPort());
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(new ObjectMapper());

            assertThatThrownBy(() -> client.complete(provider, "bad-key", List.of(ChatMessage.user("hi")), 0.7, 100))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("401");
            assertThat(calls.get()).isEqualTo(1);
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
