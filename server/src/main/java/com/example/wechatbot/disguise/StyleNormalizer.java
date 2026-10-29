package com.example.wechatbot.disguise;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 把"模型腔"洗成"微信腔"。
 *
 * <p>模型输出里有大量稳定但不像人的特征：Markdown 结构、分点罗列、
 * "首先/其次/总之"、句末句号、"作为AI"、以及对每个人都一样的客套结尾。
 * 这些既影响观感，也是最容易被内容侧规则命中的模板特征。</p>
 */
@Component
public class StyleNormalizer {

    private static final Pattern MULTI_BLANK = Pattern.compile("\n{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([，。！？；：、])");
    private static final Pattern TRAILING_PERIOD = Pattern.compile("[。.，,；;、]+$");

    private final DisguiseProperties properties;

    public StyleNormalizer(DisguiseProperties properties) {
        this.properties = properties;
    }

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[\\u200B-\\u200F\\uFEFF]", "");
        if (text.isBlank()) {
            return "";
        }
        text = stripMarkdown(text);
        if (properties.isStyleEnabled()) {
            text = stripAiPhrases(text);
            text = polish(text);
            text = limitEmoji(text, Math.max(0, properties.getEmojiLimit()));
        }
        return finish(text);
    }

    /** 粗略的"AI 味"打分，仅用于观测与调参，不参与发送决策。 */
    public int aiScore(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int score = 0;
        if (text.contains("**") || text.contains("##") || text.contains("```")) {
            score += 2;
        }
        if (text.matches("(?s).*^(\\s*[-*+]|\\s*\\d+[.)、])\\s+.*")) {
            score += 2;
        }
        for (String marker : new String[]{"首先", "其次", "最后", "总之", "综上", "作为AI", "作为 AI",
                "语言模型", "希望对你", "希望对您", "需要注意的是", "值得注意"}) {
            if (text.contains(marker)) {
                score++;
            }
        }
        if (text.length() > 180) {
            score++;
        }
        if (text.endsWith("。")) {
            score++;
        }
        return score;
    }

    private String stripMarkdown(String text) {
        String value = text;
        value = value.replaceAll("(?m)^\\s*```[\\w+.#-]*\\s*$", "");
        value = value.replace("```", "");
        value = value.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        value = value.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        value = value.replaceAll("__(.+?)__", "$1");
        value = value.replaceAll("~~(.+?)~~", "$1");
        value = value.replaceAll("(?<!\\*)\\*(?!\\s)([^*\\n]+?)\\*(?!\\*)", "$1");
        value = value.replaceAll("`([^`\\n]+)`", "$1");
        value = value.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        value = value.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1 $2");
        value = value.replaceAll("(?m)^\\s*>\\s?", "");
        value = value.replaceAll("(?m)^\\s*[-*+•]\\s+", "");
        value = value.replaceAll("(?m)^\\s*\\d{1,2}[.)、]\\s+", "");
        value = value.replaceAll("(?m)^\\s*([-*_])\\1{2,}\\s*$", "");
        value = value.replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "");
        return value;
    }

    private String stripAiPhrases(String text) {
        String value = text;
        value = value.replaceAll("(?i)作为(一个|一名)?\\s*(ai|人工智能|语言模型|大模型|智能助手|助手|机器人)[，,：:]?", "");
        value = value.replaceAll("(?i)(希望|但愿)(以上|这|这些|这个回答|这些内容)?(对你|对您)?(有所)?(帮助|用)[。！!]?", "");
        value = value.replaceAll("(?i)(希望|但愿)[^。！？\\n]{0,14}(帮到你|帮到您|对你有用|对您有用|有所帮助|有用)[。！!]?", "");
        value = value.replaceAll("(如果)?(你|您)?还有(其他|别的)?(问题|疑问)[，,]?(随时|欢迎|可以)(告诉我|提问|问我|联系我)[。！!]?", "");
        value = value.replaceAll("(总的来说|综上所述|总而言之|总之)[，,]?", "");
        value = value.replaceAll("(首先|其次|再者|另外|此外|最后)[，,]", "");
        value = value.replaceAll("(需要注意的是|值得注意的是|值得一提的是)[，,]?", "");
        value = value.replaceAll("以下(是|为)[^。\\n]{0,15}[：:]", "");
        value = value.replaceAll("根据(你|您)的(问题|描述|需求|情况)[，,]?", "");
        value = value.replaceAll("(我)?(很)?(乐意|高兴|愿意)(为你|为您)?(解答|回答|服务|帮助)[。！!]?", "");
        value = value.replaceAll("(?i)\\bas an ai\\b[^。.!?\\n]*[。.!?]?", "");
        value = value.replaceAll("(?i)\\blet me know if\\b[^。.!?\\n]*[。.!?]?", "");
        value = value.replaceAll("(?i)(我是|我只是)(一个|一名)?\\s*(智能助手|ai助手|人工智能|语言模型|大模型|机器人|助手|ai)[，,。]?", "");
        value = value.replace("您", "你");
        value = value.replace("——", "，").replace("—", "，").replace("--", "，");
        value = value.replace("...", "…");
        value = value.replaceAll("\"([^\"\\n]{1,60})\"", "“$1”");
        return value;
    }

    private String polish(String text) {
        String value = text;
        value = value.replaceAll("！{2,}", "！");
        value = value.replaceAll("？{2,}", "？");
        value = value.replaceAll("。{2,}", "。");
        value = value.replaceAll("，{2,}", "，");
        value = value.replaceAll("([，、；：])\\s*([。！？])", "$2");
        value = value.replaceAll("^[，、；：。！？\\s]+", "");
        value = value.replaceAll("\\s+([，。！？；：、])", "$1");
        value = value.replaceAll("[ \\t]{2,}", " ");
        return value;
    }

    private String finish(String text) {
        String value = text.replaceAll("\n{3,}", "\n\n");
        StringBuilder builder = new StringBuilder(value.length());
        for (String line : value.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() && (builder.isEmpty() || builder.charAt(builder.length() - 1) == '\n')) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }
        value = builder.toString().strip();
        value = MULTI_SPACE.matcher(value).replaceAll(" ");
        value = SPACE_BEFORE_PUNCT.matcher(value).replaceAll("$1");
        value = TRAILING_PERIOD.matcher(value).replaceAll("");
        return value.strip();
    }

    private String limitEmoji(String text, int limit) {
        StringBuilder out = new StringBuilder(text.length());
        int seen = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (!isEmoji(cp)) {
                out.appendCodePoint(cp);
                i += width;
                continue;
            }
            int end = i + width;
            while (end < text.length()) {
                int next = text.codePointAt(end);
                if (next == 0xFE0F || next == 0x200D || (next >= 0x1F3FB && next <= 0x1F3FF)) {
                    end += Character.charCount(next);
                } else {
                    break;
                }
            }
            if (seen < limit) {
                out.append(text, i, end);
                seen++;
            }
            i = end;
        }
        return out.toString();
    }

    private boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || cp == 0x2764;
    }
}
