package com.resolver.cache;

public class CacheConfig {
    private int maximumSize = 10000;
    private long minTtlSeconds = 60;
    private long maxTtlSeconds = 86400;
    private long negativeTtlSeconds = 30;

    private boolean staleWhileRevalidate = true;
    private long staleWindowSeconds = 300;

    private boolean prefetchEnabled = true;
    private int prefetchThresholdQueries = 5;
    private long prefetchWindowSeconds = 60;

    public CacheConfig() {}

    public int getMaximumSize() { return maximumSize; }
    public void setMaximumSize(int maximumSize) { this.maximumSize = maximumSize; }

    public long getMinTtlSeconds() { return minTtlSeconds; }
    public void setMinTtlSeconds(long minTtlSeconds) { this.minTtlSeconds = minTtlSeconds; }

    public long getMaxTtlSeconds() { return maxTtlSeconds; }
    public void setMaxTtlSeconds(long maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }

    public long getNegativeTtlSeconds() { return negativeTtlSeconds; }
    public void setNegativeTtlSeconds(long negativeTtlSeconds) { this.negativeTtlSeconds = negativeTtlSeconds; }

    public boolean isStaleWhileRevalidate() { return staleWhileRevalidate; }
    public void setStaleWhileRevalidate(boolean staleWhileRevalidate) { this.staleWhileRevalidate = staleWhileRevalidate; }

    public long getStaleWindowSeconds() { return staleWindowSeconds; }
    public void setStaleWindowSeconds(long staleWindowSeconds) { this.staleWindowSeconds = staleWindowSeconds; }

    public boolean isPrefetchEnabled() { return prefetchEnabled; }
    public void setPrefetchEnabled(boolean prefetchEnabled) { this.prefetchEnabled = prefetchEnabled; }

    public int getPrefetchThresholdQueries() { return prefetchThresholdQueries; }
    public void setPrefetchThresholdQueries(int prefetchThresholdQueries) { this.prefetchThresholdQueries = prefetchThresholdQueries; }

    public long getPrefetchWindowSeconds() { return prefetchWindowSeconds; }
    public void setPrefetchWindowSeconds(long prefetchWindowSeconds) { this.prefetchWindowSeconds = prefetchWindowSeconds; }
}
