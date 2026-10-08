package com.resolver.upstream;

/**
 * Configuration for an upstream DNS-over-HTTPS (DoH) provider.
 *
 * Holds the URL and timeout settings for a single upstream resolver.
 * Example providers:
 *   - Cloudflare: https://1.1.1.1/dns-query
 *   - Google:     https://8.8.8.8/dns-query
 */
public class UpstreamConfig {

    private final String url;
    private final int timeoutMs;

    /**
     * Creates a new upstream configuration.
     *
     * @param url       The DoH endpoint URL (e.g. "https://1.1.1.1/dns-query")
     * @param timeoutMs Timeout in milliseconds for upstream requests
     */
    public UpstreamConfig(String url, int timeoutMs) {
        this.url = url;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Creates a config with default timeout of 5 seconds.
     *
     * @param url The DoH endpoint URL
     */
    public UpstreamConfig(String url) {
        this(url, 5000);
    }

    public String getUrl() {
        return url;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Preset config for Cloudflare DNS (1.1.1.1).
     */
    public static UpstreamConfig cloudflare() {
        return new UpstreamConfig("https://1.1.1.1/dns-query");
    }

    /**
     * Preset config for Google DNS (8.8.8.8).
     */
    public static UpstreamConfig google() {
        return new UpstreamConfig("https://8.8.8.8/dns-query");
    }
}
