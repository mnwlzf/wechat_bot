package com.example.wechatbot.service;

import com.example.wechatbot.domain.MessageDirection;
import com.example.wechatbot.domain.MessageStatus;
import com.example.wechatbot.repository.MessageLogRepository;
import com.example.wechatbot.web.dto.AdminDtos;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用量统计：按天聚合收发量、token 消耗与平均耗时，并按模型拆分。
 *
 * <p>数据全部来自 {@code message_log}，不额外打点，因此历史数据天然可用。</p>
 */
@Service
public class UsageService {
    private final MessageLogRepository messageRepository;
    private final RuntimeSettingsService settings;

    public UsageService(MessageLogRepository messageRepository, RuntimeSettingsService settings) {
        this.messageRepository = messageRepository;
        this.settings = settings;
    }

    public AdminDtos.UsageView summary(int days) {
        int span = Math.max(1, Math.min(days, 90));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(span - 1L);
        OffsetDateTime from = firstDay.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = OffsetDateTime.now();

        List<Object[]> rows = messageRepository.usageRows(from);

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        Map<String, long[]> byProvider = new LinkedHashMap<>();
        Set<Object> conversations = new LinkedHashSet<>();

        long inbound = 0;
        long outbound = 0;
        long replied = 0;
        long ignored = 0;
        long failed = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long latencySum = 0;
        long latencyCount = 0;

        for (Object[] row : rows) {
            OffsetDateTime createdAt = (OffsetDateTime) row[0];
            MessageDirection direction = (MessageDirection) row[1];
            MessageStatus status = (MessageStatus) row[2];
            long prompt = number(row[3]);
            long completion = number(row[4]);
            Long latency = row[5] == null ? null : ((Number) row[5]).longValue();

            promptTokens += prompt;
            completionTokens += completion;
            if (row[8] != null) {
                conversations.add(row[8]);
            }

            if (direction == MessageDirection.INBOUND) {
                inbound++;
            } else {
                outbound++;
                if (status == MessageStatus.SUCCESS) {
                    replied++;
                } else if (status == MessageStatus.IGNORED) {
                    ignored++;
                }
            }
            if (status == MessageStatus.FAILED) {
                failed++;
            }
            if (direction == MessageDirection.OUTBOUND && latency != null) {
                latencySum += latency;
                latencyCount++;
            }

            LocalDate day = createdAt.atZoneSameInstant(zone).toLocalDate();
            long[] daily = byDay.computeIfAbsent(day, key -> new long[4]);
            if (direction == MessageDirection.INBOUND) {
                daily[0]++;
            } else {
                daily[1]++;
            }
            daily[2] += prompt;
            daily[3] += completion;

            if (direction == MessageDirection.OUTBOUND) {
                String provider = row[6] == null ? "关键词规则" : String.valueOf(row[6]);
                String model = row[7] == null ? "-" : String.valueOf(row[7]);
                long[] stats = byProvider.computeIfAbsent(provider + "\u0000" + model, key -> new long[5]);
                stats[0]++;
                stats[1] += prompt;
                stats[2] += completion;
                if (latency != null) {
                    stats[3] += latency;
                    stats[4]++;
                }
            }
        }

        AdminDtos.UsageTotals totals = new AdminDtos.UsageTotals(
                inbound, outbound, replied, ignored, failed,
                promptTokens, completionTokens,
                latencyCount == 0 ? 0L : Math.round((double) latencySum / latencyCount),
                conversations.size());

        return new AdminDtos.UsageView(
                span, from, to, totals,
                estimatedCost(promptTokens, completionTokens),
                "CNY",
                daily(span, firstDay, byDay),
                providers(byProvider));
    }

    private List<AdminDtos.UsageDaily> daily(int span, LocalDate firstDay, Map<LocalDate, long[]> byDay) {
        List<AdminDtos.UsageDaily> series = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate day = firstDay.plusDays(i);
            long[] values = byDay.getOrDefault(day, new long[4]);
            series.add(new AdminDtos.UsageDaily(day.toString(), values[0], values[1], values[2], values[3]));
        }
        return series;
    }

    private List<AdminDtos.UsageProvider> providers(Map<String, long[]> byProvider) {
        List<AdminDtos.UsageProvider> list = new ArrayList<>(byProvider.size());
        byProvider.forEach((key, stats) -> {
            String[] parts = key.split("\u0000", 2);
            long avg = stats[4] == 0 ? 0L : Math.round((double) stats[3] / stats[4]);
            list.add(new AdminDtos.UsageProvider(parts[0], parts[1], stats[0], stats[1], stats[2], avg));
        });
        list.sort((left, right) -> Long.compare(right.replies(), left.replies()));
        return list;
    }

    private double estimatedCost(long promptTokens, long completionTokens) {
        double promptPrice = settings.promptPricePerK();
        double completionPrice = settings.completionPricePerK();
        if (promptPrice <= 0 && completionPrice <= 0) {
            return 0d;
        }
        double cost = promptTokens / 1000d * promptPrice + completionTokens / 1000d * completionPrice;
        return BigDecimal.valueOf(cost).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static long number(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
