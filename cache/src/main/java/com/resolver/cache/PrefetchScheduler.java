package com.resolver.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PrefetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrefetchScheduler.class);

    private final DnsCache dnsCache;
    private final CacheConfig config;
    private final ScheduledExecutorService scheduler;

    public PrefetchScheduler(DnsCache dnsCache, CacheConfig config) {
        this.dnsCache = dnsCache;
        this.config = config;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dns-prefetch-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!config.isPrefetchEnabled()) {
            return;
        }

        scheduler.scheduleWithFixedDelay(this::runPrefetchPass,
            config.getPrefetchWindowSeconds(),
            config.getPrefetchWindowSeconds(),
            TimeUnit.SECONDS);

        log.info("Prefetch scheduler started, running every {} seconds", config.getPrefetchWindowSeconds());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void runPrefetchPass() {
        try {
            log.debug("Running prefetch pass");
            Map<CacheKey, CachedResponse> map = dnsCache.getInternalCache().synchronous().asMap();

            for (Map.Entry<CacheKey, CachedResponse> entry : map.entrySet()) {
                CacheKey key = entry.getKey();
                CachedResponse cached = entry.getValue();

                if (shouldPrefetch(cached)) {
                    log.debug("Prefetching hot domain: {}", key);
                    // AsyncLoadingCache doesn't have refresh() directly
                    // We need to use the synchronous view to refresh
                    dnsCache.getInternalCache().synchronous().refresh(key);
                }
            }
        } catch (Exception e) {
            log.error("Error during prefetch pass", e);
        }
    }

    boolean shouldPrefetch(CachedResponse cached) {
        if (cached.getQueryCount() < config.getPrefetchThresholdQueries()) {
            return false;
        }

        long remaining = cached.getRemainingTtl();
        long original = cached.getOriginalTtlSeconds();

        // If remaining TTL is less than 25% of original TTL
        // and it's not already expired (if expired, SWR handles it)
        return remaining > 0 && remaining < (original * 0.25);
    }
}
