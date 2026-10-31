package com.example.wechatbot.service;

import com.example.wechatbot.domain.MediaKind;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把微信 Hook 推来的非文本消息（{@code type != 1}）解析成「媒体种类 + 可读文本 + 元数据」。
 *
 * <p>Hook 对图片、语音、文件等消息的 {@code content} 可能是空串，也可能是一段 XML。
 * 这里做的是尽力而为的提取：<b>解析不出元数据时退化为占位文本，绝不因为格式陌生而丢消息</b>。
 * 渲染出来的文本会直接进对话内容（管理端列表可见），也会原样进入模型上下文，所以要求
 * 一眼能看懂「他发的是什么、关键信息是什么」。</p>
 *
 * <p>三类特殊消息在 {@link #parse} 之外单独处理：</p>
 * <ul>
 *   <li><b>引用消息</b>（type=49，appmsg 子类型 57，带 {@code <refermsg>}）用
 *       {@link #parseQuote} 拆成「本次说的话 + 被引用的原文」，它属于文本消息而不是媒体消息；</li>
 *   <li><b>图片</b>（type=3）额外从 {@code <img>} 节点取 {@code md5} 与 {@code aeskey}，
 *       供后续定位本地 {@code .dat} 并解密成可识别的图片；</li>
 *   <li><b>appmsg</b>（type=49）按子类型再分派：链接、文件、合并转发、小程序、视频号、转账、红包……</li>
 * </ul>
 *
 * <p>各类型的渲染规则见 {@code docs/messages.md}。</p>
 */
public final class MediaMessageParser {
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_VOICE = 34;
    public static final int TYPE_CARD = 42;
    public static final int TYPE_VIDEO = 43;
    public static final int TYPE_STICKER = 47;
    public static final int TYPE_LOCATION = 48;
    public static final int TYPE_APP = 49;
    public static final int TYPE_CALL = 50;
    /** 微信内部操作通知（{@code <op>} / lastMessage），只会来自系统账号。 */
    public static final int TYPE_OP = 51;
    public static final int TYPE_CHANNEL = 62;
    public static final int TYPE_SYSTEM = 10000;
    public static final int TYPE_SYSTEM_2 = 10002;

    /** appmsg 子类型（{@code <appmsg><type>}）。 */
    public static final int APP_MUSIC = 3;
    public static final int APP_VIDEO = 4;
    public static final int APP_LINK = 5;
    public static final int APP_FILE = 6;
    public static final int APP_RECORD = 19;
    public static final int APP_MINI_PROGRAM = 33;
    public static final int APP_MINI_PROGRAM_2 = 36;
    public static final int APP_CHANNEL = 51;
    public static final int APP_QUOTE = 57;
    public static final int APP_LIVE = 63;
    public static final int APP_TRANSFER = 2000;
    public static final int APP_RED_PACKET = 2001;

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DES = Pattern.compile("<des>(.*?)</des>", Pattern.DOTALL);
    private static final Pattern FILENAME = Pattern.compile("<filename>(.*?)</filename>", Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("<url>(.*?)</url>", Pattern.DOTALL);
    private static final Pattern TOTAL_LEN = Pattern.compile("<totallen>(\\d+)</totallen>");
    private static final Pattern VOICE_LENGTH = Pattern.compile("voicelength=\"(\\d+)\"");
    private static final Pattern FILE_EXT = Pattern.compile("<fileext>(.*?)</fileext>", Pattern.DOTALL);
    /** 同 {@link #IMG_MD5}：必须挡住 {@code cdnthumblength} 这类同后缀属性。 */
    private static final Pattern IMG_LENGTH = Pattern.compile("(?<![A-Za-z0-9_])length=\"(\\d+)\"");
    /** 视频时长（秒），{@code <videomsg length="12">}。 */
    private static final Pattern VIDEO_LENGTH = Pattern.compile("(?<![A-Za-z0-9_])length=\"(\\d+)\"");
    /**
     * 图片属性必须带前界断言：微信报文里同时有 {@code cdnthumbmd5} / {@code cdnthumbaeskey}，
     * 不加 {@code (?<![A-Za-z0-9_])} 会先把这两个的后半截当成本体 {@code md5} / {@code aeskey} 抓出来。
     */
    private static final Pattern IMG_MD5 = Pattern.compile("(?<![A-Za-z0-9_])md5=\"([0-9a-fA-F]{32})\"");
    private static final Pattern IMG_AESKEY = Pattern.compile("(?<![A-Za-z0-9_])aeskey=\"([0-9a-fA-F]{16,64})\"");
    private static final Pattern IMG_THUMB_MD5 = Pattern.compile("cdnthumbmd5=\"([0-9a-fA-F]{32})\"");
    private static final Pattern IMG_THUMB_AESKEY = Pattern.compile("cdnthumbaeskey=\"([0-9a-fA-F]{16,64})\"");
    private static final Pattern IMG_FILEID = Pattern.compile("(?<![A-Za-z0-9_])fileid=\"([0-9a-fA-F]{32})\"");

    private static final Pattern REFERMSG = Pattern.compile("<refermsg>(.*?)</refermsg>", Pattern.DOTALL);
    private static final Pattern REFER_CONTENT = Pattern.compile("<content>(.*?)</content>", Pattern.DOTALL);
    private static final Pattern REFER_TYPE = Pattern.compile("<type>(\\d+)</type>");
    private static final Pattern REFER_CREATETIME = Pattern.compile("<createtime>(\\d+)</createtime>");
    private static final Pattern DISPLAY_NAME = Pattern.compile("<displayname>(.*?)</displayname>", Pattern.DOTALL);
    private static final Pattern CHAT_USER = Pattern.compile("<chatusr>(.*?)</chatusr>", Pattern.DOTALL);

    /** 系统消息正文里常见的一串 wxid / 数字，对人没有信息量。 */
    private static final Pattern ID_ONLY = Pattern.compile("[\\w@.\\-\\s]+");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    private MediaMessageParser() {}

    /**
     * @param md5        图片原图 md5
     * @param aeskey     原图 V2 格式的解密密钥（16 字节 hex）
     * @param thumbMd5   缩略图 md5：本机 {@code Img/} 目录里的文件名有时用的是它，不是原图 md5
     * @param thumbAeskey 缩略图密钥
     */
    public record Parsed(MediaKind kind, String placeholder, String name, Long size, String url,
                         String md5, String aeskey, String thumbMd5, String thumbAeskey) {

        public Parsed(MediaKind kind, String placeholder, String name, Long size, String url,
                      String md5, String aeskey) {
            this(kind, placeholder, name, size, url, md5, aeskey, null, null);
        }

        /** 本地文件名可能用到的所有 md5：原图优先，其次缩略图。 */
        public java.util.List<String> md5Candidates() {
            java.util.List<String> values = new java.util.ArrayList<>(2);
            if (md5 != null && !md5.isBlank()) {
                values.add(md5);
            }
            if (thumbMd5 != null && !thumbMd5.isBlank() && !values.contains(thumbMd5)) {
                values.add(thumbMd5);
            }
            return values;
        }
    }

    /**
     * 引用消息：{@code text} 是本次说的话，其余字段是被引用的那条消息。
     *
     * @param quoteEpochSeconds 被引用消息的发送时间（秒），定位本地图片文件时用
     * @param media             被引用消息本身是图片/语音等时的解析结果；文字引用为 {@code null}
     */
    public record Quoted(String text, String quoteAuthor, String quoteText,
                         Long quoteEpochSeconds, Parsed media) {

        public Quoted(String text, String quoteAuthor, String quoteText) {
            this(text, quoteAuthor, quoteText, null, null);
        }

        /** 被引用的是一条图片：可以顺着 md5 去本地找 {@code .dat}，让模型真的看到画面。 */
        public boolean hasQuotedImage() {
            return media != null && media.kind() == MediaKind.IMAGE;
        }
    }

    public static boolean isText(int type) {
        return type == TYPE_TEXT;
    }

    /** 只判断归类，不渲染文本；{@link #parse} 内部也用它，保证两边口径一致。 */
    public static MediaKind kindOf(int type, String rawContent) {
        return parse(type, rawContent).kind();
    }

    /**
     * 解析任意非文本消息。
     *
     * @param type       Hook 报文里的 {@code type}
     * @param rawContent Hook 报文里的 {@code content}（可能是空串、纯文本或 XML）
     */
    public static Parsed parse(int type, String rawContent) {
        String raw = rawContent == null ? "" : rawContent;
        return switch (type) {
            case TYPE_IMAGE -> image(raw);
            case TYPE_VOICE -> voice(raw);
            case TYPE_VIDEO -> video(raw);
            case TYPE_STICKER -> simple(MediaKind.STICKER, "[表情]", raw);
            case TYPE_CARD -> card(raw);
            case TYPE_LOCATION -> location(raw);
            case TYPE_CALL -> simple(MediaKind.CALL, "[通话]", raw);
            // type=51 是微信自己的 op 通知（例如 lastMessage 游标），正文只有 JSON，对人没有信息量
            case TYPE_OP -> simple(MediaKind.SYSTEM, "[系统消息]", raw);
            case TYPE_CHANNEL -> channel(raw);
            case TYPE_APP -> app(raw);
            case TYPE_SYSTEM, TYPE_SYSTEM_2 -> system(raw);
            default -> unknown(type, raw);
        };
    }

    // ------------------------------------------------------------------ 各类型渲染

    private static Parsed image(String raw) {
        Long size = number(TOTAL_LEN, raw);
        if (size == null) {
            size = number(IMG_LENGTH, raw);
        }
        return new Parsed(MediaKind.IMAGE, "[图片]", null, size, sanitizeUrl(first(URL, raw)),
                blankToNull(first(IMG_MD5, raw)), blankToNull(first(IMG_AESKEY, raw)),
                blankToNull(first(IMG_THUMB_MD5, raw)), blankToNull(first(IMG_THUMB_AESKEY, raw)));
    }

    /**
     * 语音：时长给模型看，{@code md5} 用来在本地找语音文件做转写（报文里有就带出来）。
     */
    private static Parsed voice(String raw) {
        Long millis = number(VOICE_LENGTH, raw);
        String text = millis == null || millis <= 0
                ? "[语音]"
                : "[语音 " + Math.max(1, Math.round(millis / 1000.0)) + "″]";
        Long size = number(IMG_LENGTH, raw);
        return new Parsed(MediaKind.VOICE, text, null, size, null, blankToNull(first(IMG_MD5, raw)), null);
    }

    private static Parsed video(String raw) {
        Long seconds = number(VIDEO_LENGTH, raw);
        String text = seconds == null || seconds <= 0 ? "[视频]" : "[视频 " + seconds + "″]";
        return new Parsed(MediaKind.VIDEO, text, null, number(TOTAL_LEN, raw),
                sanitizeUrl(first(URL, raw)), null, null);
    }

    /** 名片（type=42）：只有昵称可读。 */
    private static Parsed card(String raw) {
        String nickname = attr(raw, "nickname");
        String alias = attr(raw, "alias");
        String text = join("[名片]", nickname.isBlank() ? alias : nickname);
        return new Parsed(MediaKind.CARD, text, blankToNull(nickname), null, null, null, null);
    }

    /** 位置（type=48）：{@code poiname} 是地点名，{@code label} 是详细地址。 */
    private static Parsed location(String raw) {
        String name = attr(raw, "poiname");
        String label = attr(raw, "label");
        String text = join("[位置]", name, name.equals(label) ? "" : label);
        return new Parsed(MediaKind.LOCATION, text, blankToNull(name), null, null, null, null);
    }

    /** 视频号动态（type=62）：标题/描述有就带上，没有就只说是一条视频号内容。 */
    private static Parsed channel(String raw) {
        String title = firstNonBlank(first(TITLE, raw), first(DES, raw), attr(raw, "nickname"));
        return new Parsed(MediaKind.CHANNEL, join("[视频号]", title), blankToNull(title), null,
                sanitizeUrl(first(URL, raw)), null, null);
    }

    /**
     * appmsg（type=49）：先看子类型，再按子类型决定归类与文案。
     *
     * <p>注意「链接卡片」也带 {@code <appattach>} 节点（字段全空），早先按「有 appattach 就算文件」
     * 判断，会把小红书这类链接当成文件，标题里塞满分享文案。</p>
     */
    private static Parsed app(String raw) {
        int sub = intValue(REFER_TYPE, raw, 0);
        // 标题/描述都截断：分享文案可能很长，占位文本要一眼能看完
        String title = clip(first(TITLE, raw), 120);
        String des = clip(first(DES, raw), 200);
        String url = sanitizeUrl(first(URL, raw));
        String fileName = first(FILENAME, raw);
        Long total = number(TOTAL_LEN, raw);

        MediaKind kind = appKind(sub, raw);
        String text = switch (kind) {
            case FILE -> join("[文件]", firstNonBlank(fileName, title, "未知文件"), formatSize(total));
            case LINK -> switch (sub) {
                case APP_MUSIC -> join("[音乐]", firstNonBlank(title, url));
                case APP_VIDEO -> join("[视频]", firstNonBlank(title, url));
                case APP_LIVE -> join("[直播]", firstNonBlank(title, url));
                default -> title.isBlank()
                        ? join("[链接]", url)
                        : block("[链接] " + title, des, clip(url, 200));
            };
            case MINIAPP -> join("[小程序]", firstNonBlank(title, attr(raw, "sourcedisplayname")));
            case RECORD -> join("[聊天记录]", title, des);
            case CHANNEL -> join("[视频号]", title, des);
            case TRANSFER -> join("[转账]", title, des);
            case REDPACKET -> join("[红包]", title, des);
            default -> join("[卡片]", title, des);
        };
        String name = firstNonBlank(fileName, title, des);
        Long size = kind == MediaKind.FILE && total != null && total > 0 ? total : null;
        return new Parsed(kind, text, blankToNull(clip(name, 255)), size, url, null, null);
    }

    /**
     * appmsg 子类型 → 归类。
     *
     * <p>子类型缺失（老版本或转发场景）时退回「看内容特征」：有真实文件字段才算文件，
     * 有 url 算链接，都没有就是未知卡片。</p>
     */
    private static MediaKind appKind(int sub, String raw) {
        return switch (sub) {
            case APP_FILE -> MediaKind.FILE;
            case APP_MUSIC, APP_VIDEO, APP_LINK, APP_LIVE -> MediaKind.LINK;
            case APP_RECORD -> MediaKind.RECORD;
            case APP_MINI_PROGRAM, APP_MINI_PROGRAM_2 -> MediaKind.MINIAPP;
            case APP_CHANNEL -> MediaKind.CHANNEL;
            case APP_TRANSFER -> MediaKind.TRANSFER;
            case APP_RED_PACKET -> MediaKind.REDPACKET;
            case APP_QUOTE -> MediaKind.OTHER;
            default -> {
                if (hasRealFile(raw)) {
                    yield MediaKind.FILE;
                }
                yield first(URL, raw).isBlank() ? MediaKind.OTHER : MediaKind.LINK;
            }
        };
    }

    /** 真文件才有的字段：链接卡片的 {@code <appattach>} 是空壳，{@code totallen} 为 0。 */
    private static boolean hasRealFile(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (!first(FILENAME, raw).isBlank() || !first(FILE_EXT, raw).isBlank()) {
            return true;
        }
        Long total = number(TOTAL_LEN, raw);
        return total != null && total > 0;
    }

    /**
     * 系统消息（type=10000/10002）。
     *
     * <p>群里「拍一拍 / 撤回 / 入群」这类消息的正文常常只是一串 wxid 与数字，
     * 原样丢给模型只是噪音，这里识别出来就只保留「系统消息」这个事实。</p>
     */
    private static Parsed system(String raw) {
        String plain = stripTags(raw).replaceAll("\\s+", " ").trim();
        boolean idOnly = !plain.isBlank() && !HAS_CJK.matcher(plain).find() && ID_ONLY.matcher(plain).matches();
        String text = plain.isBlank() || idOnly ? "[系统消息]" : "[系统消息] " + clip(plain, 60);
        return new Parsed(MediaKind.SYSTEM, text, null, null, null, null, null);
    }

    /** 没见过的 type：能抽出标题/描述就用，抽不出来也要留下类型号，方便排查。 */
    private static Parsed unknown(int type, String raw) {
        String title = firstNonBlank(first(TITLE, raw), first(DES, raw), first(FILENAME, raw));
        String text = title.isBlank()
                ? "[消息 type=" + type + "]"
                : "[消息 type=" + type + "] " + clip(title, 60);
        return new Parsed(MediaKind.OTHER, text, blankToNull(clip(title, 255)), null,
                sanitizeUrl(first(URL, raw)), null, null);
    }

    private static Parsed simple(MediaKind kind, String text, String raw) {
        return new Parsed(kind, text, null, null, sanitizeUrl(first(URL, raw)), null, null);
    }

    // ------------------------------------------------------------------ 引用消息

    /**
     * 识别引用回复：type=49 且带 {@code <refermsg>}，并且本次确实带了文字。
     *
     * <p>微信把「引用一条消息再回复」编码成 appmsg（子类型 57）：外层 {@code <title>} 是用户这次
     * 说的话，{@code <refermsg>} 里装着被引用那条消息的发送者和原文。这类消息本质是文本，
     * 走 {@link #parse} 只会得到一句读不懂的 {@code [卡片]}，所以必须先走这里。</p>
     *
     * <p>被引用的对象不一定是文字：可能是图片、表情、文件、语音、链接，甚至是一条视频。
     * 所以这里读 {@code <refermsg>} 自己的 {@code <type>}，非文本一律复用 {@link #parse}
     * 生成可读文本（{@code [图片]}、{@code [语音 4″]}、{@code [文件] 报价单.pdf} …），
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
        int quotedType = quotedTypeOf(body);
        Parsed media = quotedType == TYPE_TEXT ? null : parse(quotedType, quoted);
        return new Quoted(
                text,
                blankToNull(clip(author, 180)),
                blankToNull(clip(quotedText(quotedType, media, quoted), 1000)),
                number(REFER_CREATETIME, body),
                media
        );
    }

    /** 被引用的内容按它自己的消息类型渲染：文字抽正文，其余走 {@link #parse} 的可读文本。 */
    private static String quotedText(int quotedType, Parsed media, String quotedContent) {
        if (quotedType == TYPE_TEXT) {
            return cleanQuoted(quotedContent);
        }
        if (quotedType == TYPE_APP && intValue(REFER_TYPE, quotedContent, 0) == APP_QUOTE) {
            // 引用里套引用：把内层再拆一层，否则模型只会看到 [卡片]
            Quoted inner = parseQuote(TYPE_APP, quotedContent);
            if (inner != null) {
                return "[引用 " + (inner.quoteAuthor() == null ? "某人" : inner.quoteAuthor())
                        + "：" + inner.quoteText() + "]\n" + inner.text();
            }
        }
        return media == null ? "" : media.placeholder();
    }

    /**
     * 取 {@code <refermsg>} 自己的 {@code <type>}。
     *
     * <p>不能只看 {@code <content>} 前后某一边：微信实际报文里 {@code <type>} 有时排在
     * {@code <content>} 之前（老版本），有时排在它之后（图片/语音引用常见，
     * 例如 {@code <content>…&lt;img…&gt;…</content><displayname>…</displayname><type>3</type>}）。
     * 只看前一边会退化成「文字」，于是把被引用图片的 XML 当成正文抽成 base64 垃圾串。
     * 所以这里先把整个 {@code <content>} 段摘掉，再在剩下的部分里找类型：
     * 既不受顺序影响，也不会被「被引用内容的 XML 没转义、里面自带 {@code <type>}」带偏。</p>
     */
    private static int quotedTypeOf(String referBody) {
        String outside = REFER_CONTENT.matcher(referBody).replaceAll(" ");
        return intValue(REFER_TYPE, outside, TYPE_TEXT);
    }

    // ------------------------------------------------------------------ 通用工具

    private static String join(String head, String... parts) {
        StringBuilder text = new StringBuilder(head);
        boolean first = true;
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            text.append(first ? " " : " · ").append(part.trim());
            first = false;
        }
        return text.toString();
    }

    private static String block(String head, String... lines) {
        StringBuilder text = new StringBuilder(head);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            text.append('\n').append(line.trim());
        }
        return text.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String formatSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 取 XML 属性值（带前界断言，避免 {@code md5} 命中 {@code cdnthumbmd5} 的后半截）。 */
    private static String attr(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "=\"([^\"]*)\"")
                .matcher(raw);
        return matcher.find() ? unescape(matcher.group(1)).trim() : "";
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
