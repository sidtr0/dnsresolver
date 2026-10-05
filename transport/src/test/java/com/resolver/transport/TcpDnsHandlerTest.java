package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.*;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TcpDnsHandlerTest {

    @Mock
    private ResolutionPipeline pipeline;

    private EmbeddedChannel channel;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        channel = new EmbeddedChannel(new TcpDnsHandler(pipeline));
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.close();
        mocks.close();
    }

    @Test
    void testValidQueryReturnsMockedResponse() {
        DefaultDnsResponse mockResponse = new DefaultDnsResponse(1234, DnsOpCode.QUERY, DnsResponseCode.NOERROR);
        mockResponse.addRecord(DnsSection.ANSWER,
            new DefaultDnsRawRecord("google.com", DnsRecordType.A, 300, io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{8, 8, 8, 8})));

        when(pipeline.resolve(any(DnsQuery.class)))
            .thenReturn(CompletableFuture.completedFuture(mockResponse));

        DefaultDnsQuery query = new DefaultDnsQuery(1234, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("google.com", DnsRecordType.A));
        query.setRecursionDesired(true);

        channel.writeInbound(query);

        Object out = channel.readOutbound();
        assertNotNull(out);
        assertTrue(out instanceof DefaultDnsResponse);

        DefaultDnsResponse tcpResponse = (DefaultDnsResponse) out;

        assertEquals(1234, tcpResponse.id());
        assertEquals(DnsResponseCode.NOERROR, tcpResponse.code());
        assertEquals(1, tcpResponse.count(DnsSection.ANSWER));

        tcpResponse.release();
    }

    @Test
    void testIdleStateClosesConnection() {
        assertTrue(channel.isOpen());

        // Simulate IdleStateHandler firing an event
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        // Assert the channel is closed
        assertFalse(channel.isOpen(), "Channel should be closed after idle event");
    }
}
