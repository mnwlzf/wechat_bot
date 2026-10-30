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

    private MediaMessageParser() {}

    public record Parsed(MediaKind kind, String placeholder, String name, Long size, String url) {}

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

    public static Parsed parse(int type, String rawContent) {
        String raw = rawContent == null ? "" : rawContent;
        MediaKind kind = kindOf(type, raw);
        String title = first(TITLE, raw);
        String fileName = first(FILENAME, raw);
        String name = !fileName.isBlank() ? fileName : title;
        Long size = number(TOTAL_LEN, raw);
        String url = sanitizeUrl(first(URL, raw));

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
        return new Parsed(kind, placeholder, name.isBlank() ? null : clip(name, 255), size, url);
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

    private static String clip(String value, int max) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
