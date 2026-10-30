package com.example.wechatbot.web;

import com.example.wechatbot.ai.LlmGateway;
import com.example.wechatbot.service.SearchSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/search-settings")
public class SearchSettingsController {
    private final SearchSettingsService settings;
    private final LlmGateway llmGateway;

    public SearchSettingsController(SearchSettingsService settings, LlmGateway llmGateway) {
        this.settings = settings;
        this.llmGateway = llmGateway;
    }

    @GetMapping
    public SearchSettingsService.SearchSettingsView view() {
        return settings.view();
    }

    @PutMapping
    public SearchSettingsService.SearchSettingsView update(@Valid @RequestBody UpdateRequest request) {
        return settings.update(new SearchSettingsService.SearchSettingsUpdate(
                request.enabled(),
                request.mode(),
                request.baseUrl(),
                request.apiKey(),
                request.backupApiKey(),
                request.clearApiKey(),
                request.clearBackupApiKey(),
                request.count(),
                request.needContent(),
                request.maxResults(),
                request.maxContentChars(),
                request.timeoutSeconds()
        ));
    }

    @PostMapping("/test")
    public TestResponse test(@RequestBody TestRequest request) {
        String query = request.query() == null || request.query().isBlank()
                ? "今天有哪些重要新闻" : request.query().trim();
        try {
            var outcome = llmGateway.testSearch(query);
            return new TestResponse(true, "联网搜索调用成功", outcome.results().size(), outcome.latencyMs());
        } catch (Exception ex) {
            Throwable current = ex;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            return new TestResponse(false, current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(), 0, 0);
        }
    }

    public record UpdateRequest(
            boolean enabled,
            @NotBlank String mode,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 5000) String apiKey,
            @Size(max = 5000) String backupApiKey,
            boolean clearApiKey,
            boolean clearBackupApiKey,
            @Min(1) @Max(20) int count,
            boolean needContent,
            @Min(1) @Max(10) int maxResults,
            @Min(100) @Max(10000) int maxContentChars,
            @Min(5) @Max(120) int timeoutSeconds
    ) {}

    public record TestRequest(String query) {}

    public record TestResponse(boolean success, String message, int resultCount, long latencyMs) {}
}
