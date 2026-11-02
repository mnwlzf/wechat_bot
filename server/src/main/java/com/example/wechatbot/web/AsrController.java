package com.example.wechatbot.web;

import com.example.wechatbot.asr.AsrException;
import com.example.wechatbot.asr.AsrService;
import com.example.wechatbot.asr.SilkDecoder;
import com.example.wechatbot.asr.VoiceTranscriber;
import com.example.wechatbot.config.AsrProperties;
import com.example.wechatbot.config.WeChatDbProperties;
import com.example.wechatbot.wechat.db.WeChatDbKeyHealth;
import com.example.wechatbot.wechat.db.WeChatDbKeyStore;
import com.example.wechatbot.wechat.db.WeChatKeyCapture;
import com.example.wechatbot.wechat.db.WeChatVoiceStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
    private final WeChatVoiceStore voiceStore;
    private final WeChatDbProperties dbProperties;
    private final WeChatDbKeyStore keyStore;
    private final WeChatKeyCapture keyCapture;
    private final WeChatDbKeyHealth keyHealth;

    public AsrController(AsrProperties properties, AsrService asrService,
                         SilkDecoder silkDecoder, VoiceTranscriber voiceTranscriber,
                         WeChatVoiceStore voiceStore, WeChatDbProperties dbProperties,
                         WeChatDbKeyStore keyStore, WeChatKeyCapture keyCapture,
                         WeChatDbKeyHealth keyHealth) {
        this.properties = properties;
        this.asrService = asrService;
        this.silkDecoder = silkDecoder;
        this.voiceTranscriber = voiceTranscriber;
        this.voiceStore = voiceStore;
        this.dbProperties = dbProperties;
        this.keyStore = keyStore;
        this.keyCapture = keyCapture;
        this.keyHealth = keyHealth;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("configured", asrService.enabled());
        result.put("provider", properties.isBailian() ? "bailian" : "siliconflow");
        result.put("model", properties.getModel());
        result.put("baseUrl", properties.getBaseUrl());
        result.put("apiKey", properties.maskedKey());
        result.put("transcribeVoice", properties.isTranscribeVoice());
        result.put("silkDecoder", silkDecoder.backend());
        result.put("silkDecoderReady", silkDecoder.available());
        result.put("maxBytes", properties.getMaxBytes());
        result.put("voiceDb", voiceStore.diagnostics());
        return result;
    }

    /** 本地语音库（media_*.db）自检：密钥对不对、找到几个分片、里面有多少条语音。 */
    @GetMapping("/voice-db")
    public Map<String, Object> voiceDb() {
        Map<String, Object> result = new LinkedHashMap<>(voiceStore.diagnostics());
        result.put("capture", captureInfo());
        return result;
    }

    /**
     * 立刻重新截获一次数据库密钥（换账号、重装微信后用）。
     *
     * <p>会阻塞到截获成功/超时：微信必须正在运行，且要能等到「登录 / 开库」那一刻，
     * 所以通常让用户先退到登录界面再点这个接口。</p>
     */
    @PostMapping("/voice-db/capture")
    public Map<String, Object> capture(@RequestParam(name = "timeoutSeconds", required = false) Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null
                ? dbProperties.getCaptureTimeoutSeconds() : Math.max(10, timeoutSeconds);
        WeChatKeyCapture.Result captured = keyCapture.capture(timeout);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", captured.ok());
        result.put("maskedKey", captured.maskedKey());
        result.put("message", captured.message());
        keyStore.invalidate();
        result.put("voiceDb", voiceStore.diagnostics());
        result.put("capture", captureInfo());
        return result;
    }

    private Map<String, Object> captureInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("available", keyCapture.available());
        info.put("running", keyCapture.running());
        info.put("autoCapture", dbProperties.isAutoCapture());
        info.put("script", Path.of(dbProperties.getCaptureScript()).toAbsolutePath().toString());
        info.put("dll", Path.of(dbProperties.getKeyDll()).toAbsolutePath().toString());
        info.put("lastResult", keyHealth.lastResult());
        return info;
    }

    /** 拿一条具体的语音试试：报文里的 {@code msgid} 填进来，看能不能在本地库里命中。 */
    @GetMapping("/voice-db/{svrId}")
    public Map<String, Object> voiceBySvrId(@PathVariable long svrId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("svrId", svrId);
        Optional<WeChatVoiceStore.Voice> voice = voiceStore.findBySvrId(svrId);
        result.put("found", voice.isPresent());
        voice.ifPresent(hit -> {
            result.put("bytes", hit.bytes().length);
            result.put("shard", hit.shard());
            result.put("head", printable(hit.bytes()));
        });
        return result;
    }

    private static String printable(byte[] bytes) {
        int length = Math.min(16, bytes.length);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            builder.append(value >= 0x20 && value < 0x7f ? (char) value : '.');
        }
        return builder.toString();
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
