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
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DnsCacheTest {

    private UpstreamResolver mockUpstream;
    private CacheConfig config;
    private DnsCache dnsCache;

    @BeforeEach
    void setUp() {
        mockUpstream = Mockito.mock(UpstreamResolver.class);
        config = new CacheConfig();
        dnsCache = new DnsCache(mockUpstream, config);
    }

    private DnsResponse createMockResponse(int id, String domain, long ttl) {
        DefaultDnsResponse response = new DefaultDnsResponse(id);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(domain, DnsRecordType.A, DnsRecord.CLASS_IN));

        // 127.0.0.1 in bytes
        byte[] ip = new byte[] {127, 0, 0, 1};
        DnsRecord answer = new DefaultDnsRawRecord(domain, DnsRecordType.A, DnsRecord.CLASS_IN, ttl, Unpooled.wrappedBuffer(ip));
        response.addRecord(DnsSection.ANSWER, answer);
        return response;
    }

    @Test
    void testCacheHit() throws Exception {
        DnsResponse mockResp = createMockResponse(1234, "google.com.", 300);
        when(mockUpstream.forward(any())).thenReturn(CompletableFuture.completedFuture(mockResp));

        DnsQuery query1 = new DefaultDnsQuery(100);
        query1.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com", DnsRecordType.A, DnsRecord.CLASS_IN));

        // First resolution (Miss)
        DnsResponse resp1 = dnsCache.resolve(query1).get();
        assertEquals(100, resp1.id());
        verify(mockUpstream, times(1)).forward(any());

        // Second resolution (Hit)
        DnsQuery query2 = new DefaultDnsQuery(200);
        query2.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com.", DnsRecordType.A, DnsRecord.CLASS_IN));

        DnsResponse resp2 = dnsCache.resolve(query2).get();
        assertEquals(200, resp2.id());
        // Verify upstream was NOT called again
        verify(mockUpstream, times(1)).forward(any());
    }

    @Test
    void testNegativeCaching() throws Exception {
        DefaultDnsResponse nxdomainResp = new DefaultDnsResponse(1234, io.netty.handler.codec.dns.DnsOpCode.QUERY, DnsResponseCode.NXDOMAIN);
        nxdomainResp.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("nonexistent.org.", DnsRecordType.A, DnsRecord.CLASS_IN));

        when(mockUpstream.forward(any())).thenReturn(CompletableFuture.completedFuture(nxdomainResp));

        DnsQuery query1 = new DefaultDnsQuery(10);
        query1.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("nonexistent.org", DnsRecordType.A, DnsRecord.CLASS_IN));

        DnsResponse resp1 = dnsCache.resolve(query1).get();
        assertEquals(DnsResponseCode.NXDOMAIN, resp1.code());

        // Second query
        DnsQuery query2 = new DefaultDnsQuery(20);
        query2.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("nonexistent.org", DnsRecordType.A, DnsRecord.CLASS_IN));

        DnsResponse resp2 = dnsCache.resolve(query2).get();
        assertEquals(DnsResponseCode.NXDOMAIN, resp2.code());

        verify(mockUpstream, times(1)).forward(any());
    }
}
