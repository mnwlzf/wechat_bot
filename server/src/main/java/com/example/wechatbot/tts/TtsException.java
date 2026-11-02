package com.example.wechatbot.tts;

/** 语音合成失败。 */
public class TtsException extends RuntimeException {

    public TtsException(String message) {
        super(message);
    }

    public TtsException(String message, Throwable cause) {
        super(message, cause);
    }
}
