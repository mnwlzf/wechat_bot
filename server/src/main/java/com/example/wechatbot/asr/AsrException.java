package com.example.wechatbot.asr;

/** ASR 调用失败：配置缺失、接口报错、音频格式不支持。 */
public class AsrException extends RuntimeException {
    public AsrException(String message) {
        super(message);
    }

    public AsrException(String message, Throwable cause) {
        super(message, cause);
    }
}
