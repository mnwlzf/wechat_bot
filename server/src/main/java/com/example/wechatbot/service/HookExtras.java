package com.example.wechatbot.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * @param quoteMd5    被引用的图片原图 md5（被引用的不是图片时为 {@code null}）
 * @param quoteThumbMd5 被引用的图片缩略图 md5
 * @param quoteAt     被引用消息的发送时间，定位本地 {@code .dat} 时按它算月份和时间窗
 * @param mediaMd5    图片原图 md5，用于定位本地 {@code .dat}
 * @param mediaAeskey 图片原图解密密钥
 * @param mediaThumbMd5    图片缩略图 md5（本地文件名有时用它）
 * @param mediaThumbAeskey 图片缩略图密钥
 */
public record HookExtras(String quoteAuthor,
                         String quoteText,
                         String quoteMd5,
                         String quoteThumbMd5,
                         OffsetDateTime quoteAt,
                         String mediaMd5,
                         String mediaAeskey,
                         String mediaThumbMd5,
                         String mediaThumbAeskey) {
    public static final HookExtras EMPTY =
            new HookExtras(null, null, null, null, null, null, null, null, null);

    public boolean hasQuote() {
        return quoteText != null && !quoteText.isBlank();
    }

    public boolean hasImageKey() {
        return (mediaMd5 != null && !mediaMd5.isBlank())
                || (mediaThumbMd5 != null && !mediaThumbMd5.isBlank());
    }

    /** 被引用的是一条图片，且拿得到定位本地文件用的 md5。 */
    public boolean hasQuotedImage() {
        return (quoteMd5 != null && !quoteMd5.isBlank())
                || (quoteThumbMd5 != null && !quoteThumbMd5.isBlank());
    }

    public static HookExtras of(MediaMessageParser.Quoted quote, MediaMessageParser.Parsed media) {
        MediaMessageParser.Parsed quotedMedia = quote == null ? null : quote.media();
        return new HookExtras(
                quote == null ? null : quote.quoteAuthor(),
                quote == null ? null : quote.quoteText(),
                quotedMedia == null ? null : quotedMedia.md5(),
                quotedMedia == null ? null : quotedMedia.thumbMd5(),
                epochSeconds(quote),
                media == null ? null : media.md5(),
                media == null ? null : media.aeskey(),
                media == null ? null : media.thumbMd5(),
                media == null ? null : media.thumbAeskey()
        );
    }

    private static OffsetDateTime epochSeconds(MediaMessageParser.Quoted quote) {
        Long seconds = quote == null ? null : quote.quoteEpochSeconds();
        return seconds == null || seconds <= 0
                ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault());
    }

    /** 定位本地图片文件用的 md5 候选：原图优先，其次缩略图。 */
    public List<String> md5Candidates() {
        return candidates(mediaMd5, mediaThumbMd5);
    }

    /** 定位「被引用的那张图片」本地文件用的 md5 候选。 */
    public List<String> quoteMd5Candidates() {
        return candidates(quoteMd5, quoteThumbMd5);
    }

    private static List<String> candidates(String primary, String secondary) {
        List<String> values = new java.util.ArrayList<>(2);
        if (primary != null && !primary.isBlank()) {
            values.add(primary);
        }
        if (secondary != null && !secondary.isBlank() && !values.contains(secondary)) {
            values.add(secondary);
        }
        return values;
    }
}
