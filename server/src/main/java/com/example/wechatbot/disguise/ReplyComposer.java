package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分条器：把一段完整回答切成 1~3 条口语气泡。
 *
 * <p>真人不会把 500 字塞进一条微信，也不会在一条消息里留段落空行。
 * 这里先按空行/换行分段（每段至少一条），段内再按句子边界打包，
 * 超过 {@code max-parts} 的尾部合并到最后一条。</p>
 */
@Component
public class ReplyComposer {

    private final DisguiseProperties properties;

    public ReplyComposer(DisguiseProperties properties) {
        this.properties = properties;
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String value = text.strip();
        if (!properties.isSplitEnabled()) {
            return List.of(value);
        }
        int maxPart = Math.max(20, properties.getMaxPartChars());

        List<String> paragraphs = new ArrayList<>();
        for (String line : value.split("\n+")) {
            String paragraph = line.strip();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        if (paragraphs.isEmpty()) {
            return List.of();
        }
        if (paragraphs.size() == 1 && value.length() <= maxPart) {
            return List.of(value);
        }

        List<String> parts = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= maxPart) {
                parts.add(paragraph);
            } else {
                parts.addAll(pack(paragraph, maxPart));
            }
        }
        parts.removeIf(String::isBlank);
        if (parts.isEmpty()) {
            return List.of();
        }
        return cap(parts);
    }

    /** 段内按句号/问号/感叹号/分号打包，超长句再在逗号处硬切。 */
    private List<String> pack(String text, int maxPart) {
        List<String> sentences = sentences(text);
        if (sentences.size() <= 1) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            boolean lastSentence = i == sentences.size() - 1;
            if (!current.isEmpty() && current.length() + sentence.length() > maxPart) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
            if (sentence.length() > maxPart * 2) {
                for (String chunk : hardWrap(sentence, maxPart)) {
                    if (!current.isEmpty()) {
                        parts.add(current.toString().strip());
                        current.setLength(0);
                    }
                    current.append(chunk);
                }
            } else {
                current.append(sentence);
            }
            if (lastSentence && !current.isEmpty()) {
                parts.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().strip());
        }
        return parts;
    }

    private List<String> cap(List<String> parts) {
        int maxParts = Math.max(1, properties.getMaxParts());
        if (parts.size() <= maxParts) {
            return List.copyOf(parts);
        }
        List<String> merged = new ArrayList<>(parts.subList(0, maxParts - 1));
        merged.add(String.join("", parts.subList(maxParts - 1, parts.size())));
        return List.copyOf(merged);
    }

    private List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            boolean boundary = ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';';
            if (boundary) {
                String sentence = current.toString().strip();
                if (!sentence.isEmpty()) {
                    result.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    /** 超长单句在逗号/顿号/空格处再切，避免出现整屏长文。 */
    private List<String> hardWrap(String sentence, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentence.length(); i++) {
            char ch = sentence.charAt(i);
            current.append(ch);
            boolean soft = ch == '，' || ch == '、' || ch == ' ';
            if (soft && current.length() >= limit) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }
}
