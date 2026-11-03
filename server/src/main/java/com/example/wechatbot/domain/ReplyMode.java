package com.example.wechatbot.domain;

/**
 * 群聊回复策略的四种模式。
 *
 * <ul>
 *   <li>{@link #ALWAYS}：主动回复——群里每条消息都当话题参与；</li>
 *   <li>{@link #RANDOM}：随机回复——没被叫到时按概率插话，被叫到一定回；</li>
 *   <li>{@link #WAKE}：关键词唤醒——只有消息里出现唤醒词才回（默认行为）；</li>
 *   <li>{@link #HUMAN}：拟人回复——把「要不要回、回什么」都交给模型自己判断。</li>
 * </ul>
 */
public enum ReplyMode {
    ALWAYS,
    RANDOM,
    WAKE,
    HUMAN
}
