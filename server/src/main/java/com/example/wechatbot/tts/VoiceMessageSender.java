package com.example.wechatbot.tts;

import com.example.wechatbot.config.WeChatHookProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code .silk} 交给微信 Hook 发出去——目前**还没有路可走**，先把位置留好。
 *
 * <p>当前这个 Hook（{@code version.dll}，4.1.10.27）只暴露了 {@code /SendTextMsg} 和
 * {@code /SendImgMsg}，{@code /SendVoiceMsg}、{@code /SendAudioMsg}、{@code /SendFileMsg} 都是 404。
 * 所以这里做的是「按约定去试一下，明确告诉调用方 Hook 不支持」，而不是假装能发。
 * 换到带发语音接口的 Hook 后，只要它认 {@code {"wxidorgid":...,"path":"<abs .silk>"}}，
 * 这里不用改代码就能用（必要时改 {@link #ROUTE} 和请求体即可）。</p>
 */
@Service
public class VoiceMessageSender {
    private static final Logger log = LoggerFactory.getLogger(VoiceMessageSender.class);
    /** 常见命名；不支持的 Hook 会回 404。 */
    private static final String ROUTE = "/SendVoiceMsg";

    private final WeChatHookProperties hookProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VoiceMessageSender(WeChatHookProperties hookProperties, ObjectMapper objectMapper) {
        this.hookProperties = hookProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** @param status {@code sent} / {@code unsupported} / {@code failed} */
    public record SendResult(String status, String detail) {}

    public SendResult send(String chatId, Path silkFile) {
        if (chatId == null || chatId.isBlank()) {
            return new SendResult("failed", "缺少 chatId");
        }
        if (silkFile == null || !java.nio.file.Files.isRegularFile(silkFile)) {
            return new SendResult("failed", "SILK 文件不存在: " + silkFile);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("wxidorgid", chatId.trim());
        body.put("path", silkFile.toAbsolutePath().toString());
        String base = hookProperties.getBaseUrl().replaceAll("/+$", "");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + ROUTE))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 404) {
                return new SendResult("unsupported",
                        "这个 Hook 没有发语音的接口（" + ROUTE + " 404）；需要换带发语音能力的 Hook");
            }
            if (code / 100 != 2) {
                return new SendResult("failed", "Hook 返回 " + code + ": " + abbreviate(response.body(), 200));
            }
            log.info("语音已通过 Hook 发出: chat={} file={}", chatId, silkFile.getFileName());
            return new SendResult("sent", abbreviate(response.body(), 200));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SendResult("failed", "请求被中断");
        } catch (IOException ex) {
            return new SendResult("failed", "Hook 请求失败: " + ex.getMessage());
        }
    }

    private static String abbreviate(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
