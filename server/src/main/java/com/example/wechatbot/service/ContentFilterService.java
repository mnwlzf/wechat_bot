package com.example.wechatbot.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 出站内容过滤：命中敏感词时按配置改写或拦截。
 */
@Service
public class ContentFilterService {
    public record Result(String content, boolean blocked, String note) {
        static Result pass(String content) {
            return new Result(content, false, null);
        }
    }

    private final RuntimeSettingsService settings;

    public ContentFilterService(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    public Result apply(String content) {
        if (content == null || content.isEmpty()) {
            return Result.pass(content);
        }
        String mode = settings.filterMode();
        if ("off".equals(mode)) {
            return Result.pass(content);
        }
        List<String> words = settings.bannedWords();
        if (words.isEmpty()) {
            return Result.pass(content);
        }

        String masked = content;
        boolean hit = false;
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            int index = masked.toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT));
            while (index >= 0) {
                hit = true;
                String replacement = "*".repeat(Math.max(1, word.length()));
                masked = masked.substring(0, index) + replacement + masked.substring(index + word.length());
                index = masked.toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT), index + replacement.length());
            }
        }
        if (!hit) {
            return Result.pass(content);
        }
        if ("block".equals(mode)) {
            return new Result(content, true, "命中敏感词，已拦截发送");
        }
        return new Result(masked, false, "命中敏感词，已改写");
    }
}
