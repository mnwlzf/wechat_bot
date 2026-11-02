package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两条 provider 的路子各跑一遍：百炼的 base64 内联（同步 HTTP）和硅基流动的 multipart。
 * 用一个本地 HttpServer 抓请求体，验证形状而不是只看返回值。
 */
class AsrServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个只回一种响应的本地服务，把收到的请求留下来。 */
    private AtomicReference<String> server(int status, String response) throws IOException {
        AtomicReference<String> captured = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                captured.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            captured.updateAndGet(previous -> previous + "\n@@headers@@\n"
                    + exchange.getRequestHeaders().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
                    .reduce("", (a, b) -> a + b + "\n"));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return captured;
    }

    private AsrProperties properties() {
        AsrProperties properties = new AsrProperties();
        properties.setApiKey("sk-test");
        properties.setTimeout(Duration.ofSeconds(20));
        return properties;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] wav() {
        return WavFixture.wav(16000, new byte[3200]);
    }

    @Test
    @DisplayName("百炼：音频以 base64 data URL 内联，响应取 output.text")
    void transcribesWithBailian() throws IOException {
        AtomicReference<String> captured = server(200,
                "{\"output\":{\"text\":\"今天天气不错\",\"sentence\":{\"text\":\"今天天气不错\"}},"
                        + "\"usage\":{\"duration\":1},\"request_id\":\"r1\"}");
        AsrProperties properties = properties();
        properties.setBaseUrl(baseUrl());
        AsrService service = new AsrService(properties, objectMapper);

        AsrService.Transcription result = service.transcribe(wav(), "voice.wav");

        assertThat(result.text()).isEqualTo("今天天气不错");
        assertThat(result.model()).isEqualTo("qwen-audio-3.1-asr-flash");
        assertThat(result.bytes()).isEqualTo(wav().length);

        String request = captured.get();
        JsonNode body = objectMapper.readTree(request.substring(0, request.indexOf("\n@@headers@@")));
        assertThat(body.path("model").asText()).isEqualTo("qwen-audio-3.1-asr-flash");
        JsonNode content = body.path("input").path("messages").get(0).path("content").get(0);
        assertThat(content.path("type").asText()).isEqualTo("input_audio");
        assertThat(content.path("input_audio").path("data").asText())
                .startsWith("data:audio/wav;base64,");
        assertThat(body.path("parameters").path("format").asText()).isEqualTo("wav");
        assertThat(body.path("parameters").path("sample_rate").asText()).isEqualTo("16000");
        assertThat(request.toLowerCase(java.util.Locale.ROOT)).contains("x-dashscope-sse=disable");
        assertThat(request).contains("Authorization=Bearer sk-test");
    }

    @Test
    @DisplayName("百炼：只有 output.sentence.text 时也能读到")
    void fallsBackToSentenceText() throws IOException {
        server(200, "{\"output\":{\"sentence\":{\"text\":\"只在此处有\"}}}");
        AsrProperties properties = properties();
        properties.setBaseUrl(baseUrl());

        AsrService.Transcription result = new AsrService(properties, objectMapper).transcribe(wav(), "voice.wav");

        assertThat(result.text()).isEqualTo("只在此处有");
    }

    @Test
    @DisplayName("百炼：4xx 变成可读异常，带响应体")
    void surfacesHttpError() throws IOException {
        server(400, "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\"}");
        AsrProperties properties = properties();
        properties.setBaseUrl(baseUrl());

        assertThatThrownBy(() -> new AsrService(properties, objectMapper).transcribe(wav(), "voice.wav"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("InvalidApiKey");
    }

    @Test
    @DisplayName("硅基流动：还是 multipart /v1/audio/transcriptions，响应取 text")
    void transcribesWithSiliconFlow() throws IOException {
        AtomicReference<String> captured = server(200, "{\"text\":\"今天天气不错\"}");
        AsrProperties properties = properties();
        properties.setProvider("siliconflow");
        properties.setBaseUrl(baseUrl());
        properties.setModel("XingChenAGI/XingChenASR-V3.2-Ultra");
        AsrService service = new AsrService(properties, objectMapper);

        AsrService.Transcription result = service.transcribe(wav(), "voice.wav");

        assertThat(result.text()).isEqualTo("今天天气不错");
        String request = captured.get();
        assertThat(request).contains("name=\"model\"").contains("XingChenAGI/XingChenASR-V3.2-Ultra")
                .contains("filename=\"voice.wav\"").contains("audio/wav");
    }

    @Test
    @DisplayName("base-url 带不带 /v1 都能拼对")
    void toleratesV1Suffix() throws IOException {
        AtomicReference<String> captured = server(200, "{\"text\":\"ok\"}");
        AsrProperties properties = properties();
        properties.setProvider("siliconflow");
        properties.setBaseUrl(baseUrl() + "/v1/");
        properties.setModel("m");
        AsrService service = new AsrService(properties, objectMapper);

        assertThat(service.transcribe(wav(), "voice.wav").text()).isEqualTo("ok");
        assertThat(service.provider()).isEqualTo("siliconflow");
        assertThat(captured.get()).contains("name=\"model\"");
    }

    @Test
    @DisplayName("没配 Key 时直接拒绝")
    void rejectsWithoutKey() {
        AsrProperties properties = properties();
        properties.setApiKey("");

        assertThat(new AsrService(properties, objectMapper).enabled()).isFalse();
        assertThatThrownBy(() -> new AsrService(properties, objectMapper).transcribe(wav(), "voice.wav"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    @DisplayName("格式/采样率从字节里认，不靠文件名")
    void detectsFormatAndRate() {
        byte[] pcm = WavFixture.wav(24000, new byte[100]);
        assertThat(AsrService.formatOf(pcm, "x.bin")).isEqualTo("wav");
        assertThat(AsrService.sampleRateOf(pcm)).isEqualTo(24000);
        assertThat(AsrService.formatOf(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0}, "x")).isEqualTo("mp3");
        assertThat(AsrService.formatOf(new byte[]{1, 2, 3}, "a.m4a")).isEqualTo("m4a");
        assertThat(AsrService.sampleRateOf(new byte[]{1, 2, 3})).isEqualTo(16000);
    }
}
