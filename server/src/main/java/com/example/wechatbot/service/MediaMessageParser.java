package com.example.wechatbot.service;

import com.example.wechatbot.domain.MediaKind;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把微信 Hook 推来的非文本消息（{@code type != 1}）解析成「媒体种类 + 占位文本 + 元数据」。
 *
 * <p>Hook 对图片、语音、文件等消息的 {@code content} 可能是空串，也可能是一段 XML
 * （例如 {@code <msg><appmsg><title>报价单.pdf</title><type>6</type>…</appmsg></msg>}）。
 * 这里做的是尽力而为的提取：解析不出元数据时退化为占位文本，绝不因为格式陌生而丢消息。</p>
 *
 * <p>两类特殊消息在 {@link #parse} 之外单独处理：</p>
 * <ul>
 *   <li><b>引用消息</b>（type=49，appmsg 子类型 57，带 {@code <refermsg>}）用
 *       {@link #parseQuote} 拆成「本次说的话 + 被引用的原文」，它属于文本消息而不是媒体消息；</li>
 *   <li><b>图片</b>（type=3）额外从 {@code <img>} 节点取 {@code md5} 与 {@code aeskey}，
 *       供后续定位本地 {@code .dat} 并解密成可识别的图片。</li>
 * </ul>
 */
public final class MediaMessageParser {
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_VOICE = 34;
    public static final int TYPE_VIDEO = 43;
    public static final int TYPE_STICKER = 47;
    public static final int TYPE_APP = 49;
    public static final int TYPE_SYSTEM = 10000;
    public static final int TYPE_SYSTEM_2 = 10002;

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DES = Pattern.compile("<des>(.*?)</des>", Pattern.DOTALL);
    private static final Pattern FILENAME = Pattern.compile("<filename>(.*?)</filename>", Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("<url>(.*?)</url>", Pattern.DOTALL);
    private static final Pattern TOTAL_LEN = Pattern.compile("<totallen>(\\d+)</totallen>");
    private static final Pattern VOICE_LENGTH = Pattern.compile("voicelength=\"(\\d+)\"");
    private static final Pattern FILE_EXT = Pattern.compile("<fileext>(.*?)</fileext>", Pattern.DOTALL);
    private static final Pattern IMG_LENGTH = Pattern.compile("length=\"(\\d+)\"");
    private static final Pattern IMG_MD5 = Pattern.compile("md5=\"([0-9a-fA-F]{32})\"");
    private static final Pattern IMG_AESKEY = Pattern.compile("aeskey=\"([0-9a-fA-F]{16,64})\"");

    private static final Pattern REFERMSG = Pattern.compile("<refermsg>(.*?)</refermsg>", Pattern.DOTALL);
    private static final Pattern REFER_CONTENT = Pattern.compile("<content>(.*?)</content>", Pattern.DOTALL);
    private static final Pattern REFER_TYPE = Pattern.compile("<type>(\\d+)</type>");
    private static final Pattern DISPLAY_NAME = Pattern.compile("<displayname>(.*?)</displayname>", Pattern.DOTALL);
    private static final Pattern CHAT_USER = Pattern.compile("<chatusr>(.*?)</chatusr>", Pattern.DOTALL);

    private MediaMessageParser() {}

    /**
     * @param md5    图片的 md5，用于在本地 {@code msg/attach/*}{@code /Img/<md5>.dat} 定位原图
     * @param aeskey 图片 V2 格式的解密密钥（16 字节 hex）
     */
    public record Parsed(MediaKind kind, String placeholder, String name, Long size, String url,
                         String md5, String aeskey) {}

    /** 引用消息：{@code text} 是本次说的话，另外两个字段是被引用的对象。 */
    public record Quoted(String text, String quoteAuthor, String quoteText) {}

    public static boolean isText(int type) {
        return type == TYPE_TEXT;
    }

    public static MediaKind kindOf(int type, String rawContent) {
        return switch (type) {
            case TYPE_IMAGE -> MediaKind.IMAGE;
            case TYPE_VOICE -> MediaKind.VOICE;
            case TYPE_VIDEO -> MediaKind.VIDEO;
            case TYPE_STICKER -> MediaKind.STICKER;
            case TYPE_SYSTEM, TYPE_SYSTEM_2 -> MediaKind.SYSTEM;
            case TYPE_APP -> looksLikeFile(rawContent) ? MediaKind.FILE : MediaKind.LINK;
            default -> MediaKind.OTHER;
        };
    }

    /**
     * 识别引用回复：type=49 且带 {@code <refermsg>}，并且本次确实带了文字。
     *
     * <p>微信把「引用一条消息再回复」编码成 appmsg（子类型 57）：外层 {@code <title>} 是用户这次
     * 说的话，{@code <refermsg>} 里装着被引用那条消息的发送者和原文。这类消息本质是文本，
     * 交给 {@link #parse} 会被误判成「链接」，所以必须先走这里。</p>
     *
     * <p>被引用的对象不一定是文字：可能是图片、表情、文件、语音、链接，甚至是一条视频。
     * 所以这里读 {@code <refermsg>} 自己的 {@code <type>}，非文本一律复用 {@link #parse}
     * 生成占位文本（{@code [图片]}、{@code [语音 4″]}、{@code [文件] 报价单.pdf} …），
     * 让模型知道「他在回应一张图」而不是拿到一堆 XML。</p>
     *
     * @return 不是引用消息、或引用里没有文字时返回 {@code null}，交回常规媒体解析
     */
    public static Quoted parseQuote(int type, String rawContent) {
        if (type != TYPE_APP || rawContent == null || rawContent.isBlank()) {
            return null;
        }
        Matcher refer = REFERMSG.matcher(rawContent);
        if (!refer.find()) {
            return null;
        }
        String text = first(TITLE, rawContent);
        if (text.isBlank()) {
            return null;
        }
        String body = refer.group(1);
        String author = first(DISPLAY_NAME, body);
        if (author.isBlank()) {
            author = first(CHAT_USER, body);
        }
        String quoted = first(REFER_CONTENT, body);
        if (quoted.isBlank()) {
            quoted = first(DES, body);
        }
        return new Quoted(
                text,
                blankToNull(clip(author, 180)),
                blankToNull(clip(quotedText(body, quoted), 1000))
        );
    }

    /** 被引用的内容按它自己的消息类型渲染：文字抽正文，其余走占位文本。 */
    private static String quotedText(String referBody, String quotedContent) {
        int quotedType = quotedTypeOf(referBody);
        if (quotedType == TYPE_TEXT) {
            return cleanQuoted(quotedContent);
        }
        // 语音、表情、文件等没有可读正文，交给统一的占位文本（图片 -> [图片]，语音 -> [语音 4″]）
        return parse(quotedType, quotedContent).placeholder();
    }

    /**
     * 取 {@code <refermsg>} 自己的 {@code <type>}。
     *
     * <p>只看 {@code <content>} 之前的部分：被引用内容的 XML 如果没被转义，里面也会带
     * {@code <type>}（比如被引用的是链接卡片），那样会把外层类型读错。</p>
     */
    private static int quotedTypeOf(String referBody) {
        int contentIndex = referBody.indexOf("<content>");
        String head = contentIndex > 0 ? referBody.substring(0, contentIndex) : referBody;
        return intValue(REFER_TYPE, head, TYPE_TEXT);
    }

    private static int intValue(Pattern pattern, String raw, int fallback) {
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static Parsed parse(int type, String rawContent) {
        String raw = rawContent == null ? "" : rawContent;
        MediaKind kind = kindOf(type, raw);
        String title = first(TITLE, raw);
        String fileName = first(FILENAME, raw);
        String name = !fileName.isBlank() ? fileName : title;
        Long size = number(TOTAL_LEN, raw);
        if (size == null && kind == MediaKind.IMAGE) {
            size = number(IMG_LENGTH, raw);
        }
        String url = sanitizeUrl(first(URL, raw));
        String md5 = kind == MediaKind.IMAGE ? blankToNull(first(IMG_MD5, raw)) : null;
        String aeskey = kind == MediaKind.IMAGE ? blankToNull(first(IMG_AESKEY, raw)) : null;

        String placeholder = switch (kind) {
            case IMAGE -> "[图片]";
            case VOICE -> voicePlaceholder(raw);
            case VIDEO -> "[视频]";
            case STICKER -> "[表情]";
            case FILE -> name.isBlank() ? "[文件]" : "[文件] " + clip(name, 80);
            case LINK -> name.isBlank() ? "[链接]" : "[链接] " + clip(name, 80);
            case SYSTEM -> systemPlaceholder(raw);
            case OTHER -> "[消息 type=" + type + "]";
        };
        return new Parsed(kind, placeholder, name.isBlank() ? null : clip(name, 255), size, url, md5, aeskey);
    }

    /** 引用原文本身可能又是一段 XML（被引用的是链接/文件卡片），这里再抽一层。 */
    private static String cleanQuoted(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String inner = value;
        if (inner.contains("<msg") || inner.contains("<appmsg")) {
            String title = first(TITLE, inner);
            String des = first(DES, inner);
            if (!title.isBlank()) {
                inner = title;
            } else if (!des.isBlank()) {
                inner = des;
            } else {
                inner = stripTags(inner);
            }
        } else {
            inner = stripTags(inner);
        }
        return inner.replaceAll("\\s+", " ").trim();
    }

    private static String voicePlaceholder(String raw) {
        Long millis = number(VOICE_LENGTH, raw);
        if (millis == null || millis <= 0) {
            return "[语音]";
        }
        long seconds = Math.max(1, Math.round(millis / 1000.0));
        return "[语音 " + seconds + "″]";
    }

    private static String systemPlaceholder(String raw) {
        String plain = stripTags(raw);
        return plain.isBlank() ? "[系统消息]" : "[系统消息] " + clip(plain, 60);
    }

    private static boolean looksLikeFile(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return raw.contains("<fileext>") || raw.contains("<filename>") || raw.contains("<appattach>");
    }

    private static String first(Pattern pattern, String raw) {
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return "";
        }
        return unescape(stripCdata(matcher.group(1))).trim();
    }

    private static Long number(Pattern pattern, String raw) {
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String sanitizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        return clip(value, 1000);
    }

    private static String stripCdata(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("<![CDATA[")) {
            trimmed = trimmed.substring("<![CDATA[".length());
        }
        if (trimmed.endsWith("]]>")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }

    private static String stripTags(String value) {
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String clip(String value, int max) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
