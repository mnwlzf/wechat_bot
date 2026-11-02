package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语音合成（TTS）配置，对接阿里云百炼的 {@code qwen-audio-3.1-tts-flash}（WebSocket 双工协议）。
 *
 * <p>接口是「事件流」式的：连上 WebSocket 后发 {@code run-task} → 等 {@code task-started} →
 * 发 {@code continue-task}（带文本）→ 发 {@code finish-task} → 音频以二进制帧回来。见
 * {@code docs/api.md} 的「语音合成」一节。</p>
 */
@ConfigurationProperties(prefix = "app.tts")
public class TtsProperties {
    private boolean enabled = true;
    /**
     * WebSocket 地址。百炼建议用业务空间专属域名
     * {@code wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference}，
     * 老域名 {@code wss://dashscope.aliyuncs.com/api-ws/v1/inference} 仍可用。
     */
    private String baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    /** 百炼（DashScope）API Key，环境变量 {@code DASHSCOPE_API_KEY}。 */
    private String apiKey = "";
    private String model = "qwen-audio-3.1-tts-flash";
    /** 音色，见 Qwen-Audio-TTS 音色列表；默认「龙安欢」这个通用女声。 */
    private String voice = "longanhuan_v3.1";
    /** 音频编码：pcm / wav / mp3 / opus。默认 pcm，由本地补 WAV 头，长度和时间都准。 */
    private String format = "pcm";
    /** 采样率：8000 / 16000 / 22050 / 24000 / 44100 / 48000。 */
    private int sampleRate = 24000;
    /** 音量 0-100。 */
    private int volume = 50;
    /** 语速 0.5-2.0。 */
    private double rate = 1.0;
    /** 音调 0.5-2.0。 */
    private double pitch = 1.0;
    private Duration timeout = Duration.ofSeconds(60);
    /** 单次合成的最大字数，超了直接拒绝（按字数计费）。 */
    private int maxChars = 500;
    /** 合成结果落盘目录。 */
    private String outputDir = "data/tts";
    /** 是否顺便转成微信语音用的 SILK（等 Hook 能发语音时直接用）。 */
    private boolean silk = true;
    /**
     * WAV→SILK 的编码命令。留空时自动用 {@code node scripts/encode-wechat-voice.mjs}
     * （node 优先从 {@code ASR_SILK_DECODER} 里借，没配再找 PATH）。
     */
    private String silkEncoder = "";
    /** 微信语音是 16 kHz，所以开了 SILK 就按这个采样率合成，省一次重采样。 */
    private int silkSampleRate = 16000;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** 打日志 / 接口回显时用，别把整把钥匙写进日志。 */
    public String maskedKey() {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.length() <= 8) {
            return key.isEmpty() ? "" : "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = volume; }
    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }
    public double getPitch() { return pitch; }
    public void setPitch(double pitch) { this.pitch = pitch; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public boolean isSilk() { return silk; }
    public void setSilk(boolean silk) { this.silk = silk; }
    public String getSilkEncoder() { return silkEncoder; }
    public void setSilkEncoder(String silkEncoder) { this.silkEncoder = silkEncoder; }
    public int getSilkSampleRate() { return silkSampleRate; }
    public void setSilkSampleRate(int silkSampleRate) { this.silkSampleRate = silkSampleRate; }
}
