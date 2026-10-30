package com.example.wechatbot.disguise;

import com.example.wechatbot.domain.ChatType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisguiseServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private DisguiseProperties baseProperties() {
        DisguiseProperties properties = new DisguiseProperties();
        properties.setQuietStart("00:00");
        properties.setQuietEnd("00:01");
        properties.setAwayBlocksEnabled(false);
        properties.setDropRate(0);
        properties.setScheduleEnabled(true);
        return properties;
    }

    private DisguiseService service(DisguiseProperties properties) {
        return new DisguiseService(
                properties,
                new DisguiseClock(properties),
                new StyleNormalizer(properties),
                new ReplyComposer(properties),
                new PacingModel(properties),
                new QuotaGuard(properties),
                new RepetitionGuard(properties));
    }

    private Instant at(int hour, int minute) {
        return ZonedDateTime.of(LocalDate.now(ZONE), LocalTime.of(hour, minute), ZONE).toInstant();
    }

    @Test
    void normalizerRemovesMarkdownAndAssistantTone() {
        DisguiseProperties properties = baseProperties();
        StyleNormalizer normalizer = new StyleNormalizer(properties);

        String cleaned = normalizer.normalize("**你好**！首先，我是一个AI助手，很高兴为您解答。希望这能帮到你。");

        assertFalse(cleaned.contains("**"), cleaned);
        assertFalse(cleaned.contains("首先"), cleaned);
        assertFalse(cleaned.contains("AI"), cleaned);
        assertFalse(cleaned.contains("希望"), cleaned);
        assertFalse(cleaned.contains("您"), cleaned);
        assertFalse(cleaned.endsWith("。"), cleaned);
        assertTrue(cleaned.startsWith("你好"), cleaned);
    }

    @Test
    void normalizerDropsListMarkersAndExtraEmoji() {
        DisguiseProperties properties = baseProperties();
        properties.setEmojiLimit(1);
        StyleNormalizer normalizer = new StyleNormalizer(properties);

        String cleaned = normalizer.normalize("- 第一点\n- 第二点 😀😀😀");

        assertFalse(cleaned.contains("- "), cleaned);
        assertTrue(cleaned.contains("第一点"), cleaned);
        assertEquals(1, cleaned.split("😀", -1).length - 1, cleaned);
    }

    @Test
    void composerSplitsLongReplyIntoBubbles() {
        DisguiseProperties properties = baseProperties();
        properties.setMaxPartChars(24);
        properties.setMaxParts(3);
        ReplyComposer composer = new ReplyComposer(properties);

        String text = "今天下午公司开会，估计六点才能结束。你先去吃吧，不用等我。我晚点过去找你们，顺便带点水果。";
        List<String> parts = composer.split(text);

        assertTrue(parts.size() >= 2, "应拆成多条: " + parts);
        assertTrue(parts.size() <= 3, "不应超过上限: " + parts);
        assertTrue(parts.stream().noneMatch(String::isBlank));
    }

    @Test
    void repliesAreDeferredDuringQuietHours() {
        DisguiseProperties properties = baseProperties();
        properties.setQuietStart("00:00");
        properties.setQuietEnd("07:00");
        DisguiseService service = service(properties);

        DisguiseService.Plan plan = service.plan("wxid_night", ChatType.PRIVATE, "在的，怎么了", at(2, 15));

        assertFalse(plan.dropped());
        Instant wake = ZonedDateTime.of(LocalDate.now(ZONE), LocalTime.of(7, 0), ZONE).toInstant();
        assertFalse(plan.firstSendAt().isBefore(wake), "凌晨回复应推迟到醒来之后: " + plan.firstSendAt());
    }

    @Test
    void quotaSpacesConsecutiveReplies() {
        DisguiseProperties properties = baseProperties();
        properties.setPerMinute(1);
        properties.setMinGapMs(0);
        properties.setPer10Minutes(100);
        properties.setPerHour(100);
        properties.setPerDay(100);
        properties.setGlobalPerHour(1000);
        properties.setGlobalPerDay(1000);
        properties.setBurstSize(50);
        DisguiseService service = service(properties);

        Instant now = at(15, 0);
        DisguiseService.Plan first = service.plan("wxid_rate", ChatType.PRIVATE, "好，我知道了", now);
        DisguiseService.Plan second = service.plan("wxid_rate", ChatType.PRIVATE, "那我晚点再过来", now.plusSeconds(1));

        assertFalse(first.dropped());
        assertFalse(second.dropped());
        assertTrue(second.firstSendAt().isAfter(first.firstSendAt().plusSeconds(30)),
                "同一会话第二次回复应被配额推后: " + first.firstSendAt() + " -> " + second.firstSendAt());
    }

    @Test
    void repeatedReplyIsDetected() {
        DisguiseProperties properties = baseProperties();
        DisguiseService service = service(properties);
        String text = "这个我帮你查一下，稍等两分钟";

        service.plan("wxid_repeat", ChatType.PRIVATE, text, at(16, 0));

        assertTrue(service.isRepetitive("wxid_repeat", text));
        assertFalse(service.isRepetitive("wxid_repeat", "晚上一起吃饭吗，我请客"));
    }

    @Test
    void previewDoesNotConsumeQuota() {
        DisguiseProperties properties = baseProperties();
        DisguiseService service = service(properties);
        Instant now = at(16, 30);

        DisguiseService.Plan preview = service.preview("wxid_preview", ChatType.PRIVATE, "测试一下预览效果", now);
        DisguiseService.Plan real = service.plan("wxid_preview", ChatType.PRIVATE, "测试一下预览效果", now);

        assertFalse(preview.dropped());
        assertTrue(real.firstSendAt().getEpochSecond() - now.getEpochSecond() < 120);
    }
}
