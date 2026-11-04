package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 让聊天对象自己选人设（{@code app.persona.*}）。
 *
 * <p>用户在群里或私聊里发「换人设」，机器人只把<b>人设名称</b>列出来让他回数字，
 * 选完立刻把这个会话绑定到那份人设。列表里不包含提示词正文，避免把设定漏给聊天对象。</p>
 */
@ConfigurationProperties(prefix = "app.persona")
public class PersonaSwitchProperties {
    /** 关掉后聊天里发「换人设」不会触发任何反应。 */
    private boolean switchEnabled = true;

    /** 列出名单后，等用户选编号的有效期（秒）；超时后按普通消息处理。 */
    private int pendingSeconds = 300;

    public boolean isSwitchEnabled() {
        return switchEnabled;
    }

    public void setSwitchEnabled(boolean switchEnabled) {
        this.switchEnabled = switchEnabled;
    }

    public int getPendingSeconds() {
        return pendingSeconds;
    }

    public void setPendingSeconds(int pendingSeconds) {
        this.pendingSeconds = pendingSeconds;
    }
}
