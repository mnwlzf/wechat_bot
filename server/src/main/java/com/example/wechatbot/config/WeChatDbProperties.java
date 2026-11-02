package com.example.wechatbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信 4.x 本地数据库（SQLCipher）读取配置。
 *
 * <p>语音消息的音频只存在 {@code db_storage/message/media_*.db} 的 {@code VoiceInfo.voice_data}
 * 里，磁盘上没有单独的音频文件，报文里也没有可用的下载地址。要读它必须先有这个账号的
 * 数据库密钥（64 位 hex），密钥与账号绑定，换账号要重新取。</p>
 *
 * <p>密钥怎么来：微信 4.x 的库是 SQLCipher 加密的，密钥只在登录时派生。取值方式见
 * {@code docs/api.md} 的「语音转文字」一节。</p>
 */
@ConfigurationProperties(prefix = "app.wechat.db")
public class WeChatDbProperties {
    private static final Logger log = LoggerFactory.getLogger(WeChatDbProperties.class);

    /** 总开关：关掉就完全不碰本地数据库（语音回到「只能看到时长」）。 */
    private boolean enabled = true;

    /** 数据库密钥（64 位 hex，账号维度）。留空表示还没配。 */
    private String key = "";

    /** 微信数据目录；留空自动找 {@code ~/Documents/xwechat_files/*}。 */
    private String dataDir = "";

    /** SQLCipher 页大小，微信 4.x 固定 4096。 */
    private int pageSize = 4096;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key == null ? "" : key.trim();
        if (!this.key.isEmpty() && !isValidKey(this.key)) {
            log.warn("app.wechat.db.key 不是 64 位 hex（长度 {}），按未配置处理", this.key.length());
        }
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir == null ? "" : dataDir.trim();
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /** 配了开关、配了合法密钥才算可用。 */
    public boolean isConfigured() {
        return enabled && isValidKey(key);
    }

    public static boolean isValidKey(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 日志/接口里只露头尾各 4 位。 */
    public String maskedKey() {
        if (!isValidKey(key)) {
            return "";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
