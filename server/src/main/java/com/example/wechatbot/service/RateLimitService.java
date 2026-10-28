package com.example.wechatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private final StringRedisTemplate redis;
    private final Map<String, LocalCounter> localFallback = new ConcurrentHashMap<>();

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean allow(String botId, String chatId, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "wechat:rate:" + botId + ":" + chatId + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(2));
            }
            return count != null && count <= limitPerMinute;
        } catch (Exception ex) {
            log.warn("Redis unavailable for rate limit; using local fallback: {}", ex.getMessage());
            LocalCounter counter = localFallback.compute(key, (ignored, current) ->
                    current == null || current.expiresAt().isBefore(Instant.now())
                            ? new LocalCounter(1, Instant.now().plus(Duration.ofMinutes(2)))
                            : new LocalCounter(current.count() + 1, current.expiresAt()));
            localFallback.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            return counter.count() <= limitPerMinute;
        }
    }

    private record LocalCounter(int count, Instant expiresAt) {}
}
