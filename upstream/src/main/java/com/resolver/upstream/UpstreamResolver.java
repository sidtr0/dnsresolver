package com.resolver.upstream;

import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for forwarding DNS queries to upstream DoH/DoT providers.
 * Implemented by the upstream module, consumed by the cache layer.
 */
public interface UpstreamResolver {
    CompletableFuture<DnsResponse> forward(DnsQuery query);
}