package com.example.wechatbot.web;

import com.example.wechatbot.disguise.DisguiseProperties;
import com.example.wechatbot.disguise.DisguiseScheduleService;
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
    private final DisguiseProperties properties;
    private final DisguiseScheduleService schedule;

    public DisguiseController(DisguiseService disguise, StyleNormalizer normalizer,
                              DisguiseProperties properties, DisguiseScheduleService schedule) {
        this.disguise = disguise;
        this.normalizer = normalizer;
        this.properties = properties;
        this.schedule = schedule;
    }

    @GetMapping
    public DisguiseService.Status status() {
        return disguise.status();
    }

    @GetMapping("/config")
    public PacingConfig config() {
        return new PacingConfig(
                properties.isEnabled(),
                properties.isScheduleEnabled(),
                properties.getZone(),
                properties.getQuietStart(),
                properties.getQuietEnd(),
                properties.getMaxDeferMinutes(),
                schedule.awayBlocksEnabled(),
                schedule.awayBlocks(),
                properties.getReadDelayMinMs(),
                properties.getReadDelayMaxMs(),
                properties.getThinkBaseMs(),
                properties.getCharsPerSecond(),
                properties.getTypingJitter(),
                properties.getMaxDelayMs(),
                properties.getLongPauseProbability(),
                properties.getLongPauseMaxMs(),
                properties.getAwayProbability(),
                properties.getAwayMinMs(),
                properties.getAwayMaxMs(),
                properties.getGroupDelayFactor(),
                properties.isSplitEnabled(),
                properties.getMaxParts(),
                properties.getMaxPartChars(),
                properties.getMaxReplyChars(),
                properties.getPartGapMinMs(),
                properties.getPartGapMaxMs(),
                properties.getPerMinute(),
                properties.getPer10Minutes(),
                properties.getPerHour(),
                properties.getPerDay(),
                properties.getGlobalPerHour(),
                properties.getGlobalPerDay(),
                properties.getMinGapMs(),
                properties.getBurstSize(),
                properties.getBurstCooldownMinMs(),
                properties.getBurstCooldownMaxMs()
        );
    }

    @PutMapping("/away-blocks")
    public PacingConfig updateAwayBlocks(@RequestBody AwayBlocksUpdate request) {
        schedule.updateAwayBlocks(request.enabled(), request.blocks());
        return config();
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

    public record PacingConfig(
            boolean enabled,
            boolean scheduleEnabled,
            String zone,
            String quietStart,
            String quietEnd,
            int maxDeferMinutes,
            boolean awayBlocksEnabled,
            List<String> awayBlocks,
            int readDelayMinMs,
            int readDelayMaxMs,
            int thinkBaseMs,
            double charsPerSecond,
            double typingJitter,
            int maxDelayMs,
            double longPauseProbability,
            int longPauseMaxMs,
            double awayProbability,
            int awayMinMs,
            int awayMaxMs,
            double groupDelayFactor,
            boolean splitEnabled,
            int maxParts,
            int maxPartChars,
            int maxReplyChars,
            int partGapMinMs,
            int partGapMaxMs,
            int perMinute,
            int per10Minutes,
            int perHour,
            int perDay,
            int globalPerHour,
            int globalPerDay,
            int minGapMs,
            int burstSize,
            int burstCooldownMinMs,
            int burstCooldownMaxMs
    ) {}

    public record AwayBlocksUpdate(boolean enabled, List<String> blocks) {}

    public record PreviewRequest(String chatId, String chatType, String text) {}

    public record PreviewPart(String text, long delaySeconds) {}

    public record Preview(String decision, String note, boolean dropped, int aiScore, List<PreviewPart> parts) {}
}


