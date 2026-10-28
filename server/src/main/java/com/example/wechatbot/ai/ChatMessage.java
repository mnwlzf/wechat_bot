package com.example.wechatbot.ai;

import java.util.List;

public record ChatMessage(String role, String content) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static List<ChatMessage> conversation(String systemPrompt, List<ChatMessage> messages) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(system(systemPrompt)),
                messages.stream()
        ).toList();
    }
}
