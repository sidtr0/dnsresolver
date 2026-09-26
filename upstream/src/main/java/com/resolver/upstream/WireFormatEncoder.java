package com.resolver.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Encodes a Netty {@link DnsQuery} into RFC 1035 binary wire-format bytes
 * suitable for transmission via DNS-over-HTTPS (RFC 8484).
 *
 * <p>The wire format consists of:
 * <ul>
 *   <li>12-byte header (ID, flags, section counts)</li>
 *   <li>Question section (QNAME + QTYPE + QCLASS)</li>
 *   <li>Additional section (OPT/EDNS0 pseudo-records, if present)</li>
 * </ul>
 */
public class WireFormatEncoder {

    private static final Logger logger = LoggerFactory.getLogger(WireFormatEncoder.class);

    /** Standard query with Recursion Desired flag set. */
    private static final int FLAGS_RD = 0x0100;

    private WireFormatEncoder() {
        // Utility class
    }

    /**
     * Encodes the given DNS query into RFC 1035 binary wire-format.
     *
     * @param query the Netty DNS query to encode
     * @return the wire-format byte array
     * @throws IllegalArgumentException if the query has no question section
     */
    public static byte[] encode(DnsQuery query) {
        if (query.count(DnsSection.QUESTION) < 1) {
            throw new IllegalArgumentException("DNS query must contain at least one question");
        }

        ByteBuf buf = Unpooled.buffer(512);
        try {
            writeHeader(buf, query);
            writeQuestionSection(buf, query);
            writeAdditionalSection(buf, query);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            if (logger.isDebugEnabled()) {
                DnsQuestion q = query.recordAt(DnsSection.QUESTION, 0);
                logger.debug("Encoded DNS query: {} {} ({} bytes)",
                        q.name(), q.type(), bytes.length);
            }

            return bytes;
        } finally {
            buf.release();
        }
    }

    private static void writeHeader(ByteBuf buf, DnsQuery query) {
        // Transaction ID — for DoH this can be 0, but we preserve the original
        buf.writeShort(query.id());

        // Flags: standard query with RD=1
        buf.writeShort(FLAGS_RD);

        // Section counts
        buf.writeShort(query.count(DnsSection.QUESTION));  // QDCOUNT
        buf.writeShort(0);                                   // ANCOUNT
        buf.writeShort(0);                                   // NSCOUNT
        buf.writeShort(query.count(DnsSection.ADDITIONAL));  // ARCOUNT
    }

    private static void writeQuestionSection(ByteBuf buf, DnsQuery query) {
        int qdCount = query.count(DnsSection.QUESTION);
        for (int i = 0; i < qdCount; i++) {
            DnsQuestion question = query.recordAt(DnsSection.QUESTION, i);
            encodeDomainName(buf, question.name());
            buf.writeShort(question.type().intValue());
            buf.writeShort(question.dnsClass());
        }
    }

    private static void writeAdditionalSection(ByteBuf buf, DnsQuery query) {
        int arCount = query.count(DnsSection.ADDITIONAL);
        for (int i = 0; i < arCount; i++) {
            DnsRecord record = query.recordAt(DnsSection.ADDITIONAL, i);
            if (record.type() == DnsRecordType.OPT) {
                encodeOptRecord(buf, record);
            }
        }
    }

    /**
     * Encodes an OPT pseudo-record (EDNS0) into the buffer.
     * OPT record format (RFC 6891):
     *   NAME:    0x00 (root domain)
     *   TYPE:    OPT (41)
     *   CLASS:   UDP payload size (requestor's max UDP payload)
     *   TTL:     Extended RCODE + version + DO flag
     *   RDLEN:   Length of RDATA (options)
     *   RDATA:   Variable-length options
     */
    private static void encodeOptRecord(ByteBuf buf, DnsRecord record) {
        buf.writeByte(0);                              // Root domain name
        buf.writeShort(DnsRecordType.OPT.intValue());  // TYPE = OPT (41)
        buf.writeShort(record.dnsClass());             // CLASS = UDP payload size
        buf.writeInt((int) record.timeToLive());       // TTL = extended RCODE + flags

        if (record instanceof DnsRawRecord) {
            DnsRawRecord raw = (DnsRawRecord) record;
            ByteBuf content = raw.content();
            int rdLength = content.readableBytes();
            buf.writeShort(rdLength);
            if (rdLength > 0) {
                buf.writeBytes(content, content.readerIndex(), rdLength);
            }
        } else {
            buf.writeShort(0); // No RDATA
        }
    }

    /**
     * Encodes a domain name into DNS wire format (length-prefixed labels).
     * Example: "google.com" → {6}'g''o''o''g''l''e'{3}'c''o''m'{0}
     *
     * @param buf  target buffer
     * @param name the domain name (with or without trailing dot)
     */
    static void encodeDomainName(ByteBuf buf, String name) {
        // Strip trailing root dot if present
        String clean = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;

        if (clean.isEmpty()) {
            buf.writeByte(0); // Root domain
            return;
        }

        for (String label : clean.split("\\.")) {
            byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            if (labelBytes.length > 63) {
                throw new IllegalArgumentException(
                        "DNS label exceeds 63 bytes: \"" + label + "\"");
            }
            buf.writeByte(labelBytes.length);
            buf.writeBytes(labelBytes);
        }
        buf.writeByte(0); // Root label terminator
    }
}
