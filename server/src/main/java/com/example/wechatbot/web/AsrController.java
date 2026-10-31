package com.example.wechatbot.web;

import com.example.wechatbot.asr.AsrException;
import com.example.wechatbot.asr.AsrService;
import com.example.wechatbot.asr.SilkDecoder;
import com.example.wechatbot.asr.VoiceTranscriber;
import com.example.wechatbot.config.AsrProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音转文字（ASR）状态与自测。
 *
 * <p>自测入口很实用：接口是通用的 {@code /audio/transcriptions}，拿任意一段 wav / mp3，
 * 或者直接给一个微信语音文件（SILK / 加密 .dat）的路径，都能验证「密钥对不对、模型名对不对、
 * SILK 解码器通不通」。</p>
 */
@RestController
@RequestMapping("/api/admin/asr")
public class AsrController {
    private final AsrProperties properties;
    private final AsrService asrService;
    private final SilkDecoder silkDecoder;
    private final VoiceTranscriber voiceTranscriber;

    public AsrController(AsrProperties properties, AsrService asrService,
                         SilkDecoder silkDecoder, VoiceTranscriber voiceTranscriber) {
        this.properties = properties;
        this.asrService = asrService;
        this.silkDecoder = silkDecoder;
        this.voiceTranscriber = voiceTranscriber;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("configured", asrService.enabled());
        result.put("model", properties.getModel());
        result.put("baseUrl", properties.getBaseUrl());
        result.put("apiKey", properties.maskedKey());
        result.put("transcribeVoice", properties.isTranscribeVoice());
        result.put("silkDecoder", silkDecoder.backend());
        result.put("silkDecoderReady", silkDecoder.available());
        result.put("maxBytes", properties.getMaxBytes());
        return result;
    }

    /** 上传一段音频直接转写。 */
    @PostMapping(path = "/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> testUpload(@RequestPart("file") MultipartFile file) {
        try {
            return run(file.getBytes(), file.getOriginalFilename());
        } catch (IOException ex) {
            return failure("读取上传文件失败: " + ex.getMessage());
        }
    }

    /** 也可以给本机路径（支持微信的 SILK / 加密 .dat）。 */
    @PostMapping(path = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> testPath(@RequestBody Map<String, String> body) {
        String raw = body == null ? "" : body.getOrDefault("path", "");
        if (raw.isBlank()) {
            return failure("请提供 path（本机音频文件路径）或直接上传 file");
        }
        Path path = Path.of(raw.trim());
        if (!Files.isRegularFile(path)) {
            return failure("文件不存在: " + path);
        }
        try {
            return run(Files.readAllBytes(path), path.getFileName().toString());
        } catch (IOException ex) {
            return failure("读取文件失败: " + ex.getMessage());
        }
    }

    private Map<String, Object> run(byte[] audio, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", filename == null ? "" : filename);
        result.put("bytes", audio == null ? 0 : audio.length);
        boolean silk = SilkDecoder.isSilk(audio);
        result.put("silk", silk);
        try {
            AsrService.Transcription transcription = voiceTranscriber.transcribeAudio(audio);
            result.put("ok", true);
            result.put("text", transcription.text());
            result.put("model", transcription.model());
            result.put("latencyMs", transcription.latencyMs());
            result.put("uploadedBytes", transcription.bytes());
            return result;
        } catch (AsrException ex) {
            return failure(ex.getMessage());
        } catch (RuntimeException ex) {
            return failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }
}
