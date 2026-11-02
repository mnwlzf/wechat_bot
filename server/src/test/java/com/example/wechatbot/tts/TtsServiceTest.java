package com.example.wechatbot.tts;

import com.example.wechatbot.config.TtsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @TempDir
    Path tempDir;

    private TtsProperties properties;
    private QwenTtsClient client;
    private SilkEncoder silkEncoder;
    private TtsService service;

    @BeforeEach
    void setUp() {
        properties = new TtsProperties();
        properties.setApiKey("sk-test");
        properties.setOutputDir(tempDir.resolve("tts").toString());
        client = mock(QwenTtsClient.class);
        silkEncoder = mock(SilkEncoder.class);
        when(silkEncoder.available()).thenReturn(false);
        service = new TtsService(properties, client, silkEncoder);
    }

    @Test
    @DisplayName("pcm 结果补上 WAV 头再落盘，时长按采样率算出来")
    void writesWavFromPcm() throws IOException {
        byte[] pcm = new byte[24000 * 2];   // 24000Hz / 16bit / 单声道 = 1 秒
        when(client.synthesize(anyString(), any(), any(), anyInt()))
                .thenReturn(new QwenTtsClient.Result(pcm, "pcm", 24000, 3, 120));

        TtsService.Speech speech = service.synthesize("你好");

        assertThat(speech.name()).startsWith("tts-").endsWith(".wav");
        assertThat(speech.format()).isEqualTo("wav");
        assertThat(speech.durationMs()).isEqualTo(1000);
        assertThat(speech.chunks()).isEqualTo(3);
        assertThat(speech.bytes()).isEqualTo(44 + pcm.length);
        assertThat(speech.silkName()).isNull();
        byte[] written = Files.readAllBytes(speech.file());
        assertThat(new String(written, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(written, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(new String(written, 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
    }

    @Test
    @DisplayName("有 SILK 编码器时按 16k 合成，顺手生成 .silk")
    void writesSilkNextToWav() throws IOException {
        when(silkEncoder.available()).thenReturn(true);
        byte[] pcm = new byte[16000 * 2];   // 1 秒 @16k
        when(client.synthesize(anyString(), any(), any(), anyInt()))
                .thenReturn(new QwenTtsClient.Result(pcm, "pcm", 16000, 2, 90));
        byte[] silk = new byte[]{2, '#', '!', 'S', 'I', 'L', 'K', '_', 'V', '3', 18, 0, 1, 2};
        when(silkEncoder.encode(any(byte[].class), anyInt()))
                .thenReturn(new SilkEncoder.Encoded(silk, 50, 1000, 16000));

        TtsService.Speech speech = service.synthesize("你好");

        assertThat(speech.sampleRate()).isEqualTo(16000);
        assertThat(speech.silkName()).isNotNull().endsWith(".silk");
        assertThat(speech.silkBytes()).isEqualTo(silk.length);
        assertThat(speech.silkFrames()).isEqualTo(50);
        assertThat(Files.readAllBytes(service.outputDir().resolve(speech.silkName()))).isEqualTo(silk);
        assertThat(service.silkAvailable()).isTrue();
    }

    @Test
    @DisplayName("SILK 编码失败不影响 WAV，只在结果里带一句原因")
    void silkFailureIsNotFatal() throws IOException {
        when(silkEncoder.available()).thenReturn(true);
        when(client.synthesize(anyString(), any(), any(), anyInt()))
                .thenReturn(new QwenTtsClient.Result(new byte[3200], "pcm", 16000, 1, 50));
        when(silkEncoder.encode(any(byte[].class), anyInt()))
                .thenThrow(new TtsException("SILK 编码失败: VOICE_ENCODER_MISSING"));

        TtsService.Speech speech = service.synthesize("你好");

        assertThat(speech.silkName()).isNull();
        assertThat(speech.silkError()).contains("VOICE_ENCODER_MISSING");
        assertThat(Files.readAllBytes(speech.file())).isNotEmpty();
    }

    @Test
    @DisplayName("mp3 原样落盘，不套 WAV 头")
    void keepsMp3AsIs() throws IOException {
        byte[] mp3 = "ID3-fake-mp3".getBytes(StandardCharsets.US_ASCII);
        when(client.synthesize(anyString(), any(), any(), anyInt()))
                .thenReturn(new QwenTtsClient.Result(mp3, "mp3", 22050, 1, 80));

        TtsService.Speech speech = service.synthesize("hi", "longanhuan_v3.1", "mp3", 22050);

        assertThat(speech.name()).endsWith(".mp3");
        assertThat(speech.format()).isEqualTo("mp3");
        assertThat(speech.durationMs()).isEqualTo(-1);
        assertThat(Files.readAllBytes(speech.file())).isEqualTo(mp3);
    }

    @Test
    @DisplayName("文件接口只能读 outputDir 里的文件，挡掉目录穿越")
    void rejectsTraversal() {
        assertThatThrownBy(() -> service.file("../.env")).isInstanceOf(TtsException.class);
        assertThatThrownBy(() -> service.file("a/b.wav")).isInstanceOf(TtsException.class);
        assertThatThrownBy(() -> service.file("nope.wav")).isInstanceOf(TtsException.class);
    }

    @Test
    @DisplayName("列表按名字倒序，带可播放的 url")
    void listsFiles() {
        when(client.synthesize(anyString(), any(), any(), anyInt()))
                .thenReturn(new QwenTtsClient.Result(new byte[]{1, 2}, "pcm", 24000, 1, 10));
        service.synthesize("one");
        service.synthesize("two");

        List<Map<String, Object>> files = service.files(10);

        assertThat(files).hasSize(2);
        assertThat((String) files.get(0).get("url")).startsWith("/api/admin/tts/files/tts-");
        assertThat(service.file((String) files.get(0).get("name"))).exists();
    }

    @Test
    @DisplayName("没配 key 时直接拒绝，不碰网络")
    void rejectsWhenNotConfigured() {
        properties.setApiKey("");

        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(() -> service.synthesize("hi")).isInstanceOf(TtsException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }
}
