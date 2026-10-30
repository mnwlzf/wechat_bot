package com.example.wechatbot.service;

import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageServiceTest {
    private final MessageLogRepository repository = mock(MessageLogRepository.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final UsageService service = new UsageService(repository, settings);

    private Object[] row(OffsetDateTime at, MessageDirection direction, MessageStatus status,
                         Integer prompt, Integer completion, Long latency,
                         String provider, String model, UUID conversationId) {
        return new Object[]{at, direction, status, prompt, completion, latency, provider, model, conversationId};
    }

    @Test
    void aggregatesTotalsDailySeriesAndCost() {
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime today = LocalDate.now(zone).atTime(10, 0).atZone(zone).toOffsetDateTime();
        OffsetDateTime yesterday = today.minusDays(1);
        UUID conversationId = UUID.randomUUID();

        when(repository.usageRows(any())).thenReturn(List.of(
                row(yesterday, MessageDirection.INBOUND, MessageStatus.SUCCESS, null, null, null,
                        null, null, conversationId),
                row(yesterday, MessageDirection.OUTBOUND, MessageStatus.SUCCESS, 600, 300, 1200L,
                        "deepseek", "deepseek-chat", conversationId),
                row(today, MessageDirection.INBOUND, MessageStatus.SUCCESS, null, null, null,
                        null, null, conversationId),
                row(today, MessageDirection.OUTBOUND, MessageStatus.SUCCESS, 400, 200, 1500L,
                        "deepseek", "deepseek-chat", UUID.randomUUID()),
                row(today, MessageDirection.OUTBOUND, MessageStatus.IGNORED, 0, 0, null,
                        "关键词规则", "auto-reply", conversationId),
                row(today, MessageDirection.INBOUND, MessageStatus.FAILED, null, null, null,
                        null, null, conversationId)));
        when(settings.promptPricePerK()).thenReturn(0.001d);
        when(settings.completionPricePerK()).thenReturn(0.002d);

        AdminDtos.UsageView view = service.summary(3);

        assertThat(view.days()).isEqualTo(3);
        assertThat(view.totals().inbound()).isEqualTo(3);
        assertThat(view.totals().outbound()).isEqualTo(3);
        assertThat(view.totals().replied()).isEqualTo(2);
        assertThat(view.totals().ignored()).isEqualTo(1);
        assertThat(view.totals().failed()).isEqualTo(1);
        assertThat(view.totals().promptTokens()).isEqualTo(1000);
        assertThat(view.totals().completionTokens()).isEqualTo(500);
        assertThat(view.totals().avgLatencyMs()).isEqualTo(1350);
        assertThat(view.totals().activeConversations()).isEqualTo(2);
        // 1000/1000*0.001 + 500/1000*0.002
        assertThat(view.estimatedCost()).isEqualTo(0.002d);

        assertThat(view.daily()).hasSize(3);
        assertThat(view.daily().get(2).date()).isEqualTo(LocalDate.now(zone).toString());
        assertThat(view.daily().get(2).inbound()).isEqualTo(2);
        assertThat(view.daily().get(2).outbound()).isEqualTo(2);
        assertThat(view.daily().get(2).promptTokens()).isEqualTo(400);
        assertThat(view.daily().get(0).inbound()).isZero();

        assertThat(view.providers()).hasSize(2);
        AdminDtos.UsageProvider first = view.providers().getFirst();
        assertThat(first.provider()).isEqualTo("deepseek");
        assertThat(first.replies()).isEqualTo(2);
        assertThat(first.promptTokens()).isEqualTo(1000);
        assertThat(first.avgLatencyMs()).isEqualTo(1350);
        assertThat(view.providers().get(1).provider()).isEqualTo("关键词规则");
    }

    @Test
    void clampsDayRangeAndSkipsCostWhenPricingMissing() {
        when(repository.usageRows(any())).thenReturn(List.of());

        AdminDtos.UsageView view = service.summary(365);

        assertThat(view.days()).isEqualTo(90);
        assertThat(view.estimatedCost()).isZero();
        assertThat(view.daily()).hasSize(90);
        assertThat(view.providers()).isEmpty();
        assertThat(view.totals().avgLatencyMs()).isZero();
    }
}
