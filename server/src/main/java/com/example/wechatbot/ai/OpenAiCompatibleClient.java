package com.example.wechatbot.ai;

import com.example.wechatbot.domain.ModelProvider;
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
 * OpenAI Chat Completions 兼容客户端。
 *
 * <p>兼容点：Base URL 自动补 {@code /v1}、429/5xx 单次重试、
 * 兼容 {@code content} 为字符串或分段数组的返回格式。</p>
 */
@Component
public class OpenAiCompatibleClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private static final int MAX_ATTEMPTS = 2;
    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 425, 429, 500, 502, 503, 504);
    /** 这些状态码说明该 Key 本身不可用（无效/欠费/无权限），直接切换备用 Key。 */
    private static final Set<Integer> KEY_REJECTED = Set.of(401, 402, 403);

    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LlmReply complete(ModelProvider provider, String apiKey, List<ChatMessage> messages,
                             double temperature, int maxTokens) {
        return complete(provider, List.of(apiKey == null ? "" : apiKey), messages, temperature, maxTokens);
    }

    /**
     * 依次尝试传入的 Key（主 Key → 备用 Key）。
     *
     * <p>401/402/403 视为该 Key 不可用，立即切下一个 Key；
     * 429/5xx/连接失败在同一個 Key 上重试一次后再切换。</p>
     */
    public LlmReply complete(ModelProvider provider, List<String> apiKeys, List<ChatMessage> messages,
                             double temperature, int maxTokens) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new LlmException("模型 Base URL 未配置");
        }
        if (provider.getModel() == null || provider.getModel().isBlank()) {
            throw new LlmException("模型名称未配置");
        }
        List<String> keys = apiKeys == null ? List.of() : apiKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .map(String::trim)
                .toList();
        if (keys.isEmpty()) {
            throw new LlmException("模型 API Key 未配置");
        }

        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (baseUrl.isBlank()) {
            throw new LlmException("模型 Base URL 无效");
        }
        RestClient client = buildClient(provider);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        List<String> errors = new ArrayList<>();
        long start = System.nanoTime();
        for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
            String apiKey = keys.get(keyIndex);
            String keyLabel = keyLabel(keyIndex);
            for (int attempt = 1; ; attempt++) {
                try {
                    String raw = client.post()
                            .uri(baseUrl + "/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .retrieve()
                            .body(String.class);
                    long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    if (keyIndex > 0) {
                        log.info("模型主 Key 不可用，已切换到{}（provider={}）", keyLabel, provider.getName());
                    }
                    return parse(provider, raw, latency);
                } catch (RestClientResponseException ex) {
                    int status = ex.getStatusCode().value();
                    String detail = abbreviate(responseBody(ex));
                    if (KEY_REJECTED.contains(status)) {
                        errors.add(keyLabel + " 被拒绝(" + status + ")");
                        log.warn("模型{}被拒绝 {}（provider={}）: {}", keyLabel, status, provider.getName(), detail);
                        break;
                    }
                    if (attempt < MAX_ATTEMPTS && RETRYABLE.contains(status)) {
                        log.warn("模型接口 {} {}，重试一次（provider={}, {}）",
                                status, ex.getStatusText(), provider.getName(), keyLabel);
                        backoff();
                        continue;
                    }
                    errors.add(keyLabel + " 返回 " + status + ": " + detail);
                    break;
                } catch (ResourceAccessException ex) {
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("模型接口连接失败，重试一次（provider={}, {}）: {}",
                                provider.getName(), keyLabel, ex.getMessage());
                        backoff();
                        continue;
                    }
                    errors.add(keyLabel + " 连接失败: " + ex.getMessage());
                    break;
                } catch (RestClientException ex) {
                    throw new LlmException("模型接口调用失败: " + ex.getMessage(), ex);
                }
            }
        }
        throw new LlmException("主/备 Key 均调用失败: " + String.join(" | ", errors));
    }

    private static String keyLabel(int index) {
        return index == 0 ? "主 Key" : "备用 Key " + index;
    }

    /**
     * 把用户填的 Base URL 规范成 {@code .../v1} 形式。
     * 已经带路径的（如 {@code /compatible-mode/v1}）保持原样，只去掉尾部斜杠。
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return value + "/v1";
            }
        } catch (IllegalArgumentException ex) {
            return value;
        }
        return value;
    }

    private RestClient buildClient(ModelProvider provider) {
        int timeoutSeconds = Math.max(5, Math.min(300, provider.getTimeoutSeconds()));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    private LlmReply parse(ModelProvider provider, String raw, long latency) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String message = error.path("message").asText(error.toString());
                throw new LlmException("模型返回错误: " + abbreviate(message));
            }
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentText(contentNode);
            if (content.isBlank()) {
                throw new LlmException("模型返回为空: " + abbreviate(raw));
            }
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
            return new LlmReply(
                    provider.getId(),
                    provider.getName(),
                    provider.getModel(),
                    content.trim(),
                    promptTokens,
                    completionTokens,
                    latency
            );
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmException("无法解析模型响应: " + abbreviate(raw), ex);
        }
    }

    /** 兼容 content 为字符串、数组（多段 text）两种返回。 */
    private String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (!content.isArray()) {
            return content.asText("");
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if (part.isTextual()) {
                builder.append(part.asText());
            } else if (part.has("text")) {
                builder.append(part.path("text").asText(""));
            }
        }
        return builder.toString();
    }

    private String responseBody(RestClientResponseException ex) {
        String detail = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
        return detail == null || detail.isBlank() ? ex.getStatusText() : detail;
    }

    private void backoff() {
        long millis = 300L + ThreadLocalRandom.current().nextLong(0, 500);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ');
        return single.length() > 500 ? single.substring(0, 500) : single;
    }
}
