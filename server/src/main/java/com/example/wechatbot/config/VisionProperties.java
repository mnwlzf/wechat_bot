package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片识别（视觉输入）相关配置。
 *
 * <p>图片会先解密、再按 {@code max-edge} 缩放成 JPEG，以 base64 data URL 塞进模型请求，
 * 因此这里的上限直接决定单次请求的体积与费用。</p>
 */
@ConfigurationProperties(prefix = "app.llm.vision")
public class VisionProperties {
    /** 关掉后图片消息只记录占位文本，不再解码与上传。 */
    private boolean enabled = true;
    /** 长边像素上限；超过则等比缩放。 */
    private int maxEdge = 1024;
    /** 单张图片编码后的字节上限（base64 前的原始字节）。 */
    private int maxBytes = 1_500_000;
    /** 解码后的 JPEG 缓存目录，避免同一条消息重复解码。 */
    private String cacheDir = "data/media";
    /** 微信本地图片密钥所在目录（kvcomm）；留空自动取 %APPDATA%\Tencent\xwechat\net\kvcomm。 */
    private String kvcommDir = "";
    /** 文件名对不上时的时间窗（秒）：同会话目录里消息时间前后这么久内写盘的 .dat 都会试。 */
    private int matchWindowSeconds = 180;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxEdge() { return maxEdge; }
    public void setMaxEdge(int maxEdge) { this.maxEdge = maxEdge; }
    public int getMaxBytes() { return maxBytes; }
    public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
    public String getCacheDir() { return cacheDir; }
    public void setCacheDir(String cacheDir) { this.cacheDir = cacheDir; }
    public String getKvcommDir() { return kvcommDir; }
    public void setKvcommDir(String kvcommDir) { this.kvcommDir = kvcommDir; }
    public int getMatchWindowSeconds() { return matchWindowSeconds; }
    public void setMatchWindowSeconds(int matchWindowSeconds) { this.matchWindowSeconds = matchWindowSeconds; }
}
