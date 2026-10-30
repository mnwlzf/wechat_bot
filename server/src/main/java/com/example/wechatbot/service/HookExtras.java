package com.example.wechatbot.service;

import java.util.List;

/**
 * 微信 Hook 集成专用的一层附加信息。
 *
 * <p>这些字段只对「从微信消息里解析出来的事件」有意义，对外部 Agent 调用
 * {@code /api/agent/events} 的场景不适用，因此不塞进公开的事件 DTO，
 * 而是作为 {@link InboxService#accept(com.example.wechatbot.web.dto.AgentDtos.AgentEventRequest, HookExtras)}
 * 的可选参数传入。</p>
 *
 * @param quoteAuthor 引用消息里被引用那条消息的发送者
 * @param quoteText   引用消息里被引用的原文
 * @param mediaMd5    图片原图 md5，用于定位本地 {@code .dat}
 * @param mediaAeskey 图片原图解密密钥
 * @param mediaThumbMd5    图片缩略图 md5（本地文件名有时用它）
 * @param mediaThumbAeskey 图片缩略图密钥
 */
public record HookExtras(String quoteAuthor,
                         String quoteText,
                         String mediaMd5,
                         String mediaAeskey,
                         String mediaThumbMd5,
                         String mediaThumbAeskey) {
    public static final HookExtras EMPTY = new HookExtras(null, null, null, null, null, null);

    public boolean hasQuote() {
        return quoteText != null && !quoteText.isBlank();
    }

    public boolean hasImageKey() {
        return (mediaMd5 != null && !mediaMd5.isBlank())
                || (mediaThumbMd5 != null && !mediaThumbMd5.isBlank());
    }

    public static HookExtras of(MediaMessageParser.Quoted quote, MediaMessageParser.Parsed media) {
        return new HookExtras(
                quote == null ? null : quote.quoteAuthor(),
                quote == null ? null : quote.quoteText(),
                media == null ? null : media.md5(),
                media == null ? null : media.aeskey(),
                media == null ? null : media.thumbMd5(),
                media == null ? null : media.thumbAeskey()
        );
    }

    /** 定位本地图片文件用的 md5 候选：原图优先，其次缩略图。 */
    public List<String> md5Candidates() {
        List<String> values = new java.util.ArrayList<>(2);
        if (mediaMd5 != null && !mediaMd5.isBlank()) {
            values.add(mediaMd5);
        }
        if (mediaThumbMd5 != null && !mediaThumbMd5.isBlank() && !values.contains(mediaThumbMd5)) {
            values.add(mediaThumbMd5);
        }
        return values;
    }
}
