package com.ratelimiter.service;

import com.ratelimiter.model.Policy;
import com.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Distributed rate limiter. All decisions are made by atomic Lua scripts
 * inside Redis, so multiple app instances stay correct under contention.
 */
@Service
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> slidingWindowScript;
    private final DefaultRedisScript<List> tokenBucketScript;

    @Value("${ratelimiter.default-capacity}")
    private long defaultCapacity;

    @Value("${ratelimiter.default-refill-per-second}")
    private double defaultRefill;

    @Value("${ratelimiter.default-window-seconds}")
    private long defaultWindowSeconds;

    public RateLimiterService(StringRedisTemplate redis,
                              DefaultRedisScript<List> slidingWindowScript,
                              DefaultRedisScript<List> tokenBucketScript) {
        this.redis = redis;
        this.slidingWindowScript = slidingWindowScript;
        this.tokenBucketScript = tokenBucketScript;
    }

    public RateLimitResult check(String clientId, Policy policy) {
        return switch (policy) {
            case SLIDING_WINDOW -> slidingWindow(clientId, defaultWindowSeconds * 1000, defaultCapacity);
            case TOKEN_BUCKET   -> tokenBucket(clientId, defaultCapacity, defaultRefill);
        };
    }

    @SuppressWarnings("unchecked")
    public RateLimitResult slidingWindow(String clientId, long windowMs, long limit) {
        String key = "rl:sw:" + clientId;
        long now = System.currentTimeMillis();
        List<Long> res = redis.execute(
                slidingWindowScript,
                Collections.singletonList(key),
                String.valueOf(windowMs),
                String.valueOf(limit),
                String.valueOf(now),
                UUID.randomUUID().toString()
        );
        return toResult(res);
    }

    @SuppressWarnings("unchecked")
    public RateLimitResult tokenBucket(String clientId, long capacity, double refillPerSecond) {
        String key = "rl:tb:" + clientId;
        long now = System.currentTimeMillis();
        List<Long> res = redis.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                String.valueOf(now),
                "1"
        );
        return toResult(res);
    }

    private RateLimitResult toResult(List<Long> res) {
        if (res == null || res.size() < 3) {
            // Fail-open if Redis returned something unexpected
            return new RateLimitResult(true, -1, 0);
        }
        boolean allowed = res.get(0) == 1L;
        long remaining = res.get(1);
        long retry = res.get(2);
        return new RateLimitResult(allowed, remaining, retry);
    }
}
