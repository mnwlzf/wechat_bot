package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复读检测。
 *
 * <p>两类风险：同一个聊天里反复说同一句话（"好的，还有什么可以帮您"），
 * 以及不同聊天收到高度相似的内容（群发/机器人特征）。用 3-gram Jaccard
 * 做近似判断，命中后交给编排层换一种说法重答。</p>
 */
@Component
public class RepetitionGuard {

    private static final long WINDOW_MS = 6 * 60 * 60 * 1000L;
    private static final int GLOBAL_LIMIT = 60;

    public record Entry(String text, long at, Set<String> shingles) {}

    private final DisguiseProperties properties;
    private final Map<String, Deque<Entry>> perChat = new ConcurrentHashMap<>();
    private final Deque<Entry> global = new ArrayDeque<>();

    public RepetitionGuard(DisguiseProperties properties) {
        this.properties = properties;
    }

    public boolean isRepetitive(String chatId, String text) {
        if (text == null || text.strip().length() < 6) {
            return false;
        }
        Set<String> shingles = shingles(text);
        if (shingles.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Entry> history = perChat.get(chatId == null ? "" : chatId);
        if (history != null) {
            int checked = 0;
            synchronized (history) {
                for (Entry entry : history) {
                    if (now - entry.at() > WINDOW_MS) {
                        continue;
                    }
                    if (++checked > Math.max(1, properties.getSimilarityHistory())) {
                        break;
                    }
                    if (jaccard(shingles, entry.shingles()) >= properties.getSimilarityThreshold()) {
                        return true;
                    }
                }
            }
        }
        synchronized (global) {
            int checked = 0;
            for (Entry entry : global) {
                if (now - entry.at() > 60 * 60 * 1000L) {
                    continue;
                }
                if (++checked > 40) {
                    break;
                }
                if (jaccard(shingles, entry.shingles()) >= properties.getCrossChatSimilarityThreshold()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void record(String chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Entry entry = new Entry(text, System.currentTimeMillis(), shingles(text));
        Deque<Entry> history = perChat.computeIfAbsent(chatId == null ? "" : chatId, key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(entry);
            while (history.size() > 40) {
                history.pollFirst();
            }
        }
        synchronized (global) {
            global.addLast(entry);
            while (global.size() > GLOBAL_LIMIT) {
                global.pollFirst();
            }
        }
    }

    public static double similarity(String a, String b) {
        return jaccard(shingles(a), shingles(b));
    }

    static Set<String> shingles(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
        if (normalized.isEmpty()) {
            return Set.of();
        }
        if (normalized.length() <= 3) {
            return Set.of(normalized);
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 3 <= normalized.length(); i++) {
            result.add(normalized.substring(i, i + 3));
        }
        return result;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int intersection = 0;
        for (String item : small) {
            if (large.contains(item)) {
                intersection++;
            }
        }
        return intersection / (double) (a.size() + b.size() - intersection);
    }
}
