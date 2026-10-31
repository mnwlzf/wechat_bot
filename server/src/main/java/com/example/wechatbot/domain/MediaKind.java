package com.example.wechatbot.domain;

/**
 * 微信消息类型归类。文本消息不写该字段（保持 null），其余类型落库便于会话记录展示与统计。
 *
 * <p>归类粒度按「回复策略是否要区别对待」来定：图片要取本地文件给模型看、语音无法转写、
 * 文件与链接要带出名字和地址，其余卡片类只需要让模型知道「他发的是一张位置 / 名片 / 小程序」。</p>
 */
public enum MediaKind {
    IMAGE("图片"),
    VOICE("语音"),
    VIDEO("视频"),
    FILE("文件"),
    LINK("链接"),
    STICKER("表情"),
    /** 位置卡片（type=48）。 */
    LOCATION("位置"),
    /** 个人 / 公众号名片（type=42）。 */
    CARD("名片"),
    /** 小程序卡片（appmsg 子类型 33 / 36）。 */
    MINIAPP("小程序"),
    /** 合并转发的聊天记录（appmsg 子类型 19）。 */
    RECORD("聊天记录"),
    /** 视频号动态（appmsg 子类型 51、type=62）。 */
    CHANNEL("视频号"),
    /** 转账（appmsg 子类型 2000）。 */
    TRANSFER("转账"),
    /** 红包（appmsg 子类型 2001）。 */
    REDPACKET("红包"),
    /** 音视频通话记录（type=50）。 */
    CALL("通话"),
    SYSTEM("系统消息"),
    OTHER("其他");

    private final String label;

    MediaKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
