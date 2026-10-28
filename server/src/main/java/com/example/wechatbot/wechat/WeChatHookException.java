package com.example.wechatbot.wechat;

public class WeChatHookException extends RuntimeException {
    public WeChatHookException(String message) {
        super(message);
    }

    public WeChatHookException(String message, Throwable cause) {
        super(message, cause);
    }
}
