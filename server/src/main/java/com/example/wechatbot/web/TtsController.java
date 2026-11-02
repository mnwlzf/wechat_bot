package com.example.wechatbot.web;

import com.example.wechatbot.config.TtsProperties;
import com.example.wechatbot.tts.TtsException;
import com.example.wechatbot.tts.TtsService;
import com.example.wechatbot.tts.VoiceMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语音合成（阿里云百炼 {@code qwen-audio-3.1-tts-flash}）自测接口。
 *
 * <p>{@code POST /api/admin/tts} 把一段文字合成成音频，返回 WAV（可试听）和 SILK（微信语音要的格式）。
 * WAV 直接在浏览器里打开 {@code /api/admin/tts/files/<name>} 就能听；SILK 是留给发语音用的。
 * {@code POST /api/admin/tts/send} 会试着让 Hook 发出去——当前 Hook 没有这个接口，会明确返回
 * {@code unsupported}。</p>
 */
@RestController
@RequestMapping("/api/admin/tts")
public class TtsController {
    private static final Logger log = LoggerFactory.getLogger(TtsController.class);

    private final TtsProperties properties;
    private final TtsService ttsService;
    private final VoiceMessageSender voiceSender;

    public TtsController(TtsProperties properties, TtsService ttsService, VoiceMessageSender voiceSender) {
        this.properties = properties;
        this.ttsService = ttsService;
        this.voiceSender = voiceSender;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("configured", properties.isConfigured());
        result.put("provider", "aliyun-bailian");
        result.put("model", properties.getModel());
        result.put("voice", properties.getVoice());
        result.put("format", properties.getFormat());
        result.put("sampleRate", properties.getSampleRate());
        result.put("volume", properties.getVolume());
        result.put("rate", properties.getRate());
        result.put("pitch", properties.getPitch());
        result.put("baseUrl", properties.getBaseUrl());
        result.put("apiKey", properties.maskedKey());
        result.put("maxChars", properties.getMaxChars());
        result.put("silk", properties.isSilk());
        result.put("silkAvailable", ttsService.silkAvailable());
        result.put("silkBackend", ttsService.silkBackend());
        result.put("silkSampleRate", properties.getSilkSampleRate());
        result.put("outputDir", ttsService.outputDir().toString());
        result.put("files", ttsService.files(10));
        // 当前 Hook（version.dll 4.1.10.27）没有发语音的接口
        result.put("wechatVoiceSend", false);
        result.put("wechatVoiceSendNote", "Hook 只开放 /SendTextMsg 与 /SendImgMsg；/SendVoiceMsg 为 404");
        return result;
    }

    /** @param body {@code {"text":"...", "voice":"longanhuan_v3.1", "format":"pcm", "sampleRate":16000}}，只有 text 必填 */
    @PostMapping
    public Map<String, Object> synthesize(@RequestBody Map<String, Object> body) {
        String text = body == null ? "" : text(body.get("text"));
        String voice = text(body.get("voice"));
        String format = text(body.get("format"));
        Integer sampleRate = number(body.get("sampleRate"));
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            TtsService.Speech speech = ttsService.synthesize(text, voice, format, sampleRate);
            result.put("ok", true);
            result.put("file", speech.name());
            result.put("url", "/api/admin/tts/files/" + speech.name());
            result.put("bytes", speech.bytes());
            result.put("format", speech.format());
            result.put("sampleRate", speech.sampleRate());
            result.put("durationMs", speech.durationMs());
            result.put("chunks", speech.chunks());
            result.put("latencyMs", speech.latencyMs());
            result.put("model", speech.model());
            result.put("voice", speech.voice());
            result.put("silk", speech.silkName());
            result.put("silkUrl", speech.silkName() == null ? null : "/api/admin/tts/files/" + speech.silkName());
            result.put("silkBytes", speech.silkBytes());
            result.put("silkFrames", speech.silkFrames());
            if (speech.silkError() != null) {
                result.put("silkError", speech.silkError());
            }
        } catch (TtsException ex) {
            log.warn("TTS 合成失败: {}", ex.getMessage());
            result.put("ok", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    /** 试着让 Hook 把某个 {@code .silk} 发出去（当前 Hook 会返回 unsupported）。 */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) {
        String chatId = text(body == null ? null : body.get("chatId"));
        String name = text(body == null ? null : body.get("file"));
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Path file = ttsService.file(name);
            VoiceMessageSender.SendResult sent = voiceSender.send(chatId, file);
            result.put("ok", "sent".equals(sent.status()));
            result.put("status", sent.status());
            result.put("detail", sent.detail());
        } catch (TtsException ex) {
            result.put("ok", false);
            result.put("status", "failed");
            result.put("detail", ex.getMessage());
        }
        return result;
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<Resource> file(@PathVariable String name) {
        Path path = ttsService.file(name);
        String lower = name.toLowerCase(Locale.ROOT);
        String type = lower.endsWith(".wav") ? "audio/wav"
                : lower.endsWith(".mp3") ? "audio/mpeg"
                : "application/octet-stream";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/files")
    public List<Map<String, Object>> files() {
        return ttsService.files(50);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
