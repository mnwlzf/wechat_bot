package com.example.wechatbot.ai;

import java.util.List;

/**
 * 一条对话消息。{@code imageDataUrl} 非空时表示这条消息带图（{@code data:image/jpeg;base64,...}），
 * 请求体里会被编码成 OpenAI 兼容的多段 content（text + image_url）。
 */
public record ChatMessage(String role, String content, String imageDataUrl) {
    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** 带图片的用户消息；模型不支持图片时由调用方降级为 {@link #user(String)}。 */
    public static ChatMessage userWithImage(String content, String imageDataUrl) {
        return new ChatMessage("user", content, imageDataUrl);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public boolean hasImage() {
        return imageDataUrl != null && !imageDataUrl.isBlank();
    }

    public ChatMessage withoutImage() {
        return hasImage() ? new ChatMessage(role, content) : this;
    }

    public static List<ChatMessage> conversation(String systemPrompt, List<ChatMessage> messages) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(system(systemPrompt)),
                messages.stream()
        ).toList();
    }
}
