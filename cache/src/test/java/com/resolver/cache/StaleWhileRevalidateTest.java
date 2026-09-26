package com.resolver.cache;

import com.resolver.upstream.UpstreamResolver;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaleWhileRevalidateTest {

    private DnsResponse createMockResponse(int id, String domain, long ttl) {
        DefaultDnsResponse response = new DefaultDnsResponse(id);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(domain, DnsRecordType.A, DnsRecord.CLASS_IN));

        byte[] ip = new byte[] {127, 0, 0, 1};
        DnsRecord answer = new DefaultDnsRawRecord(domain, DnsRecordType.A, DnsRecord.CLASS_IN, ttl, Unpooled.wrappedBuffer(ip));
        response.addRecord(DnsSection.ANSWER, answer);
        return response;
    }

    @Test
    void testSWRReturnsStaleAndRefreshes() throws Exception {
        UpstreamResolver mockUpstream = Mockito.mock(UpstreamResolver.class);
        CacheConfig config = new CacheConfig();
        config.setStaleWhileRevalidate(true);
        config.setStaleWindowSeconds(10);

        DnsCache dnsCache = new DnsCache(mockUpstream, config);

        // First response has a short TTL
        DnsResponse initialResp = createMockResponse(1, "swr-test.com.", 1);
        when(mockUpstream.forward(any())).thenReturn(CompletableFuture.completedFuture(initialResp));

        DnsQuery query = new DefaultDnsQuery(100);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("swr-test.com", DnsRecordType.A, DnsRecord.CLASS_IN));

        // Initial fetch
        DnsResponse resp1 = dnsCache.resolve(query).get();
        assertEquals(100, resp1.id());

        // Wait for TTL to expire (sleep 1100ms)
        Thread.sleep(1100);

        // Prepare refreshed mock response
        DnsResponse refreshedResp = createMockResponse(2, "swr-test.com.", 300);
        when(mockUpstream.forward(any())).thenReturn(CompletableFuture.completedFuture(refreshedResp));

        // Subsequent query should return stale response immediately
        long start = System.currentTimeMillis();
        DnsResponse resp2 = dnsCache.resolve(query).get();
        long duration = System.currentTimeMillis() - start;

        // Sub-millisecond or very fast response
        assertTrue(duration < 100, "Should return stale response immediately");
        assertNotNull(resp2);

        // Verify that async background refresh was triggered (mockUpstream called a second time)
        Thread.sleep(200); // Give background task time to run
        verify(mockUpstream, atLeast(2)).forward(any());
    }
}
