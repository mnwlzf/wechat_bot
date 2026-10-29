package com.example.wechatbot.ai;

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

class DeepSeekSearchClientTest {

    @Test
    void buildsOfficialSearchEndpoint() {
        assertThat(DeepSeekSearchClient.searchEndpoint("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1/tools/search");
        assertThat(DeepSeekSearchClient.searchEndpoint("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com/v1/tools/search");
        assertThat(DeepSeekSearchClient.searchEndpoint("https://api.deepseek.com/v1"))
                .isEqualTo("https://api.deepseek.com/v1/tools/search");
        assertThat(DeepSeekSearchClient.searchEndpoint("https://proxy.local/compatible-mode/v1"))
                .isEqualTo("https://proxy.local/compatible-mode/v1/tools/search");
        assertThat(DeepSeekSearchClient.searchEndpoint("")).isEqualTo("https://api.deepseek.com/v1/tools/search");
    }

    @Test
    void parsesResultsAndFailsOverToBackupKey() throws IOException {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> auths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            auths.add(auth == null ? "" : auth);
            boolean primary = "Bearer sk-primary".equals(auth);
            int status = primary ? 402 : 200;
            String body = primary ? "{\"error\":{\"message\":\"insufficient balance\"}}" : """
                    {"results":[
                      {"rerank_score":2.4,"title":"台风路径","url":"https://weather.example.com/typhoon",
                       "snippet":"今年第 9 号台风","summary":"台风将在明天登陆浙江沿海，风力 12 级。",
                       "content":"","site_name":"天气网","site_icon":null,
                       "date":"2026-10-28T10:00:00Z","authority":3}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DeepSeekSearchClient.SearchOutcome outcome = new DeepSeekSearchClient(new ObjectMapper())
                    .search(base, List.of("sk-primary", "sk-backup"), "明天台风", "明天台风到哪了",
                            5, false, 10);

            assertThat(paths).containsExactly("/v1/tools/search", "/v1/tools/search");
            assertThat(auths).containsExactly("Bearer sk-primary", "Bearer sk-backup");
            assertThat(outcome.usedKeyIndex()).isEqualTo(1);
            assertThat(outcome.results()).hasSize(1);
            DeepSeekSearchClient.SearchResult hit = outcome.results().getFirst();
            assertThat(hit.title()).isEqualTo("台风路径");
            assertThat(hit.siteName()).isEqualTo("天气网");
            assertThat(hit.authority()).isEqualTo(3);
            assertThat(hit.bestExcerpt()).contains("明天登陆浙江沿海");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsWhenNoKeyConfigured() {
        assertThatThrownBy(() -> new DeepSeekSearchClient(new ObjectMapper())
                .search("https://api.deepseek.com", List.of("  "), "q", null, 5, false, 5))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("API Key");
    }
}
