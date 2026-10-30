package com.example.wechatbot.ai;

import com.example.wechatbot.config.AppProperties;
import com.example.wechatbot.domain.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 决定一条消息要不要联网，并把搜索结果整理成一段系统提示。
 *
 * <p>搜索失败一律降级为“不联网”，绝不影响正常回复。</p>
 */
@Component
public class SearchAugmenter {
    private static final Logger log = LoggerFactory.getLogger(SearchAugmenter.class);

    /** 纯寒暄/语气词，不值得为它花钱搜索。 */
    private static final Set<String> SMALL_TALK = Set.of(
            "你好", "您好", "在吗", "在么", "在不在", "哈喽", "嗨", "hi", "hello", "ok", "okay",
            "好的", "好", "嗯", "嗯嗯", "哦", "谢谢", "多谢", "拜拜", "晚安", "早安", "早", "哈哈",
            "哈哈哈", "收到", "明白", "行", "可以", "没事", "没关系"
    );

    /** 命中即联网：强时效词。 */
    private static final List<String> STRONG = List.of(
            "最新", "新闻", "热搜", "头条", "天气", "气温", "降雨", "台风", "汇率", "股价", "股价",
            "比分", "赛程", "赛果", "上映", "票房", "发布会", "实时", "查一下", "帮我查", "搜一下",
            "搜搜", "百度一下", "多少钱", "价格", "报价", "限行", "油价"
    );

    /** 命中且带问号才联网：弱时效词，避免把日常闲聊都送去搜索。 */
    private static final List<String> WEAK = List.of(
            "今天", "昨天", "明天", "最近", "现在", "当前", "什么时候", "哪一年", "哪天",
            "谁是", "是谁", "多少", "怎么样", "如何", "为什么", "什么", "哪个", "介绍", "了解"
    );

    private final LlmGateway gateway;
    private final AppProperties properties;

    public SearchAugmenter(LlmGateway gateway, AppProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    /** 返回可直接塞进 system 消息的搜索上下文；不需要或失败时返回 {@code null}。 */
    public String buildContext(BotConfig bot, String userText) {
        if (bot == null || userText == null || userText.isBlank()) {
            return null;
        }
        AppProperties.Search config = properties.getLlm().getSearch();
        AppProperties.Search.Mode mode = config.resolvedMode();
        if (mode == AppProperties.Search.Mode.OFF) {
            return null;
        }
        if (mode == AppProperties.Search.Mode.HEURISTIC && !shouldSearch(userText)) {
            return null;
        }
        try {
            DeepSeekSearchClient.SearchOutcome outcome = gateway.search(bot, query(userText), userText);
            if (outcome.results().isEmpty()) {
                log.info("联网搜索无结果: {}", abbreviate(userText));
                return null;
            }
            log.info("联网搜索命中 {} 条，providerKey={}，耗时 {}ms",
                    outcome.results().size(), outcome.usedKeyIndex() == 0 ? "主" : "备用", outcome.latencyMs());
            return render(outcome.results(), config);
        } catch (Exception ex) {
            log.warn("联网搜索失败，降级为不联网回复: {}", ex.getMessage());
            return null;
        }
    }

    boolean shouldSearch(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() < 3) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (SMALL_TALK.contains(lower)) {
            return false;
        }
        boolean question = text.contains("?") || text.contains("？");
        for (String word : STRONG) {
            if (lower.contains(word)) {
                return true;
            }
        }
        if (question) {
            for (String word : WEAK) {
                if (lower.contains(word)) {
                    return true;
                }
            }
            return text.length() >= 8;
        }
        return text.matches(".*20\\d{2}年?.*") && text.length() >= 6;
    }

    private String query(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 128 ? single : single.substring(0, 128);
    }

    private String render(List<DeepSeekSearchClient.SearchResult> results, AppProperties.Search config) {
        StringBuilder builder = new StringBuilder();
        builder.append("【联网搜索结果】以下是刚检索到的网页资料，可能不完整或过时，请结合时间判断：\n");
        int limit = Math.min(results.size(), Math.max(1, config.getMaxResults()));
        for (int index = 0; index < limit; index++) {
            DeepSeekSearchClient.SearchResult item = results.get(index);
            builder.append(index + 1).append(". ");
            builder.append(item.title() == null || item.title().isBlank() ? "(无标题)" : item.title());
            if (item.siteName() != null && !item.siteName().isBlank()) {
                builder.append(" — ").append(item.siteName());
            }
            if (item.publishedAt() != null && !item.publishedAt().isBlank()) {
                builder.append("（").append(item.publishedAt()).append("）");
            }
            builder.append('\n');
            String excerpt = item.bestExcerpt();
            if (excerpt != null && !excerpt.isBlank()) {
                String single = excerpt.replaceAll("\\s+", " ").trim();
                int max = Math.max(100, config.getMaxContentChars());
                builder.append("   ").append(single.length() <= max ? single : single.substring(0, max) + "…").append('\n');
            }
            if (item.url() != null && !item.url().isBlank()) {
                builder.append("   来源：").append(item.url()).append('\n');
            }
            if (builder.length() > 2600) {
                break;
            }
        }
        builder.append("要求：涉及实时信息时以上述结果为准；结果里没有的事实不要编造；");
        builder.append("回答要口语化、简短，不要把链接原样发给对方，除非对方明确要链接。");
        return builder.toString();
    }

    private static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 40 ? single.substring(0, 40) + "…" : single;
    }
}



