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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 语音转文字。
 *
 * <p>默认走阿里云百炼的 {@code qwen-audio-3.1-asr-flash}：同步接口
 * {@code POST {baseUrl}/api/v1/services/aigc/multimodal-generation/generation}，
 * 音频以 base64 data URL 直接内联在请求体里（不用先传到公网），
 * 返回的文本在 {@code output.text}（也在 {@code output.sentence.text}）。</p>
 *
 * <p>也可以切回硅基流动那种 OpenAI 风格的 {@code POST /v1/audio/transcriptions}（multipart，
 * 返回 {@code {"text": "..."}}），把 {@code ASR_PROVIDER=siliconflow} 即可。</p>
 */
@Service
public class AsrService {
    private static final Logger log = LoggerFactory.getLogger(AsrService.class);
    private static final String BAILIAN_PATH = "/api/v1/services/aigc/multimodal-generation/generation";

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

    public String provider() {
        return properties.isBailian() ? "bailian" : "siliconflow";
    }

    /**
     * @param audio    音频字节（wav / mp3 / m4a 等接口支持的格式；SILK 需要先解码）
     * @param filename 上传时用的文件名，接口按后缀判断格式
     */
    public Transcription transcribe(byte[] audio, String filename) {
        if (!enabled()) {
            throw new AsrException("ASR 未配置：请在 .env 里填 DASHSCOPE_API_KEY（或 ASR_API_KEY）");
        }
        if (audio == null || audio.length == 0) {
            throw new AsrException("音频为空");
        }
        if (audio.length > properties.getMaxBytes()) {
            throw new AsrException("音频超过上限 " + properties.getMaxBytes() + " 字节");
        }
        String name = filename == null || filename.isBlank() ? "voice.wav" : filename.trim();
        return properties.isBailian() ? transcribeWithBailian(audio, name) : transcribeWithOpenAi(audio, name);
    }

    // ------------------------------------------------------------------ 阿里云百炼

    private Transcription transcribeWithBailian(byte[] audio, String filename) {
        String format = formatOf(audio, filename);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", format);
        // 采样率按 WAV 头来（微信语音解出来是 16 kHz，TTS 生成的是 16/24 kHz）
        parameters.put("sample_rate", String.valueOf(sampleRateOf(audio)));

        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", "data:audio/" + format + ";base64," + Base64.getEncoder().encodeToString(audio));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "input_audio");
        content.put("input_audio", inputAudio);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(content));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", List.of(message));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", input);
        body.put("parameters", parameters);

        String base = properties.getBaseUrl().replaceAll("/+$", "");
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + BAILIAN_PATH))
                    .timeout(properties.getTimeout())
                    .header("Authorization", "Bearer " + properties.getApiKey().trim())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-SSE", "disable")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AsrException("构造 ASR 请求失败: " + ex.getMessage(), ex);
        }

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
            JsonNode output = node.path("output");
            text = output.path("text").asText("");
            if (text.isBlank()) {
                text = output.path("sentence").path("text").asText("");
            }
            if (text.isBlank() && node.hasNonNull("code")) {
                throw new AsrException("ASR 接口报错 " + node.path("code").asText()
                        + ": " + node.path("message").asText(""));
            }
        } catch (IOException ex) {
            throw new AsrException("ASR 返回不是合法 JSON: " + abbreviate(payload, 200), ex);
        }
        log.debug("ASR 完成: provider=bailian model={} bytes={} latency={}ms text={}", properties.getModel(),
                audio.length, latencyMs, abbreviate(text, 120));
        return new Transcription(text.trim(), properties.getModel(), latencyMs, audio.length);
    }

    // ------------------------------------------------------------------ OpenAI 兼容（硅基流动）

    private Transcription transcribeWithOpenAi(byte[] audio, String filename) {
        String boundary = "----wechatbot" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, audio, filename);
        // 兼容两种写法：https://api.siliconflow.cn 和 https://api.siliconflow.cn/v1
        String base = properties.getBaseUrl().replaceAll("/+$", "").replaceAll("/v1$", "");

        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/audio/transcriptions"))
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
        log.debug("ASR 完成: provider=siliconflow model={} bytes={} latency={}ms text={}", properties.getModel(),
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

    /** 百炼要看真实格式，WAV / MP3 各一种。 */
    static String formatOf(byte[] audio, String filename) {
        if (audio.length >= 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
            return "wav";
        }
        if (audio.length > 2 && (audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0) {
            return "mp3";
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) {
            return "mp3";
        }
        if (name.endsWith(".m4a")) {
            return "m4a";
        }
        return "wav";
    }

    /** WAV 头里的采样率（偏移 24，小端 32 位），读不到就按 16 kHz。 */
    static int sampleRateOf(byte[] audio) {
        if (audio.length >= 44 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
            int rate = (audio[24] & 0xFF) | ((audio[25] & 0xFF) << 8)
                    | ((audio[26] & 0xFF) << 16) | ((audio[27] & 0xFF) << 24);
            if (rate >= 8000 && rate <= 96000) {
                return rate;
            }
        }
        return 16000;
    }

    static String contentTypeOf(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
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
