package com.resolver.upstream;

import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpstreamManagerTest {

    @Mock
    private DoHClient dohClient;

    private UpstreamManager manager;
    private UpstreamServer s1;
    private UpstreamServer s2;
    private List<UpstreamServer> servers;
    private byte[] syntheticWireResponse;

    @BeforeEach
    void setUp() {
        s1 = new UpstreamServer("Cloudflare", URI.create("https://1.1.1.1/dns-query"), 1);
        s2 = new UpstreamServer("Google", URI.create("https://8.8.8.8/dns-query"), 2);
        servers = Arrays.asList(s1, s2);

        // Create a valid dummy response to encode
        DefaultDnsQuery dummyResponse = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        dummyResponse.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));
        syntheticWireResponse = WireFormatEncoder.encode(dummyResponse);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("Failover strategy: Uses priority 1, falls back to priority 2 on error")
    void testFailoverChain() throws Exception {
        manager = new UpstreamManager(servers, dohClient, UpstreamManager.RoutingStrategy.FAILOVER_CHAIN);

        DnsQuery testQuery = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        testQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));

        // s1 fails, s2 succeeds
        CompletableFuture<byte[]> s1Fail = new CompletableFuture<>();
        s1Fail.completeExceptionally(new UpstreamException("Timeout"));

        when(dohClient.query(eq(s1.uri()), any(byte[].class))).thenReturn(s1Fail);
        when(dohClient.query(eq(s2.uri()), any(byte[].class))).thenReturn(
                CompletableFuture.completedFuture(syntheticWireResponse));

        CompletableFuture<DnsResponse> result = manager.forward(testQuery);
        DnsResponse response = result.get();

        assertNotNull(response);
        verify(dohClient).query(eq(s1.uri()), any(byte[].class));
        verify(dohClient).query(eq(s2.uri()), any(byte[].class));

        // Let's also verify metrics/health update
        assertTrue(s2.isHealthy());
        assertEquals(1, s2.totalQueries());
        assertEquals(1, s1.totalFailures());
        assertTrue(s1.isHealthy()); // Doesn't cross threshold yet
    }

    @Test
    @DisplayName("Circuit breaker trips after 3 failures")
    void testCircuitBreakerTrips() {
        manager = new UpstreamManager(servers, dohClient, UpstreamManager.RoutingStrategy.FAILOVER_CHAIN);

        DnsQuery testQuery = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        testQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));

        CompletableFuture<byte[]> fail = new CompletableFuture<>();
        fail.completeExceptionally(new UpstreamException("Bad gateway"));
        when(dohClient.query(any(), any())).thenReturn(fail);

        for (int i = 0; i < 3; i++) {
            assertThrows(ExecutionException.class, () -> manager.forward(testQuery).get());
        }

        // Both should be marked unhealthy because chain tried 1 then 2 each time
        assertFalse(s1.isHealthy());
        assertFalse(s2.isHealthy());
    }

    @Test
    @DisplayName("Lowest Latency strategy: picks faster server")
    void testLowestLatencyRouting() throws Exception {
        manager = new UpstreamManager(servers, dohClient, UpstreamManager.RoutingStrategy.LOWEST_LATENCY);

        DnsQuery testQuery = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        testQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));

        // Artificially manipulate EWMA for s2 so it's lower than s1 (whose initial is 100)
        s2.recordSuccess(10); // EWMA drops significantly

        when(dohClient.query(eq(s2.uri()), any(byte[].class))).thenReturn(
                CompletableFuture.completedFuture(syntheticWireResponse));

        DnsResponse response = manager.forward(testQuery).get();

        assertNotNull(response);
        // Only s2 should be called because it had lower latency
        verify(dohClient, never()).query(eq(s1.uri()), any(byte[].class));
        verify(dohClient).query(eq(s2.uri()), any(byte[].class));
    }

    @Test
    @DisplayName("Parallel Race strategy: attempts all healthy servers, takes first")
    void testParallelRace() throws Exception {
        manager = new UpstreamManager(servers, dohClient, UpstreamManager.RoutingStrategy.PARALLEL_RACE);

        DnsQuery testQuery = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        testQuery.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));

        CompletableFuture<byte[]> slowResponse = new CompletableFuture<>();
        CompletableFuture<byte[]> fastResponse = CompletableFuture.completedFuture(syntheticWireResponse);

        // Intercept both calls
        when(dohClient.query(eq(s1.uri()), any(byte[].class))).thenReturn(slowResponse);
        when(dohClient.query(eq(s2.uri()), any(byte[].class))).thenReturn(fastResponse);

        DnsResponse response = manager.forward(testQuery).get();

        assertNotNull(response);
        verify(dohClient).query(eq(s1.uri()), any(byte[].class));
        verify(dohClient).query(eq(s2.uri()), any(byte[].class));
    }
}
