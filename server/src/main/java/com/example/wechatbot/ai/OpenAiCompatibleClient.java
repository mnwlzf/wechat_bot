package com.example.wechatbot.ai;

import com.example.wechatbot.domain.ModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient {
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LlmReply complete(ModelProvider provider, String apiKey, List<ChatMessage> messages,
                             double temperature, int maxTokens) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new LlmException("模型 Base URL 未配置");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("模型 API Key 未配置");
        }

        String baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(15, provider.getTimeoutSeconds())));
        requestFactory.setReadTimeout(Duration.ofSeconds(provider.getTimeoutSeconds()));
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        long start = System.nanoTime();
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
            return parse(provider, raw, latency);
        } catch (RestClientResponseException ex) {
            String detail = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (detail.length() > 500) {
                detail = detail.substring(0, 500);
            }
            throw new LlmException("模型接口返回 " + ex.getStatusCode().value() + ": " + detail, ex);
        } catch (RestClientException ex) {
            throw new LlmException("模型接口调用失败: " + ex.getMessage(), ex);
        }
    }

    private LlmReply parse(ModelProvider provider, String raw, long latency) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull() || contentNode.asText().isBlank()) {
                throw new LlmException("模型返回为空: " + abbreviate(raw));
            }
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
            return new LlmReply(
                    provider.getId(),
                    provider.getName(),
                    provider.getModel(),
                    contentNode.asText().trim(),
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

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
