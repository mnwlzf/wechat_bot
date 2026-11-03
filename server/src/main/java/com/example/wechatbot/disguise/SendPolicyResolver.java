package com.example.wechatbot.disguise;

/**
 * 按会话解析「生效的发信策略」。
 *
 * <p>默认实现直接返回全局配置（{@code app.disguise.*}），
 * 数据库实现会依次叠加「全局默认策略」和「该群自己的策略」。</p>
 */
@FunctionalInterface
public interface SendPolicyResolver {

    /** 返回该会话生效的策略快照；只读，调用方不要修改。 */
    DisguiseProperties forChat(String chatId);
}
