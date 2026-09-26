package com.resolver.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resolver.pipeline.ResolutionPipeline;
import com.resolver.upstream.UpstreamResolver;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.DnsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class DnsCache implements ResolutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DnsCache.class);

    private final AsyncLoadingCache<CacheKey, CachedResponse> cache;
    private final UpstreamResolver upstreamResolver;
    private final CacheConfig config;
    private final ExecutorService backgroundExecutor;

    public DnsCache(UpstreamResolver upstreamResolver, CacheConfig config) {
        this.upstreamResolver = upstreamResolver;
        this.config = config;
        this.backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dns-cache-bg");
            t.setDaemon(true);
            return t;
        });

        this.cache = Caffeine.newBuilder()
            .maximumSize(config.getMaximumSize())
            .expireAfter(new DynamicTtlExpiry(config))
            .recordStats()
            .buildAsync((key, executor) -> fetchFromUpstream(key));
    }

    public AsyncLoadingCache<CacheKey, CachedResponse> getInternalCache() {
        return cache;
    }

    @Override
    public CompletableFuture<DnsResponse> resolve(DnsQuery query) {
        try {
            CacheKey key = CacheKey.fromQuery(query);
            int queryId = query.id();

            return cache.get(key).thenApply(cached -> {
                cached.incrementQueryCount();

                // Stale-While-Revalidate check
                if (cached.isExpired() && config.isStaleWhileRevalidate()) {
                    triggerAsyncRefresh(key, query);
                }

                return cached.getResponseWithAdjustedTtl(queryId);
            });
        } catch (IllegalArgumentException e) {
            log.warn("Invalid query received", e);
            CompletableFuture<DnsResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private void triggerAsyncRefresh(CacheKey key, DnsQuery originalQuery) {
        backgroundExecutor.submit(() -> {
            log.debug("Triggering background SWR refresh for {}", key);
            upstreamResolver.forward(originalQuery)
                .thenAccept(response -> {
                    long ttl = extractTtl(response);
                    boolean isNegative = isNegativeResponse(response);

                    CachedResponse newCached = new CachedResponse(response, ttl, isNegative);
                    cache.synchronous().put(key, newCached);
                    log.debug("Background SWR refresh completed for {}", key);
                })
                .exceptionally(ex -> {
                    log.warn("Background SWR refresh failed for {}", key, ex);
                    return null;
                });
        });
    }

    private CompletableFuture<CachedResponse> fetchFromUpstream(CacheKey key) {
        // Construct a synthetic query to forward
        // In real cases we construct exactly what's needed or pass along the query if we stored it,
        // but Caffeine's load method only gives the Key.
        // For simplicity, we assume we need to rebuild the query:
        // io.netty.handler.codec.dns.DefaultDnsQuery
        io.netty.handler.codec.dns.DefaultDnsQuery query = new io.netty.handler.codec.dns.DefaultDnsQuery(0); // ID 0 is ok for internal use
        query.addRecord(DnsSection.QUESTION, new io.netty.handler.codec.dns.DefaultDnsQuestion(
                key.normalizedDomainName(), key.recordType(), key.recordClass()));

        return upstreamResolver.forward(query)
            .thenApply(response -> {
                long ttl = extractTtl(response);
                boolean isNegative = isNegativeResponse(response);
                // The Cache needs to store the response, but it must assume ownership of the Netty ByteBuf buffers
                // We'll trust UpstreamResolver to return a response with retained byte buffers.
                return new CachedResponse(response, ttl, isNegative);
            });
    }

    private boolean isNegativeResponse(DnsResponse response) {
        return response.code() == DnsResponseCode.NXDOMAIN || response.code() == DnsResponseCode.SERVFAIL;
    }

    private long extractTtl(DnsResponse response) {
        if (isNegativeResponse(response)) {
            // Find SOA in authority for negative cache
            for (int i = 0; i < response.count(DnsSection.AUTHORITY); i++) {
                DnsRecord record = response.recordAt(DnsSection.AUTHORITY, i);
                if (record.type().intValue() == 6) { // SOA
                    // Real implementation needs to parse SOA bytes to get the min TTL (last 4 bytes)
                    // We'll rely on default negative TTL for simplicity here unless parsed.
                    return config.getNegativeTtlSeconds();
                }
            }
            return config.getNegativeTtlSeconds();
        }

        long minTtl = Long.MAX_VALUE;
        boolean found = false;

        for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
            DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
            minTtl = Math.min(minTtl, record.timeToLive());
            found = true;
        }

        if (!found) {
            return config.getMinTtlSeconds(); // fallback
        }

        return minTtl;
    }
}
