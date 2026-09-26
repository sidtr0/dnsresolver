package com.resolver.upstream;

import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WireFormatEncoderTest {

    @Test
    @DisplayName("Encode a standard A query for google.com")
    void testEncodeStandardAQuery() {
        DnsQuery query = new DefaultDnsQuery(0x1234, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION,
                new DefaultDnsQuestion("google.com.", DnsRecordType.A));

        byte[] wire = WireFormatEncoder.encode(query);

        assertNotNull(wire);
        assertTrue(wire.length >= 12, "DNS message must be at least 12 bytes");

        // Transaction ID: 0x1234
        assertEquals(0x12, wire[0] & 0xFF);
        assertEquals(0x34, wire[1] & 0xFF);

        // Flags: RD=1 (0x0100)
        assertEquals(0x01, wire[2] & 0xFF);
        assertEquals(0x00, wire[3] & 0xFF);

        // QDCOUNT = 1
        assertEquals(0x00, wire[4] & 0xFF);
        assertEquals(0x01, wire[5] & 0xFF);

        // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        assertEquals(0x00, wire[6] & 0xFF);
        assertEquals(0x00, wire[7] & 0xFF);
        assertEquals(0x00, wire[8] & 0xFF);
        assertEquals(0x00, wire[9] & 0xFF);
        assertEquals(0x00, wire[10] & 0xFF);
        assertEquals(0x00, wire[11] & 0xFF);

        // QNAME: 6 google 3 com 0
        assertEquals(6, wire[12]);
        String label1 = new String(wire, 13, 6, StandardCharsets.US_ASCII);
        assertEquals("google", label1);

        assertEquals(3, wire[19]);
        String label2 = new String(wire, 20, 3, StandardCharsets.US_ASCII);
        assertEquals("com", label2);

        assertEquals(0, wire[23]); // Root terminator

        // QTYPE = A (1)
        assertEquals(0x00, wire[24]);
        assertEquals(0x01, wire[25]);

        // QCLASS = IN (1)
        assertEquals(0x00, wire[26]);
        assertEquals(0x01, wire[27]);
    }

    @Test
    @DisplayName("Encode throws exception when query has no questions")
    void testEncodeEmptyQuestionThrows() {
        DnsQuery query = new DefaultDnsQuery(1, DnsOpCode.QUERY);
        assertThrows(IllegalArgumentException.class, () -> WireFormatEncoder.encode(query));
    }

    @Test
    @DisplayName("Encode query with subdomains: api.sub.example.com")
    void testEncodeMultiLabelDomain() {
        DnsQuery query = new DefaultDnsQuery(42, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION,
                new DefaultDnsQuestion("api.sub.example.com.", DnsRecordType.AAAA));

        byte[] wire = WireFormatEncoder.encode(query);
        assertNotNull(wire);

        // QTYPE for AAAA is 28 (0x001C)
        int qtypeOffset = wire.length - 4;
        assertEquals(0x00, wire[qtypeOffset] & 0xFF);
        assertEquals(0x1C, wire[qtypeOffset + 1] & 0xFF);
    }
}
