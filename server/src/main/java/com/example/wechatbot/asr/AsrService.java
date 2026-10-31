package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 语音转文字：{@code POST {baseUrl}/audio/transcriptions}（multipart，字段 {@code file} + {@code model}），
 * 返回 {@code {"text": "..."}}。默认对接硅基流动，模型可配。
 */
@Service
public class AsrService {
    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final AsrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AsrService(AsrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** @param text 转写结果；接口返回空串表示这段音频没有可识别的人声 */
    public record Transcription(String text, String model, long latencyMs, int bytes) {}

    public boolean enabled() {
        return properties.isConfigured();
    }

    public String model() {
        return properties.getModel();
    }

    /**
     * @param audio    音频字节（wav / mp3 / m4a 等接口支持的格式；SILK 需要先解码）
     * @param filename 上传时用的文件名，接口按后缀判断格式
     */
    public Transcription transcribe(byte[] audio, String filename) {
        if (!enabled()) {
            throw new AsrException("ASR 未配置：请在 .env 里填 ASR_API_KEY");
        }
        if (audio == null || audio.length == 0) {
            throw new AsrException("音频为空");
        }
        if (audio.length > properties.getMaxBytes()) {
            throw new AsrException("音频超过上限 " + properties.getMaxBytes() + " 字节");
        }
        String name = filename == null || filename.isBlank() ? "voice.wav" : filename.trim();
        String boundary = "----wechatbot" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, audio, name);
        String base = properties.getBaseUrl().replaceAll("/+$", "");

        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions"))
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey().trim())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AsrException("ASR 请求被中断", ex);
        } catch (IOException ex) {
            throw new AsrException("ASR 请求失败: " + ex.getMessage(), ex);
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        String payload = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw new AsrException("ASR 接口返回 " + response.statusCode() + ": " + abbreviate(payload, 300));
        }
        String text;
        try {
            JsonNode node = objectMapper.readTree(payload.isBlank() ? "{}" : payload);
            text = node.path("text").asText("");
        } catch (IOException ex) {
            throw new AsrException("ASR 返回不是合法 JSON: " + abbreviate(payload, 200), ex);
        }
        log.debug("ASR 完成: model={} bytes={} latency={}ms text={}", properties.getModel(),
                audio.length, latencyMs, abbreviate(text, 120));
        return new Transcription(text.trim(), properties.getModel(), latencyMs, audio.length);
    }

    private byte[] multipart(String boundary, byte[] audio, String filename) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + properties.getModel() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentTypeOf(filename) + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 512);
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(audio);
        out.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static String contentTypeOf(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (name.endsWith(".ogg") || name.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (name.endsWith(".flac")) {
            return "audio/flac";
        }
        return "audio/wav";
    }

    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
