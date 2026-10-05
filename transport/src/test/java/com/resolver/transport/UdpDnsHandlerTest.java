package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UdpDnsHandlerTest {

    @Mock
    private ResolutionPipeline pipeline;

    private EmbeddedChannel channel;
    private AutoCloseable mocks;
    private final InetSocketAddress sender = new InetSocketAddress("10.0.0.1", 12345);
    private final InetSocketAddress recipient = new InetSocketAddress("127.0.0.1", 53);

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        channel = new EmbeddedChannel(new UdpDnsHandler(pipeline));
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.close();
        mocks.close();
    }

    @Test
    void testValidQueryReturnsMockedResponse() {
        // Setup mock pipeline to return a successful answer
        DefaultDnsResponse mockResponse = new DefaultDnsResponse(1234, DnsOpCode.QUERY, DnsResponseCode.NOERROR);
        mockResponse.addRecord(DnsSection.ANSWER,
            new DefaultDnsRawRecord("google.com", DnsRecordType.A, 300, io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{8, 8, 8, 8})));

        when(pipeline.resolve(any(DnsQuery.class)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse));

        // Create an incoming UDP query
        DatagramDnsQuery query = new DatagramDnsQuery(sender, recipient, 1234, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com", DnsRecordType.A));
        query.setRecursionDesired(true);

        // Feed query into channel
        channel.writeInbound(query);

        // Get the response written back
        Object out = channel.readOutbound();
        assertNotNull(out, "Expected a response");
        assertTrue(out instanceof DatagramDnsResponse, "Response should be a DatagramDnsResponse");

        DatagramDnsResponse udpResponse = (DatagramDnsResponse) out;

        // Assertions
        assertEquals(1234, udpResponse.id());
        assertEquals(DnsResponseCode.NOERROR, udpResponse.code());
        assertTrue(udpResponse.isRecursionDesired());
        assertTrue(udpResponse.isRecursionAvailable());
        assertEquals(1, udpResponse.count(DnsSection.ANSWER));

        // Ensure recipient of the response is the original sender
        assertEquals(sender, udpResponse.recipient());

        udpResponse.release();
    }

    @Test
    void testMalformedQueryReturnsFormErr() {
        // Create an incoming UDP query with NO question (malformed)
        DatagramDnsQuery query = new DatagramDnsQuery(sender, recipient, 9999, DnsOpCode.QUERY);

        // We shouldn't even call the pipeline for this

        channel.writeInbound(query);

        Object out = channel.readOutbound();
        assertNotNull(out);
        assertTrue(out instanceof DatagramDnsResponse);

        DatagramDnsResponse udpResponse = (DatagramDnsResponse) out;

        assertEquals(9999, udpResponse.id());
        assertEquals(DnsResponseCode.FORMERR, udpResponse.code());

        verify(pipeline, never()).resolve(any());
        udpResponse.release();
    }

    @Test
    void testPipelineExceptionReturnsServFail() {
        // Pipeline throws an exception
        CompletableFuture<DnsResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Upstream failed"));
        when(pipeline.resolve(any(DnsQuery.class))).thenReturn(failedFuture);

        DatagramDnsQuery query = new DatagramDnsQuery(sender, recipient, 1111, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com", DnsRecordType.A));

        channel.writeInbound(query);

        Object out = channel.readOutbound();
        assertNotNull(out);

        DatagramDnsResponse udpResponse = (DatagramDnsResponse) out;
        assertEquals(DnsResponseCode.SERVFAIL, udpResponse.code());

        udpResponse.release();
    }
}
