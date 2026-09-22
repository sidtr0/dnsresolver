package com.resolver.pipeline;

import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for resolving DNS queries.
 * Implemented by the cache layer, consumed by the transport layer.
 */
public interface ResolutionPipeline {
    CompletableFuture<DnsResponse> resolve(DnsQuery query);
}