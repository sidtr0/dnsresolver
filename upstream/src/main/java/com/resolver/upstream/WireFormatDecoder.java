package com.resolver.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Decodes RFC 1035 binary wire-format bytes into a Netty {@link DefaultDnsResponse}.
 *
 * <p>Handles DNS name compression (RFC 1035 §4.1.4) where a label pointer is
 * indicated by the two highest bits being set (0xC0), followed by a 14-bit offset
 * into the message.
 */
public class WireFormatDecoder {

    private static final Logger logger = LoggerFactory.getLogger(WireFormatDecoder.class);

    /** Mask for detecting a compressed name pointer (top 2 bits set). */
    private static final int POINTER_MASK = 0xC0;

    /** Maximum number of pointer hops to follow before aborting (prevents loops). */
    private static final int MAX_POINTER_HOPS = 64;

    /** Minimum valid DNS message size (header only). */
    private static final int MIN_MESSAGE_SIZE = 12;

    private WireFormatDecoder() {
        // Utility class
    }

    /**
     * Decodes a DNS wire-format byte array into a Netty {@link DefaultDnsResponse}.
     *
     * @param data the raw DNS response bytes
     * @return the decoded Netty DNS response
     * @throws IllegalArgumentException if the data is too short or malformed
     */
    public static DefaultDnsResponse decode(byte[] data) {
        if (data == null || data.length < MIN_MESSAGE_SIZE) {
            throw new IllegalArgumentException(
                    "DNS response too short: " + (data == null ? 0 : data.length) + " bytes");
        }

        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return decodeMessage(buf, data);
        } finally {
            buf.release();
        }
    }

    private static DefaultDnsResponse decodeMessage(ByteBuf buf, byte[] rawData) {
        // --- Header (12 bytes) ---
        int id = buf.readUnsignedShort();
        int flags = buf.readUnsignedShort();
        int qdCount = buf.readUnsignedShort();
        int anCount = buf.readUnsignedShort();
        int nsCount = buf.readUnsignedShort();
        int arCount = buf.readUnsignedShort();

        // Parse flags
        int opCode = (flags >> 11) & 0x0F;
        int rCode = flags & 0x0F;

        DefaultDnsResponse response = new DefaultDnsResponse(id, DnsOpCode.valueOf(opCode));
        response.setCode(DnsResponseCode.valueOf(rCode));
        response.setRecursionDesired((flags & 0x0100) != 0);
        response.setRecursionAvailable((flags & 0x0080) != 0);
        response.setAuthoritativeAnswer((flags & 0x0400) != 0);
        response.setTruncated((flags & 0x0200) != 0);
        response.setZ((flags >> 4) & 0x07);

        if (logger.isDebugEnabled()) {
            logger.debug("Decoding DNS response: id={}, opcode={}, rcode={}, " +
                            "qd={}, an={}, ns={}, ar={}",
                    id, opCode, rCode, qdCount, anCount, nsCount, arCount);
        }

        // --- Question Section ---
        for (int i = 0; i < qdCount; i++) {
            String qName = decodeDomainName(buf, rawData);
            int qType = buf.readUnsignedShort();
            int qClass = buf.readUnsignedShort();
            response.addRecord(DnsSection.QUESTION,
                    new DefaultDnsRawRecord(qName, DnsRecordType.valueOf(qType), qClass, 0,
                            Unpooled.EMPTY_BUFFER.retainedDuplicate()));
        }

        // --- Answer Section ---
        decodeRecords(buf, rawData, response, DnsSection.ANSWER, anCount);

        // --- Authority Section ---
        decodeRecords(buf, rawData, response, DnsSection.AUTHORITY, nsCount);

        // --- Additional Section ---
        decodeRecords(buf, rawData, response, DnsSection.ADDITIONAL, arCount);

        return response;
    }

    /**
     * Decodes a batch of resource records from the buffer and adds them to the response.
     */
    private static void decodeRecords(ByteBuf buf, byte[] rawData,
                                       DefaultDnsResponse response,
                                       DnsSection section, int count) {
        for (int i = 0; i < count; i++) {
            if (buf.readableBytes() < 1) {
                logger.warn("Truncated DNS response: expected {} records in {}, got {}",
                        count, section, i);
                break;
            }

            try {
                String name = decodeDomainName(buf, rawData);
                int type = buf.readUnsignedShort();
                int dnsClass = buf.readUnsignedShort();
                long ttl = buf.readUnsignedInt();
                int rdLength = buf.readUnsignedShort();

                if (buf.readableBytes() < rdLength) {
                    logger.warn("Truncated RDATA in {} record {}: expected {} bytes, have {}",
                            section, i, rdLength, buf.readableBytes());
                    break;
                }

                // Read the raw RDATA bytes
                ByteBuf rdata = Unpooled.buffer(rdLength);
                buf.readBytes(rdata, rdLength);

                DefaultDnsRawRecord record = new DefaultDnsRawRecord(
                        name, DnsRecordType.valueOf(type), dnsClass, ttl, rdata);
                response.addRecord(section, record);
            } catch (Exception e) {
                logger.error("Error decoding record {} in section {}: {}",
                        i, section, e.getMessage());
                break;
            }
        }
    }

    /**
     * Decodes a DNS domain name from the buffer, handling label compression
     * (RFC 1035 §4.1.4).
     *
     * <p>Compressed labels use a 2-byte pointer: the first byte has the top two
     * bits set (0xC0), and the remaining 14 bits indicate the offset from the
     * start of the DNS message where the name (or remainder) continues.
     *
     * @param buf     the buffer positioned at the start of the name
     * @param rawData the complete raw DNS message (for resolving pointers)
     * @return the decoded domain name with a trailing dot
     */
    static String decodeDomainName(ByteBuf buf, byte[] rawData) {
        StringBuilder name = new StringBuilder(64);
        int hops = 0;
        boolean jumped = false;

        int offset = buf.readerIndex();

        while (true) {
            if (offset >= rawData.length) {
                throw new IllegalArgumentException(
                        "Domain name offset out of bounds: " + offset);
            }

            int labelLen = rawData[offset] & 0xFF;

            if (labelLen == 0) {
                // Root label — end of name
                if (!jumped) {
                    buf.readerIndex(offset + 1);
                }
                break;
            }

            if ((labelLen & POINTER_MASK) == POINTER_MASK) {
                // Compressed pointer
                if (++hops > MAX_POINTER_HOPS) {
                    throw new IllegalArgumentException(
                            "DNS name compression loop detected (>" + MAX_POINTER_HOPS + " hops)");
                }
                if (!jumped) {
                    // Save the position after the 2-byte pointer for the caller
                    buf.readerIndex(offset + 2);
                    jumped = true;
                }
                // Follow the pointer
                offset = ((labelLen & 0x3F) << 8) | (rawData[offset + 1] & 0xFF);
                continue;
            }

            // Regular label
            offset++;
            if (offset + labelLen > rawData.length) {
                throw new IllegalArgumentException(
                        "DNS label extends beyond message boundary");
            }

            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(rawData, offset, labelLen, StandardCharsets.US_ASCII));
            offset += labelLen;
        }

        // Always append trailing dot (DNS convention)
        name.append('.');
        return name.toString();
    }
}
