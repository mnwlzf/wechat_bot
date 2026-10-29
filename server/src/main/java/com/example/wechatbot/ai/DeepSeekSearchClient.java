package com.example.wechatbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepSeek 官方联网搜索：{@code POST {baseUrl}/v1/tools/search}。
 *
 * <p>与对话共用同一组主/备 Key：401/402/403 直接切备用 Key，
 * 429/5xx 与连接失败先重试一次再切换。</p>
 */
@Component
public class DeepSeekSearchClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchClient.class);

    private static final int MAX_ATTEMPTS = 2;
    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 425, 429, 500, 502, 503, 504);
    private static final Set<Integer> KEY_REJECTED = Set.of(401, 402, 403);

    private final ObjectMapper objectMapper;

    public DeepSeekSearchClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SearchOutcome search(String baseUrl, List<String> apiKeys, String query, String originalQuestion,
                                int count, boolean needContent, int timeoutSeconds) {
        if (query == null || query.isBlank()) {
            throw new LlmException("搜索关键词为空");
        }
        List<String> keys = apiKeys == null ? List.of() : apiKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .map(String::trim)
                .toList();
        if (keys.isEmpty()) {
            throw new LlmException("搜索需要模型 API Key，但当前未配置");
        }

        String endpoint = searchEndpoint(baseUrl);
        RestClient client = buildClient(timeoutSeconds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", truncate(query.trim(), 128));
        body.put("count", Math.max(1, Math.min(50, count)));
        body.put("need_content", needContent);
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            body.put("ori_question", truncate(originalQuestion.trim(), 128));
        }

        List<String> errors = new ArrayList<>();
        long start = System.nanoTime();
        for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
            String apiKey = keys.get(keyIndex);
            String keyLabel = keyIndex == 0 ? "主 Key" : "备用 Key " + keyIndex;
            for (int attempt = 1; ; attempt++) {
                try {
                    String raw = client.post()
                            .uri(endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .retrieve()
                            .body(String.class);
                    long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    if (keyIndex > 0) {
                        log.info("联网搜索主 Key 不可用，已切换到{}", keyLabel);
                    }
                    return new SearchOutcome(parse(raw), keyIndex, latency);
                } catch (RestClientResponseException ex) {
                    int status = ex.getStatusCode().value();
                    if (KEY_REJECTED.contains(status)) {
                        errors.add(keyLabel + " 被拒绝(" + status + ")");
                        log.warn("联网搜索{}被拒绝 {}: {}", keyLabel, status, abbreviate(responseBody(ex)));
                        break;
                    }
                    if (attempt < MAX_ATTEMPTS && RETRYABLE.contains(status)) {
                        log.warn("联网搜索 {} {}，重试一次（{}）", status, ex.getStatusText(), keyLabel);
                        backoff();
                        continue;
                    }
                    errors.add(keyLabel + " 返回 " + status + ": " + abbreviate(responseBody(ex)));
                    break;
                } catch (ResourceAccessException ex) {
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("联网搜索连接失败，重试一次（{}）: {}", keyLabel, ex.getMessage());
                        backoff();
                        continue;
                    }
                    errors.add(keyLabel + " 连接失败: " + ex.getMessage());
                    break;
                } catch (RestClientException ex) {
                    throw new LlmException("联网搜索调用失败: " + ex.getMessage(), ex);
                }
            }
        }
        throw new LlmException("联网搜索主/备 Key 均失败: " + String.join(" | ", errors));
    }

    static String searchEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        try {
            URI uri = URI.create(base);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return base + "/v1/tools/search";
            }
        } catch (IllegalArgumentException ex) {
            return base + "/v1/tools/search";
        }
        return base + "/v1/tools/search";
    }

    private List<SearchResult> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return List.of();
            }
            List<SearchResult> parsed = new ArrayList<>();
            for (JsonNode item : results) {
                parsed.add(new SearchResult(
                        text(item, "title"),
                        text(item, "url"),
                        text(item, "snippet"),
                        text(item, "summary"),
                        text(item, "content"),
                        text(item, "site_name"),
                        text(item, "date"),
                        item.path("authority").asInt(-1),
                        item.path("rerank_score").asDouble(0)
                ));
            }
            return parsed;
        } catch (Exception ex) {
            throw new LlmException("解析搜索结果失败: " + ex.getMessage(), ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private RestClient buildClient(int timeoutSeconds) {
        int timeout = Math.max(5, Math.min(120, timeoutSeconds));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeout)));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        return RestClient.builder().requestFactory(factory).build();
    }

    private String responseBody(RestClientResponseException ex) {
        String detail = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
        return detail == null || detail.isBlank() ? ex.getStatusText() : detail;
    }

    private void backoff() {
        try {
            Thread.sleep(300L + ThreadLocalRandom.current().nextLong(0, 500));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ');
        return single.length() > 300 ? single.substring(0, 300) : single;
    }

    public record SearchResult(
            String title,
            String url,
            String snippet,
            String summary,
            String content,
            String siteName,
            String publishedAt,
            int authority,
            double rerankScore
    ) {
        public String bestExcerpt() {
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            if (snippet != null && !snippet.isBlank()) {
                return snippet;
            }
            return content == null ? "" : content;
        }
    }

    public record SearchOutcome(List<SearchResult> results, int usedKeyIndex, long latencyMs) {}
}
