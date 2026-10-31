package com.example.wechatbot.asr;

import com.example.wechatbot.config.AsrProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTranscriberTest {
    private AsrProperties properties;
    private AsrService asrService;
    private SilkDecoder silkDecoder;
    private VoiceFileResolver resolver;
    private VoiceTranscriber transcriber;

    @BeforeEach
    void setUp() {
        properties = new AsrProperties();
        properties.setApiKey("sk-test");
        asrService = Mockito.mock(AsrService.class);
        silkDecoder = Mockito.mock(SilkDecoder.class);
        resolver = Mockito.mock(VoiceFileResolver.class);
        transcriber = new VoiceTranscriber(properties, asrService, silkDecoder, resolver);
        when(asrService.enabled()).thenReturn(true);
    }

    private static byte[] wav() {
        return "RIFF____WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("找到 WAV 就直接转写，不再走 SILK 解码")
    void transcribesPlainWav() {
        when(resolver.resolve(any(), any())).thenReturn(
                Optional.of(new VoiceFileResolver.Voice(wav(), null)));
        when(asrService.transcribe(any(), eq("voice.wav")))
                .thenReturn(new AsrService.Transcription("明天上午十点开会", "m", 100, 16));

        Optional<String> text = transcriber.transcribe(null, OffsetDateTime.now());

        assertThat(text).contains("明天上午十点开会");
        verify(silkDecoder, never()).toWav(any());
    }

    @Test
    @DisplayName("找到的是 SILK 时先解码再转写")
    void decodesSilkBeforeTranscribing() {
        byte[] silk = "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII);
        when(resolver.resolve(any(), any())).thenReturn(
                Optional.of(new VoiceFileResolver.Voice(silk, null)));
        when(silkDecoder.toWav(silk)).thenReturn(wav());
        when(asrService.transcribe(any(), eq("voice.wav")))
                .thenReturn(new AsrService.Transcription("收到", "m", 90, 16));

        assertThat(transcriber.transcribe(null, OffsetDateTime.now())).contains("收到");
        verify(silkDecoder).toWav(silk);
    }

    @Test
    @DisplayName("本地没找到语音文件时返回空，不调用 ASR")
    void emptyWhenAudioMissing() {
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());

        assertThat(transcriber.transcribe(null, OffsetDateTime.now())).isEmpty();
        verify(asrService, never()).transcribe(any(), any());
    }

    @Test
    @DisplayName("转写结果为空（没人声）时返回空，不把空串塞给模型")
    void emptyWhenTranscriptBlank() {
        when(resolver.resolve(any(), any())).thenReturn(
                Optional.of(new VoiceFileResolver.Voice(wav(), null)));
        when(asrService.transcribe(any(), any()))
                .thenReturn(new AsrService.Transcription("   ", "m", 10, 16));

        assertThat(transcriber.transcribe(null, OffsetDateTime.now())).isEmpty();
    }

    @Test
    @DisplayName("ASR 报错时吞掉异常返回空，不影响正常回复")
    void swallowsAsrFailure() {
        when(resolver.resolve(any(), any())).thenReturn(
                Optional.of(new VoiceFileResolver.Voice(wav(), null)));
        when(asrService.transcribe(any(), any())).thenThrow(new AsrException("接口返回 500"));

        assertThat(transcriber.transcribe(null, OffsetDateTime.now())).isEmpty();
    }

    @Test
    @DisplayName("没配密钥或关掉开关时整体不可用")
    void disabledWhenNotConfigured() {
        properties.setApiKey("");
        assertThat(transcriber.enabled()).isFalse();

        properties.setApiKey("sk-test");
        properties.setTranscribeVoice(false);
        assertThat(transcriber.enabled()).isFalse();
    }

    @Test
    @DisplayName("管理端自测走同一条链路：SILK 自动解码后再上传")
    void adminProbeReusesPipeline() {
        byte[] silk = "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII);
        when(silkDecoder.toWav(silk)).thenReturn(wav());
        when(asrService.transcribe(any(), any()))
                .thenReturn(new AsrService.Transcription("你好", "m", 5, 16));

        assertThat(transcriber.transcribeAudio(silk).text()).isEqualTo("你好");
    }
}
