package com.example.wechatbot.service;

import com.example.wechatbot.config.ReplyProperties;

import java.util.concurrent.Executors;

/**
 * 测试用：跳过防连发聚合，收到就同步生成，方便断言「到底有没有生成」。
 */
class InlineReplyDispatcher extends ReplyDispatcher {

    InlineReplyDispatcher() {
        super(new ReplyProperties(), Executors.newSingleThreadScheduledExecutor(), Executors.newSingleThreadExecutor());
    }

    @Override
    public void submit(String chatId, Runnable generate, Runnable superseded) {
        // 不模拟合并：只关心 InboxService 有没有把生成请求交出去
        generate.run();
    }
}
