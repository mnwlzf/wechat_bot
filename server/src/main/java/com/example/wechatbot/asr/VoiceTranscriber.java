package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 语音消息 → 文字：本地找语音文件 → 解密 → SILK 转 WAV → 调 ASR。
 *
 * <p>任何一步拿不到就返回空，调用方继续按「只能看到时长」处理——转写是加分项，不能影响回复。</p>
 */
@Service
public class VoiceTranscriber {
    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriber.class);

    private final AsrProperties properties;
    private final AsrService asrService;
    private final SilkDecoder silkDecoder;
    private final VoiceFileResolver resolver;

    public VoiceTranscriber(AsrProperties properties, AsrService asrService,
                            SilkDecoder silkDecoder, VoiceFileResolver resolver) {
        this.properties = properties;
        this.asrService = asrService;
        this.silkDecoder = silkDecoder;
        this.resolver = resolver;
    }

    public boolean enabled() {
        return properties.isEnabled() && properties.isTranscribeVoice() && properties.isConfigured();
    }

    /** 直接转写一段音频（管理端自测也走这里）。 */
    public AsrService.Transcription transcribeAudio(byte[] audio) {
        byte[] wav = SilkDecoder.isSilk(audio) ? silkDecoder.toWav(audio) : audio;
        String filename = SilkDecoder.isWav(wav) ? "voice.wav" : "voice.mp3";
        return asrService.transcribe(wav, filename);
    }

    /** 按消息定位本地语音文件（或下载 voiceurl）并转写；拿不到音频或转写失败都返回空。 */
    public Optional<String> transcribe(String md5, OffsetDateTime sentAt, String url) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            Optional<VoiceFileResolver.Voice> voice = resolver.resolve(md5, sentAt, url);
            if (voice.isEmpty()) {
                log.info("语音消息没找到本地音频文件，保持「只能看到时长」: md5={} voiceurl={} sentAt={}",
                        md5, url == null || url.isBlank() ? "无" : url, sentAt);
                return Optional.empty();
            }
            AsrService.Transcription result = transcribeAudio(voice.get().bytes());
            String source = voice.get().path() == null ? "(内存音频)" : voice.get().path().getFileName().toString();
            if (result.text().isBlank()) {
                log.info("语音转写结果为空（可能没有人声）: {}", source);
                return Optional.empty();
            }
            log.info("语音转写完成: {} -> {}", source, result.text());
            return Optional.of(result.text());
        } catch (AsrException ex) {
            log.warn("语音转写失败: {}", ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("语音转写异常: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
