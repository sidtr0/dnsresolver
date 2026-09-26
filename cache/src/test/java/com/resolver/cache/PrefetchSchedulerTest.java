package com.resolver.cache;

import com.resolver.upstream.UpstreamResolver;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class PrefetchSchedulerTest {

    private DnsResponse createMockResponse(int id, String domain, long ttl) {
        DefaultDnsResponse response = new DefaultDnsResponse(id);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(domain, DnsRecordType.A, DnsRecord.CLASS_IN));

        byte[] ip = new byte[] {127, 0, 0, 1};
        DnsRecord answer = new DefaultDnsRawRecord(domain, DnsRecordType.A, DnsRecord.CLASS_IN, ttl, Unpooled.wrappedBuffer(ip));
        response.addRecord(DnsSection.ANSWER, answer);
        return response;
    }

    @Test
    void testShouldPrefetchLogic() throws Exception {
        UpstreamResolver mockUpstream = Mockito.mock(UpstreamResolver.class);
        CacheConfig config = new CacheConfig();
        config.setPrefetchThresholdQueries(5);

        DnsCache dnsCache = new DnsCache(mockUpstream, config);
        PrefetchScheduler scheduler = new PrefetchScheduler(dnsCache, config);

        // Low queries (cold) -> shouldn't prefetch
        CachedResponse cold = new CachedResponse(createMockResponse(1, "cold.com.", 100), 100, false);
        assertFalse(scheduler.shouldPrefetch(cold));

        // High queries, but plenty of TTL left -> shouldn't prefetch
        CachedResponse hotFresh = new CachedResponse(createMockResponse(2, "hot.com.", 100), 100, false);
        for (int i = 0; i < 10; i++) {
            hotFresh.incrementQueryCount();
        }
        assertFalse(scheduler.shouldPrefetch(hotFresh));

        // High queries and low TTL -> SHOULD prefetch
        // Create an entry with original TTL=10s, sleep 8s (so remaining is ~2s, which is 20% < 25%)
        CachedResponse hotExpiring = new CachedResponse(createMockResponse(3, "expiring.com.", 10), 10, false);
        for (int i = 0; i < 10; i++) {
            hotExpiring.incrementQueryCount();
        }
        Thread.sleep(8000);
        assertTrue(scheduler.shouldPrefetch(hotExpiring));
    }
}
