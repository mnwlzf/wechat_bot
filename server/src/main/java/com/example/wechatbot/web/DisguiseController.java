package com.example.wechatbot.web;

import com.example.wechatbot.disguise.DisguiseService;
import com.example.wechatbot.disguise.StyleNormalizer;
import com.example.wechatbot.domain.ChatType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/disguise")
public class DisguiseController {

    private final DisguiseService disguise;
    private final StyleNormalizer normalizer;

    public DisguiseController(DisguiseService disguise, StyleNormalizer normalizer) {
        this.disguise = disguise;
        this.normalizer = normalizer;
    }

    @GetMapping
    public DisguiseService.Status status() {
        return disguise.status();
    }

    @PostMapping("/preview")
    public Preview preview(@RequestBody PreviewRequest request) {
        String text = request.text() == null ? "" : request.text();
        String chatId = request.chatId() == null || request.chatId().isBlank()
                ? "preview" : request.chatId().trim();
        ChatType type = "GROUP".equalsIgnoreCase(request.chatType()) ? ChatType.GROUP : ChatType.PRIVATE;
        DisguiseService.Plan plan = disguise.preview(chatId, type, text, Instant.now());
        List<PreviewPart> parts = plan.parts().stream()
                .map(part -> new PreviewPart(part.text(), Math.max(0, (part.sendAt().toEpochMilli() - System.currentTimeMillis()) / 1000)))
                .toList();
        return new Preview(plan.decision(), plan.note(), plan.dropped(), normalizer.aiScore(text), parts);
    }

    public record PreviewRequest(String chatId, String chatType, String text) {}

    public record PreviewPart(String text, long delaySeconds) {}

    public record Preview(String decision, String note, boolean dropped, int aiScore, List<PreviewPart> parts) {}
}
