package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsrServiceTest {
    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> reply = new AtomicReference<>("{\"text\":\"今天天气不错\"}");

    private AsrProperties properties;
    private AsrService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new AsrProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("sk-test-123456");
        properties.setModel("XingChenAGI/XingChenASR-V3.2-Ultra");
        service = new AsrService(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("上传音频：带 Bearer 头、multipart 里既有 model 也有文件字节")
    void sendsMultipartWithModelAndFile() {
        byte[] audio = "RIFFfakewav".getBytes(StandardCharsets.ISO_8859_1);

        AsrService.Transcription result = service.transcribe(audio, "voice.wav");

        assertThat(result.text()).isEqualTo("今天天气不错");
        assertThat(result.model()).isEqualTo("XingChenAGI/XingChenASR-V3.2-Ultra");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test-123456");
        assertThat(lastContentType.get()).startsWith("multipart/form-data; boundary=");
        String body = lastBody.get();
        assertThat(body).contains("name=\"model\"");
        assertThat(body).contains("XingChenAGI/XingChenASR-V3.2-Ultra");
        assertThat(body).contains("name=\"file\"; filename=\"voice.wav\"");
        assertThat(body).contains(audio.length > 0 ? "RIFFfakewav" : "RIFF");
    }

    @Test
    @DisplayName("接口报错时抛出带状态码和响应体的异常")
    void surfacesHttpError() {
        status.set(400);
        reply.set("");

        assertThatThrownBy(() -> service.transcribe(new byte[]{1, 2, 3}, "voice.wav"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("空文本（没有人声）也正常返回空串，不报错")
    void emptyTextIsNotAnError() {
        reply.set("{\"text\":\"\"}");

        assertThat(service.transcribe(new byte[]{1}, "voice.wav").text()).isEmpty();
    }

    @Test
    @DisplayName("没配密钥时直接拒绝，不发请求")
    void rejectsWhenNotConfigured() {
        properties.setApiKey("");

        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(() -> service.transcribe(new byte[]{1}, "voice.wav"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("ASR_API_KEY");
    }

    @Test
    @DisplayName("超过大小上限的音频直接拒绝")
    void rejectsOversizedAudio() {
        properties.setMaxBytes(4);

        assertThatThrownBy(() -> service.transcribe(new byte[]{1, 2, 3, 4, 5}, "voice.wav"))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("上限");
    }

    @Test
    @DisplayName("按后缀推断上传用的 MIME")
    void mapsContentTypes() {
        assertThat(AsrService.contentTypeOf("voice.wav")).isEqualTo("audio/wav");
        assertThat(AsrService.contentTypeOf("voice.MP3")).isEqualTo("audio/mpeg");
        assertThat(AsrService.contentTypeOf("voice.m4a")).isEqualTo("audio/mp4");
        assertThat(AsrService.contentTypeOf("voice.flac")).isEqualTo("audio/flac");
        assertThat(AsrService.contentTypeOf(null)).isEqualTo("audio/wav");
    }
}
