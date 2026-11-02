package com.example.wechatbot.tts;

import com.example.wechatbot.config.TtsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阿里云百炼 {@code qwen-audio-3.1-tts-flash}（Qwen-Audio-TTS）的 WebSocket 客户端。
 *
 * <p>协议是事件流，一次合成走完这几步：</p>
 * <ol>
 *   <li>连 {@code wss://…/api-ws/v1/inference}，头部带 {@code Authorization: bearer <key>}；</li>
 *   <li>发 {@code run-task}（模型、音色、采样率、格式都在这里定）；</li>
 *   <li>收到 {@code task-started} 后发 {@code continue-task}（待合成文本）再发 {@code finish-task}；</li>
 *   <li>音频以<b>二进制帧</b>回来，收完等 {@code task-finished}。</li>
 * </ol>
 *
 * <p>同一次合成里三个事件的 {@code task_id} 必须一致，所以这里一次合成一条连接（不池化：
 * 语音回复是一条条来的，复用连接收益不大，出错还难隔离）。</p>
 */
@Service
public class QwenTtsClient {
    private static final Logger log = LoggerFactory.getLogger(QwenTtsClient.class);

    private final TtsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public QwenTtsClient(TtsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** @param audio 原始音频字节（{@code format=pcm} 时是裸 PCM，需要自己补 WAV 头） */
    public record Result(byte[] audio, String format, int sampleRate, int chunks, long latencyMs) {}

    public Result synthesize(String text, String voice, String format, int sampleRate) {
        if (!properties.isConfigured()) {
            throw new TtsException("TTS 未配置：请在 .env 里填 DASHSCOPE_API_KEY");
        }
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new TtsException("待合成文本为空");
        }
        if (content.length() > properties.getMaxChars()) {
            throw new TtsException("文本超过 " + properties.getMaxChars() + " 字（按字数计费，先裁一下）");
        }
        String encoding = format == null || format.isBlank() ? properties.getFormat() : format.trim();
        int rate = sampleRate > 0 ? sampleRate : properties.getSampleRate();
        Session session = new Session(content, voice, encoding, rate);
        URI uri = URI.create(properties.getBaseUrl().trim());

        long startedAt = System.nanoTime();
        WebSocket socket;
        try {
            socket = httpClient.newWebSocketBuilder()
                    .header("Authorization", "bearer " + properties.getApiKey().trim())
                    .header("X-DashScope-DataInspection", "enable")
                    .header("user-agent", "wechat-bot/0.1")
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, session)
                    .get(20, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TtsException("TTS 连接被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new TtsException("TTS 连接失败（检查 API Key / 地域域名）: " + cause.getMessage(), cause);
        } catch (TimeoutException ex) {
            throw new TtsException("TTS 连接超时: " + uri, ex);
        }

        try {
            byte[] audio = session.done.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (audio.length == 0) {
                throw new TtsException("TTS 没有返回音频（文本可能被内容安全策略拦了）");
            }
            log.debug("TTS 完成: model={} voice={} format={} bytes={} chunks={} latency={}ms",
                    properties.getModel(), session.voice, encoding, audio.length, session.chunks.get(), latencyMs);
            return new Result(audio, encoding, rate, session.chunks.get(), latencyMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TtsException("TTS 合成被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw cause instanceof TtsException tts ? tts : new TtsException("TTS 合成失败: " + cause.getMessage(), cause);
        } catch (TimeoutException ex) {
            throw new TtsException("TTS 合成超时（" + properties.getTimeout().toSeconds() + "s）", ex);
        } finally {
            socket.abort();
        }
    }

    /** 一条连接对应一次合成。 */
    private final class Session implements WebSocket.Listener {
        private final String taskId = UUID.randomUUID().toString();
        private final String text;
        private final String voice;
        private final String format;
        private final int sampleRate;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream(64 * 1024);
        private final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private final AtomicInteger chunks = new AtomicInteger();

        private Session(String text, String voice, String format, int sampleRate) {
            this.text = text;
            this.voice = voice == null || voice.isBlank() ? properties.getVoice() : voice.trim();
            this.format = format;
            this.sampleRate = sampleRate;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.sendText(runTaskJson(), true);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String message = data.toString();
            try {
                JsonNode root = objectMapper.readTree(message);
                JsonNode header = root.path("header");
                String event = header.path("event").asText("");
                switch (event) {
                    case "task-started" -> webSocket.sendText(continueTaskJson(), true)
                            .thenCompose(ignored -> webSocket.sendText(finishTaskJson(), true));
                    case "task-finished" -> done.complete(bytes());
                    case "task-failed" -> done.completeExceptionally(new TtsException(
                            "TTS 任务失败: " + header.path("error_code").asText("?")
                                    + " " + header.path("error_message").asText("")));
                    case "error" -> done.completeExceptionally(new TtsException(
                            "TTS 服务报错: " + root.path("payload").path("message").asText(message)));
                    default -> log.trace("TTS 事件: {}", event);
                }
            } catch (Exception ex) {
                done.completeExceptionally(new TtsException("TTS 返回内容无法解析: " + message, ex));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            synchronized (audio) {
                audio.writeBytes(chunk);
            }
            if (last) {
                chunks.incrementAndGet();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 有些情况下服务端先关连接、不再发 task-finished：拿到音频就算成功
            if (audio.size() > 0) {
                done.complete(bytes());
            } else {
                done.completeExceptionally(new TtsException(
                        "TTS 连接被关闭（" + statusCode + " " + reason + "）"));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            done.completeExceptionally(new TtsException("TTS WebSocket 出错: " + error.getMessage(), error));
        }

        private byte[] bytes() {
            synchronized (audio) {
                return audio.toByteArray();
            }
        }

        private String runTaskJson() {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("text_type", "PlainText");
            parameters.put("voice", voice);
            parameters.put("format", format);
            parameters.put("sample_rate", sampleRate);
            parameters.put("volume", properties.getVolume());
            parameters.put("rate", properties.getRate());
            parameters.put("pitch", properties.getPitch());
            parameters.put("enable_ssml", false);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", properties.getModel());
            payload.put("parameters", parameters);
            payload.put("input", Map.of());

            return json(Map.of("header", header("run-task"), "payload", payload));
        }

        private String continueTaskJson() {
            return json(Map.of("header", header("continue-task"), "payload", Map.of("input", Map.of("text", text))));
        }

        private String finishTaskJson() {
            return json(Map.of("header", header("finish-task"), "payload", Map.of("input", Map.of())));
        }

        private Map<String, Object> header(String action) {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("action", action);
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            return header;
        }

        private String json(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                throw new TtsException("构造 TTS 请求失败: " + ex.getMessage(), ex);
            }
        }
    }
}
