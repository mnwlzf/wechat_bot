package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语音转文字（ASR）配置。
 *
 * <p>两种 provider：</p>
 * <ul>
 *   <li>{@code bailian}（默认）—— 阿里云百炼 {@code qwen-audio-3.1-asr-flash}，同步 HTTP 接口，
 *       音频用 base64 data URL 直接传，不需要公网 URL；</li>
 *   <li>{@code siliconflow} —— OpenAI 风格的 {@code POST /v1/audio/transcriptions}
 *       （multipart {@code file} + {@code model}）。</li>
 * </ul>
 *
 * <p>语音消息在微信里是 SILK 编码，两种接口都只收 wav / mp3 这类常见格式，
 * 所以 {@link #silkDecoder} 指向一个外部的 SILK→WAV 解码命令（可选）。</p>
 */
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {
    /** {@code bailian} 或 {@code siliconflow}。 */
    private String provider = "bailian";
    private boolean enabled = true;
    /** 百炼的地域域名；硅基流动则是 {@code https://api.siliconflow.cn}。 */
    private String baseUrl = "https://dashscope.aliyuncs.com";
    private String apiKey = "";
    private String model = "qwen-audio-3.1-asr-flash";
    private Duration timeout = Duration.ofSeconds(90);
    /** 单次上传上限，接口限制 50MB。 */
    private int maxBytes = 50 * 1024 * 1024;
    /** 收到语音消息时是否自动转写（关掉就回到「只能看到时长」的老行为）。 */
    private boolean transcribeVoice = true;
    /**
     * SILK→WAV 解码命令。两种写法：
     * <ul>
     *   <li>{@code node D:\WeChatAgent-main\scripts\decode-wechat-voice.mjs} —— JSON 进 JSON 出
     *       （stdin {@code {"b64":...}}，stdout {@code {"b64":...}}）；</li>
     *   <li>带占位符的文件模式，例如 {@code ffmpeg -y -i {input} {output}}。</li>
     * </ul>
     * 留空时自动探测 {@code node} + WeChatAgent 的解码脚本。
     */
    private String silkDecoder = "";
    /** 没有 md5 时，按消息时间在本地找语音文件的允许偏差（秒）。 */
    private int voiceMatchWindowSeconds = 180;

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

    public boolean isBailian() {
        return provider == null || provider.isBlank() || "bailian".equalsIgnoreCase(provider.trim());
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public boolean isTranscribeVoice() { return transcribeVoice; }
    public void setTranscribeVoice(boolean transcribeVoice) { this.transcribeVoice = transcribeVoice; }
    public String getSilkDecoder() { return silkDecoder; }
    public void setSilkDecoder(String silkDecoder) { this.silkDecoder = silkDecoder; }
    public int getVoiceMatchWindowSeconds() { return voiceMatchWindowSeconds; }
    public void setVoiceMatchWindowSeconds(int voiceMatchWindowSeconds) {
        this.voiceMatchWindowSeconds = voiceMatchWindowSeconds;
    }
}
