package com.resolver.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WireFormatDecoderTest {

    @Test
    @DisplayName("Reject responses shorter than 12 bytes")
    void testRejectShortMessage() {
        assertThrows(IllegalArgumentException.class, () -> WireFormatDecoder.decode(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> WireFormatDecoder.decode(null));
    }

    @Test
    @DisplayName("Round-trip: Encode query, decode as response header + question")
    void testEncodeDecodeRoundTrip() {
        DnsQuery query = new DefaultDnsQuery(0x5678, DnsOpCode.QUERY);
        query.addRecord(DnsSection.QUESTION,
                new DefaultDnsQuestion("cloudflare.com.", DnsRecordType.A));

        byte[] wire = WireFormatEncoder.encode(query);
        DefaultDnsResponse decoded = WireFormatDecoder.decode(wire);

        assertEquals(0x5678, decoded.id());
        assertEquals(DnsOpCode.QUERY, decoded.opCode());
        assertEquals(1, decoded.count(DnsSection.QUESTION));

        DnsRawRecord q = decoded.recordAt(DnsSection.QUESTION, 0);
        assertEquals("cloudflare.com.", q.name());
        assertEquals(DnsRecordType.A, q.type());
    }

    @Test
    @DisplayName("Decode synthetic DNS response with A record and pointer compression")
    void testDecodeWithCompressedPointer() {
        // Construct a DNS message with an answer pointing back to the question name
        // Header (12 bytes): ID=0x0001, QR=1, RCODE=NOERROR, QDCOUNT=1, ANCOUNT=1
        ByteBuf buf = Unpooled.buffer(64);
        buf.writeShort(0x0001); // ID
        buf.writeShort(0x8180); // Flags: QR=1, RD=1, RA=1
        buf.writeShort(1);      // QDCOUNT
        buf.writeShort(1);      // ANCOUNT
        buf.writeShort(0);      // NSCOUNT
        buf.writeShort(0);      // ARCOUNT

        // Question: "example.com", Type=A, Class=IN
        // Offset = 12
        buf.writeByte(7);
        buf.writeBytes("example".getBytes());
        buf.writeByte(3);
        buf.writeBytes("com".getBytes());
        buf.writeByte(0);       // Root
        buf.writeShort(1);      // Type A
        buf.writeShort(1);      // Class IN

        // Answer: Compressed pointer to offset 12 (0xC00C)
        buf.writeShort(0xC00C); // Pointer to "example.com" at byte 12
        buf.writeShort(1);      // Type A
        buf.writeShort(1);      // Class IN
        buf.writeInt(300);      // TTL = 300
        buf.writeShort(4);      // RDLENGTH = 4
        buf.writeByte(93);      // 93.184.216.34 (example.com IP)
        buf.writeByte(184);
        buf.writeByte(216);
        buf.writeByte(34);

        byte[] raw = new byte[buf.readableBytes()];
        buf.readBytes(raw);

        DefaultDnsResponse response = WireFormatDecoder.decode(raw);

        assertEquals(0x0001, response.id());
        assertEquals(DnsResponseCode.NOERROR, response.code());
        assertEquals(1, response.count(DnsSection.QUESTION));
        assertEquals(1, response.count(DnsSection.ANSWER));

        DnsRawRecord ans = response.recordAt(DnsSection.ANSWER, 0);
        assertEquals("example.com.", ans.name());
        assertEquals(DnsRecordType.A, ans.type());
        assertEquals(300, ans.timeToLive());

        ByteBuf rdata = ans.content();
        assertEquals(4, rdata.readableBytes());
        assertEquals((byte) 93, rdata.readByte());
        assertEquals((byte) 184, rdata.readByte());
        assertEquals((byte) 216, rdata.readByte());
        assertEquals((byte) 34, rdata.readByte());
    }
}
