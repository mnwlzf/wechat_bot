package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.example.wechatbot.wechat.db.WeChatVoiceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 语音消息 → 文字：本地库里取音频 → SILK 转 WAV → 调 ASR。
 *
 * <p>取音频的顺序：</p>
 * <ol>
 *   <li>{@code media_*.db} 的 {@code VoiceInfo}：按报文 {@code msgid} = {@code svr_id} 精确命中；</li>
 *   <li>同一张表里按 {@code create_time}（消息时间）在时间窗内找最近的；</li>
 *   <li>最后才退回老路子（{@code voiceurl} 下载 / 本地 {@code .dat}、{@code .silk} 文件）。</li>
 * </ol>
 *
 * <p>每步都是加分项：全拿不到就返回空，调用方继续按「只能看到时长」处理，不影响回复。</p>
 */
@Service
public class VoiceTranscriber {
    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriber.class);

    private final AsrProperties properties;
    private final AsrService asrService;
    private final SilkDecoder silkDecoder;
    private final VoiceFileResolver resolver;
    private final WeChatVoiceStore voiceStore;

    public VoiceTranscriber(AsrProperties properties, AsrService asrService,
                            SilkDecoder silkDecoder, VoiceFileResolver resolver,
                            WeChatVoiceStore voiceStore) {
        this.properties = properties;
        this.asrService = asrService;
        this.silkDecoder = silkDecoder;
        this.resolver = resolver;
        this.voiceStore = voiceStore;
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

    /** 兼容老调用：没有 svrId 时只按时间和 md5 找。 */
    public Optional<String> transcribe(String md5, OffsetDateTime sentAt, String url) {
        return transcribe(null, md5, sentAt, url);
    }

    /**
     * 取音频并转写；拿不到音频或转写失败都返回空。
     *
     * @param svrId 微信服务器消息 ID（报文 {@code msgid}），语音库里精确定位用
     */
    public Optional<String> transcribe(Long svrId, String md5, OffsetDateTime sentAt, String url) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            Optional<WeChatVoiceStore.Voice> stored = stored(svrId, sentAt);
            if (stored.isPresent()) {
                AsrService.Transcription result = transcribeAudio(stored.get().bytes());
                if (result.text().isBlank()) {
                    log.info("语音转写结果为空（可能没有人声）: {}", stored.get().shard());
                    return Optional.empty();
                }
                log.info("语音转写完成（本地库 {}）: {} -> {}", stored.get().shard(), result.text().length(), result.text());
                return Optional.of(result.text());
            }
            Optional<VoiceFileResolver.Voice> voice = resolver.resolve(md5, sentAt, url);
            if (voice.isEmpty()) {
                log.info("语音消息没找到音频（本地库未命中、磁盘也没有），保持「只能看到时长」: svrId={} md5={} voiceurl={} sentAt={}",
                        svrId, md5, url == null || url.isBlank() ? "无" : url, sentAt);
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

    private Optional<WeChatVoiceStore.Voice> stored(Long svrId, OffsetDateTime sentAt) {
        if (!voiceStore.enabled()) {
            return Optional.empty();
        }
        Optional<WeChatVoiceStore.Voice> byId = voiceStore.findBySvrId(svrId == null ? 0L : svrId);
        if (byId.isPresent()) {
            return byId;
        }
        if (sentAt == null) {
            return Optional.empty();
        }
        return voiceStore.findByCreateTime(sentAt.toEpochSecond(), Math.max(30, properties.getVoiceMatchWindowSeconds()));
    }
}
