package com.example.wechatbot.wechat;

import com.example.wechatbot.service.RuntimeSettingsService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话准入策略：决定一条微信消息「能不能自动回复」。
 *
 * <p>规则参考 WeChatAgent 的白名单逻辑，按会话类型分别限制：</p>
 * <ul>
 *   <li><b>群聊</b>：{@code groupsEnabled} 打开，且群 ID 必须在群聊白名单里。
 *       群聊不做「全量接收」——陌生群一旦被拉进去，自动回复会立刻变成骚扰。</li>
 *   <li><b>私聊</b>：白名单非空时只回名单里的；白名单为空时，看「全量接收私聊」开关。</li>
 *   <li><b>公众号 / 机器人 / 系统会话</b>：无论怎么配都不回复（见 {@link ChatIdentity}）。</li>
 * </ul>
 *
 * <p>策略每次调用都读运行时配置，因此在管理端保存后立即生效，不需要重启。</p>
 */
@Component
public class ChatAccessPolicy {

    private final RuntimeSettingsService settings;

    public ChatAccessPolicy(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    /** 一条消息的准入结果；{@code reason} 会写进回调诊断。 */
    public record Decision(boolean allowed, String reason, ChatIdentity.Kind kind) {
        static Decision allow(ChatIdentity.Kind kind) {
            return new Decision(true, "", kind);
        }

        static Decision deny(ChatIdentity.Kind kind, String reason) {
            return new Decision(false, reason, kind);
        }
    }

    public Decision decide(String chatId) {
        ChatIdentity.Kind kind = ChatIdentity.kindOf(chatId);
        return switch (kind) {
            case GROUP -> decideGroup(chatId);
            case PRIVATE -> decidePrivate(chatId);
            case OFFICIAL, SYSTEM -> Decision.deny(kind, ChatIdentity.rejectReason(chatId));
        };
    }

    private Decision decideGroup(String chatId) {
        if (!settings.groupsEnabled()) {
            return Decision.deny(ChatIdentity.Kind.GROUP, "群聊消息未启用");
        }
        List<String> allowed = settings.allowedGroupChats();
        if (!allowed.contains(chatId)) {
            return Decision.deny(ChatIdentity.Kind.GROUP, "群聊不在白名单");
        }
        return Decision.allow(ChatIdentity.Kind.GROUP);
    }

    private Decision decidePrivate(String chatId) {
        List<String> allowed = settings.allowedPrivateChats();
        if (!allowed.isEmpty()) {
            return allowed.contains(chatId)
                    ? Decision.allow(ChatIdentity.Kind.PRIVATE)
                    : Decision.deny(ChatIdentity.Kind.PRIVATE, "会话不在私聊白名单");
        }
        if (!settings.acceptAll()) {
            return Decision.deny(ChatIdentity.Kind.PRIVATE, "未配置私聊白名单且未开启全量接收");
        }
        return Decision.allow(ChatIdentity.Kind.PRIVATE);
    }
}
