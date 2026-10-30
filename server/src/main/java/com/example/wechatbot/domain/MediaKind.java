package com.example.wechatbot.domain;

/**
 * 微信消息类型归类。文本消息不写该字段（保持 null），其余类型落库便于会话记录展示与统计。
 */
public enum MediaKind {
    IMAGE("图片"),
    VOICE("语音"),
    VIDEO("视频"),
    FILE("文件"),
    LINK("链接"),
    STICKER("表情"),
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
