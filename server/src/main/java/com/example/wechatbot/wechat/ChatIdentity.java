package com.example.wechatbot.wechat;

import java.util.Locale;
import java.util.Set;

/**
 * 会话身份识别：把微信里的各种会话 ID 归成「群聊 / 真人私聊 / 公众号 / 系统会话」。
 *
 * <p>微信的会话 ID 并不只有 {@code wxid_*} 和 {@code @chatroom} 两种，实测回调里还会出现：</p>
 * <ul>
 *   <li>{@code gh_xxxxxxxx} —— 公众号（服务号、订阅号），会主动推送图文消息</li>
 *   <li>{@code xxx@weclaw} —— 微信内置 AI 助手 / 机器人</li>
 *   <li>{@code xxx@openim}、{@code xxx@kf} —— 企业微信、微信客服</li>
 *   <li>{@code filehelper}、{@code weixin}、{@code service_officialaccounts} 等 —— 系统会话</li>
 * </ul>
 *
 * <p>这些会话都不是「人」：自动回复只会刷屏、浪费 token，还可能触发风控。
 * 因此无论白名单怎么配、是否开启全量接收，都不应该回复它们。</p>
 */
public final class ChatIdentity {

    /** 会话类别。 */
    public enum Kind {
        /** 群聊（{@code @chatroom}）。 */
        GROUP,
        /** 真人私聊。 */
        PRIVATE,
        /** 公众号、机器人、企业微信 / 客服等非真人账号。 */
        OFFICIAL,
        /** 微信内置系统会话（文件传输助手、微信团队等）。 */
        SYSTEM
    }

    /**
     * 微信内置系统 / 服务会话。
     *
     * <p>名单来自客户端 {@code BizServiceMuteWhitelist} 与常见内置账号；这些 ID 永远不会是好友。</p>
     */
    private static final Set<String> SYSTEM_ACCOUNTS = Set.of(
            "filehelper", "weixin", "fmessage", "floatbottle", "medianote", "newsapp",
            "feedsapp", "masssendapp", "blogapp", "voipapp", "voicevoipapp", "voiceinputapp",
            "googlecontact", "qqmail", "qmessage", "tmessage", "mphelper", "brandsessionholder",
            "notifymessage", "weibo", "qqsync", "lbsapp", "shakeapp", "linkedinplugin",
            "facebookapp", "qqfriend", "qqsafe", "weixinreminder", "exmail", "weixinlucky",
            "service_officialaccounts");

    /** 非真人账号的 ID 后缀：微信 AI 助手、企业微信 / 客服。 */
    private static final String[] NON_HUMAN_SUFFIXES = {"@weclaw", "@openim", "@kf"};

    /** 公众号 / 服务号 / 订阅号 ID 前缀。 */
    private static final String OFFICIAL_PREFIX = "gh_";

    /** 微信智能助手（小微 / AI 相关能力）使用的 wxid 前缀。 */
    private static final String INTELLIGENCE_PREFIX = "wxid_wi_";

    private ChatIdentity() {
    }

    /**
     * 判断会话 ID 属于哪一类。空值按系统会话处理（一律不回复）。
     */
    public static Kind kindOf(String chatId) {
        String id = chatId == null ? "" : chatId.trim();
        if (id.isEmpty()) {
            return Kind.SYSTEM;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.endsWith("@chatroom")) {
            return Kind.GROUP;
        }
        if (lower.startsWith(OFFICIAL_PREFIX) || lower.startsWith(INTELLIGENCE_PREFIX)) {
            return Kind.OFFICIAL;
        }
        for (String suffix : NON_HUMAN_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return Kind.OFFICIAL;
            }
        }
        if (lower.endsWith("_officialaccounts")) {
            return Kind.OFFICIAL;
        }
        if (SYSTEM_ACCOUNTS.contains(lower)) {
            return Kind.SYSTEM;
        }
        // 其余带 @ 的会话（@stranger、@im.chatroom 等）都不是个人好友，保守地按系统会话处理
        if (lower.indexOf('@') >= 0) {
            return Kind.SYSTEM;
        }
        return Kind.PRIVATE;
    }

    public static boolean isGroup(String chatId) {
        return kindOf(chatId) == Kind.GROUP;
    }

    /** 真人私聊：可以按白名单 / 全量接收决定是否回复。 */
    public static boolean isHumanPrivate(String chatId) {
        return kindOf(chatId) == Kind.PRIVATE;
    }

    /** 公众号 / 机器人 / 系统会话：永远不自动回复。 */
    public static boolean isNeverReply(String chatId) {
        Kind kind = kindOf(chatId);
        return kind == Kind.OFFICIAL || kind == Kind.SYSTEM;
    }

    /** 给管理端展示的中文标签。 */
    public static String labelOf(String chatId) {
        return switch (kindOf(chatId)) {
            case GROUP -> "群聊";
            case PRIVATE -> "私聊";
            case OFFICIAL -> "公众号 / 机器人";
            case SYSTEM -> "系统会话";
        };
    }

    /** 不回复的原因，直接写进回调诊断，方便排查“为什么没回”。 */
    public static String rejectReason(String chatId) {
        return switch (kindOf(chatId)) {
            case OFFICIAL -> "公众号 / 机器人 / 企业微信会话不自动回复";
            case SYSTEM -> "微信系统会话不自动回复";
            default -> "";
        };
    }
}
