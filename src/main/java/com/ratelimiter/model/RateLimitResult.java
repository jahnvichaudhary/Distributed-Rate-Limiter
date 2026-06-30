package com.ratelimiter.model;

public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs) {}
