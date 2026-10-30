package com.example.wechatbot.disguise;

import com.example.wechatbot.common.ApiException;
import com.example.wechatbot.domain.AppSetting;
import com.example.wechatbot.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * “离开手机”离线块的运行时配置。
 *
 * <p>管理员在页面上手动维护每日时间段，写库后立即更新内存中的
 * {@link DisguiseProperties}，无需重启。最多 6 段，支持跨零点。</p>
 */
@Service
public class DisguiseScheduleService {
    private static final Logger log = LoggerFactory.getLogger(DisguiseScheduleService.class);

    static final String KEY_AWAY_BLOCKS_ENABLED = "disguise.awayBlocksEnabled";
    static final String KEY_AWAY_BLOCKS = "disguise.awayBlocks";
    private static final int MAX_BLOCKS = 6;
    private static final int MIN_DURATION_MINUTES = 5;
    private static final Pattern TIME_PATTERN = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");

    private final AppSettingRepository repository;
    private final DisguiseProperties properties;

    public DisguiseScheduleService(AppSettingRepository repository, DisguiseProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    public void reload() {
        try {
            repository.findById(KEY_AWAY_BLOCKS_ENABLED).ifPresent(setting ->
                    properties.setAwayBlocksEnabled(Boolean.parseBoolean(setting.getValue().trim())));
            repository.findById(KEY_AWAY_BLOCKS).ifPresent(setting -> {
                try {
                    properties.setAwayBlocks(normalize(splitBlocks(setting.getValue()), false));
                } catch (RuntimeException ex) {
                    log.warn("数据库中的离线块配置无效，继续使用环境变量默认值: {}", ex.getMessage());
                }
            });
            log.info("离线块配置已加载: enabled={} blocks={}",
                    properties.isAwayBlocksEnabled(), properties.getAwayBlocks());
        } catch (RuntimeException ex) {
            log.warn("加载离线块配置失败，使用环境变量默认值: {}", ex.getMessage());
        }
    }

    public boolean awayBlocksEnabled() {
        return properties.isAwayBlocksEnabled();
    }

    public List<String> awayBlocks() {
        return List.copyOf(properties.getAwayBlocks());
    }

    public void updateAwayBlocks(boolean enabled, List<String> blocks) {
        List<String> normalized = normalize(blocks, enabled);
        properties.setAwayBlocksEnabled(enabled);
        properties.setAwayBlocks(normalized);
        save(KEY_AWAY_BLOCKS_ENABLED, String.valueOf(enabled));
        save(KEY_AWAY_BLOCKS, String.join(",", normalized));
        log.info("离线块配置已更新: enabled={} blocks={}", enabled, normalized);
    }

    private List<String> normalize(List<String> blocks, boolean requireBlockWhenEnabled) {
        if (blocks == null) {
            blocks = List.of();
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw ApiException.badRequest("离线块最多配置 " + MAX_BLOCKS + " 段");
        }

        List<Range> ranges = new ArrayList<>();
        for (String raw : blocks) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ranges.add(parseRange(raw));
        }
        if (requireBlockWhenEnabled && ranges.isEmpty()) {
            throw ApiException.badRequest("开启离开手机后至少需要配置一个时间段");
        }

        ranges.sort(Comparator.comparingInt(Range::startMinute));
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                if (overlaps(ranges.get(i), ranges.get(j))) {
                    throw ApiException.badRequest("离线块时间不能重叠: "
                            + ranges.get(i).value() + " / " + ranges.get(j).value());
                }
            }
        }
        return ranges.stream().map(Range::value).toList();
    }

    private static List<String> splitBlocks(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }

    private static Range parseRange(String raw) {
        String value = raw.trim();
        String[] parts = value.split("-", 2);
        if (parts.length != 2) {
            throw ApiException.badRequest("离线块格式应为 HH:mm-HH:mm: " + value);
        }
        String startText = parts[0].trim();
        String endText = parts[1].trim();
        if (!TIME_PATTERN.matcher(startText).matches() || !TIME_PATTERN.matcher(endText).matches()) {
            throw ApiException.badRequest("离线块格式应为 HH:mm-HH:mm: " + value);
        }
        try {
            LocalTime start = LocalTime.parse(startText);
            LocalTime end = LocalTime.parse(endText);
            if (start.equals(end)) {
                throw ApiException.badRequest("离线块开始和结束时间不能相同: " + value);
            }
            int startMinute = start.getHour() * 60 + start.getMinute();
            int endMinute = end.getHour() * 60 + end.getMinute();
            int duration = endMinute > startMinute
                    ? endMinute - startMinute
                    : 24 * 60 - startMinute + endMinute;
            if (duration < MIN_DURATION_MINUTES) {
                throw ApiException.badRequest("离线块至少需要 " + MIN_DURATION_MINUTES + " 分钟: " + value);
            }
            return new Range(start.toString() + "-" + end.toString(), startMinute, endMinute);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("离线块格式应为 HH:mm-HH:mm: " + value);
        }
    }

    private static boolean overlaps(Range left, Range right) {
        for (int[] a : segments(left)) {
            for (int[] b : segments(right)) {
                if (Math.max(a[0], b[0]) < Math.min(a[1], b[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<int[]> segments(Range range) {
        if (range.endMinute() > range.startMinute()) {
            return List.of(new int[]{range.startMinute(), range.endMinute()});
        }
        List<int[]> result = new ArrayList<>(2);
        result.add(new int[]{range.startMinute(), 24 * 60});
        if (range.endMinute() > 0) {
            result.add(new int[]{0, range.endMinute()});
        }
        return result;
    }

    private void save(String key, String value) {
        try {
            repository.save(new AppSetting(key, value));
        } catch (RuntimeException ex) {
            log.error("离线块配置写库失败（内存已生效，重启会丢失）: {}={}", key, value, ex);
        }
    }

    private record Range(String value, int startMinute, int endMinute) {}
}
