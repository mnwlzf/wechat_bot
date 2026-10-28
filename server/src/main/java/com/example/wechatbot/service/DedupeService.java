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
public class DedupeService {
    private static final Logger log = LoggerFactory.getLogger(DedupeService.class);
    private static final Duration TTL = Duration.ofMinutes(30);
    private final StringRedisTemplate redis;
    private final Map<String, Instant> localFallback = new ConcurrentHashMap<>();

    public DedupeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean firstSeen(String eventId) {
        String key = "wechat:event:" + eventId;
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, "1", TTL);
            return Boolean.TRUE.equals(first);
        } catch (Exception ex) {
            log.warn("Redis unavailable for dedupe; using local fallback: {}", ex.getMessage());
            Instant now = Instant.now();
            localFallback.entrySet().removeIf(entry -> entry.getValue().plus(TTL).isBefore(now));
            return localFallback.putIfAbsent(eventId, now) == null;
        }
    }
}
