package com.resolver.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import java.util.concurrent.TimeUnit;

public class DynamicTtlExpiry implements Expiry<CacheKey, CachedResponse> {

    private final CacheConfig config;

    public DynamicTtlExpiry(CacheConfig config) {
        this.config = config;
    }

    @Override
    public long expireAfterCreate(CacheKey key, CachedResponse value, long currentTime) {
        return calculateDuration(value);
    }

    @Override
    public long expireAfterUpdate(CacheKey key, CachedResponse value, long currentTime, long currentDuration) {
        return calculateDuration(value);
    }

    @Override
    public long expireAfterRead(CacheKey key, CachedResponse value, long currentTime, long currentDuration) {
        return currentDuration; // Reading doesn't change expiry
    }

    private long calculateDuration(CachedResponse value) {
        long effectiveTtl = value.getOriginalTtlSeconds();

        if (value.isNegative()) {
            effectiveTtl = config.getNegativeTtlSeconds();
        } else {
            // Clamping
            effectiveTtl = Math.max(config.getMinTtlSeconds(), effectiveTtl);
            effectiveTtl = Math.min(config.getMaxTtlSeconds(), effectiveTtl);
        }

        // Add stale window
        long retentionTime = effectiveTtl;
        if (config.isStaleWhileRevalidate()) {
            retentionTime += config.getStaleWindowSeconds();
        }

        return TimeUnit.SECONDS.toNanos(retentionTime);
    }
}
