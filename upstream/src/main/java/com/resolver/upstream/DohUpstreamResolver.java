package com.resolver.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * DNS-over-HTTPS (DoH) upstream resolver.
 *
 * Forwards DNS queries to an upstream DoH provider (e.g. Cloudflare 1.1.1.1)
 * using the RFC 8484 wire-format POST method.
 *
 * How it works:
 *   1. Encode the DNS query into raw wire-format bytes
 *   2. POST those bytes to the DoH endpoint with content-type: application/dns-message
 *   3. Read the wire-format response bytes
 *   4. Decode them back into a Netty DnsResponse
 */
public class DohUpstreamResolver implements UpstreamResolver {

    private static final Logger logger = LoggerFactory.getLogger(DohUpstreamResolver.class);
    private static final String DNS_MESSAGE_TYPE = "application/dns-message";

    private final UpstreamConfig config;

    /**
     * Creates a resolver with the given upstream configuration.
     *
     * @param config The upstream DoH provider config
     */
    public DohUpstreamResolver(UpstreamConfig config) {
        this.config = config;
    }

    /**
     * Creates a resolver with default Cloudflare config.
     */
    public DohUpstreamResolver() {
        this(UpstreamConfig.cloudflare());
    }

    /**
     * Forwards a DNS query to the upstream DoH provider.
     *
     * @param query The DNS query to forward
     * @return A future that completes with the DNS response from upstream
     */
    @Override
    public CompletableFuture<DnsResponse> forward(DnsQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1: Encode the query into DNS wire-format bytes
                byte[] queryBytes = encodeDnsQuery(query);
                logger.debug("Sending DNS query to upstream: {} ({} bytes)",
                    config.getUrl(), queryBytes.length);

                // Step 2: Send the query via HTTPS POST to the DoH endpoint
                byte[] responseBytes = sendDohRequest(queryBytes);
                logger.debug("Received DNS response from upstream: {} bytes", responseBytes.length);

                // Step 3: Decode the wire-format response back into a DnsResponse
                DnsResponse response = decodeDnsResponse(responseBytes, query.id());
                return response;

            } catch (Exception e) {
                logger.error("Failed to resolve query via upstream {}: {}",
                    config.getUrl(), e.getMessage(), e);
                // Return a SERVFAIL response so the client knows something went wrong
                return buildErrorResponse(query.id());
            }
        });
    }

    /**
     * Encodes a DNS query into wire-format bytes (RFC 1035).
     *
     * Wire format:
     *   - Header: 12 bytes (ID, flags, counts)
     *   - Question: variable (domain name + type + class)
     */
    private byte[] encodeDnsQuery(DnsQuery query) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Get the question from the query
        DnsRecord question = query.recordAt(DnsSection.QUESTION);
        String domainName = question.name();
        int queryType = question.type().intValue();

        // --- DNS Header (12 bytes) ---
        dos.writeShort(query.id());       // Transaction ID
        dos.writeShort(0x0100);           // Flags: standard query, recursion desired
        dos.writeShort(1);                // QDCOUNT: 1 question
        dos.writeShort(0);                // ANCOUNT: 0 answers
        dos.writeShort(0);                // NSCOUNT: 0 authority records
        dos.writeShort(0);                // ARCOUNT: 0 additional records

        // --- Question Section ---
        // Encode the domain name in DNS label format
        // e.g. "www.google.com" -> [3]www[6]google[3]com[0]
        encodeDomainName(dos, domainName);
        dos.writeShort(queryType);        // QTYPE  (e.g. A=1, AAAA=28)
        dos.writeShort(1);                // QCLASS (IN = 1)

        return baos.toByteArray();
    }

    /**
     * Encodes a domain name in DNS label format.
     * "www.google.com." -> [3]www[6]google[3]com[0]
     */
    private void encodeDomainName(DataOutputStream dos, String name) throws Exception {
        // Remove trailing dot if present
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }

        String[] labels = name.split("\\.");
        for (String label : labels) {
            byte[] labelBytes = label.getBytes("UTF-8");
            dos.writeByte(labelBytes.length);  // Length prefix
            dos.write(labelBytes);              // Label content
        }
        dos.writeByte(0);  // Root label (end of name)
    }

    /**
     * Sends the DNS query bytes to the DoH endpoint via HTTPS POST.
     * Uses RFC 8484 wire-format: Content-Type: application/dns-message
     */
    private byte[] sendDohRequest(byte[] queryBytes) throws Exception {
        URL url = URI.create(config.getUrl()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", DNS_MESSAGE_TYPE);
            conn.setRequestProperty("Accept", DNS_MESSAGE_TYPE);
            conn.setConnectTimeout(config.getTimeoutMs());
            conn.setReadTimeout(config.getTimeoutMs());
            conn.setDoOutput(true);

            // Send the query bytes
            conn.getOutputStream().write(queryBytes);
            conn.getOutputStream().flush();

            // Check response code
            int httpStatus = conn.getResponseCode();
            if (httpStatus != 200) {
                throw new RuntimeException("DoH request failed with HTTP " + httpStatus);
            }

            // Read the response bytes
            try (InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Decodes wire-format DNS response bytes into a Netty DnsResponse.
     *
     * Parses the header, skips the question section, and extracts answer records.
     */
    private DnsResponse decodeDnsResponse(byte[] data, int originalId) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(data);

        // --- Parse Header (12 bytes) ---
        int id = buf.getShort() & 0xFFFF;           // Transaction ID
        int flags = buf.getShort() & 0xFFFF;         // Flags
        int qdCount = buf.getShort() & 0xFFFF;       // Question count
        int anCount = buf.getShort() & 0xFFFF;       // Answer count
        int nsCount = buf.getShort() & 0xFFFF;       // Authority count
        int arCount = buf.getShort() & 0xFFFF;       // Additional count

        int rcode = flags & 0x000F;                   // Response code (last 4 bits)

        // Build the Netty response
        DefaultDnsResponse response = new DefaultDnsResponse(originalId);
        response.setResponseCode(DnsResponseCode.valueOf(rcode));
        response.setRecursionDesired(true);
        response.setRecursionAvailable(true);

        // --- Skip Question Section ---
        for (int i = 0; i < qdCount; i++) {
            skipDomainName(buf);
            buf.getShort();  // QTYPE
            buf.getShort();  // QCLASS
        }

        // --- Parse Answer Section ---
        for (int i = 0; i < anCount; i++) {
            parseAndAddRecord(buf, data, response, DnsSection.ANSWER);
        }

        // --- Parse Authority Section ---
        for (int i = 0; i < nsCount; i++) {
            parseAndAddRecord(buf, data, response, DnsSection.AUTHORITY);
        }

        // --- Parse Additional Section ---
        for (int i = 0; i < arCount; i++) {
            parseAndAddRecord(buf, data, response, DnsSection.ADDITIONAL);
        }

        return response;
    }

    /**
     * Parses a single DNS resource record from the wire format and adds it to the response.
     */
    private void parseAndAddRecord(ByteBuffer buf, byte[] data,
                                   DefaultDnsResponse response, DnsSection section) {
        try {
            String name = readDomainName(buf, data);
            int type = buf.getShort() & 0xFFFF;
            int dnsClass = buf.getShort() & 0xFFFF;
            long ttl = buf.getInt() & 0xFFFFFFFFL;
            int rdLength = buf.getShort() & 0xFFFF;

            // Read RDATA
            byte[] rdata = new byte[rdLength];
            buf.get(rdata);

            // Create a raw DNS record with the data
            ByteBuf content = Unpooled.wrappedBuffer(rdata);
            DnsRawRecord record = new DnsRawRecord(
                name, DnsRecordType.valueOf(type), dnsClass, ttl, content
            );
            response.addRecord(section, record);

        } catch (Exception e) {
            logger.warn("Failed to parse DNS record in {} section: {}", section, e.getMessage());
        }
    }

    /**
     * Reads a domain name from the wire format, handling DNS name compression (RFC 1035 §4.1.4).
     *
     * Compression uses a pointer (2 bytes starting with 11xxxxxx) that references
     * an earlier position in the message where the name (or suffix) already appeared.
     */
    private String readDomainName(ByteBuffer buf, byte[] data) {
        StringBuilder name = new StringBuilder();
        boolean jumped = false;
        int savedPosition = -1;

        while (true) {
            int len = buf.get() & 0xFF;

            if (len == 0) {
                break;  // Root label — end of name
            }

            // Check for compression pointer (top 2 bits = 11)
            if ((len & 0xC0) == 0xC0) {
                if (!jumped) {
                    savedPosition = buf.position() + 1;  // Save where to resume after pointer
                }
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                buf.position(offset);  // Jump to the pointed-to position
                jumped = true;
                continue;
            }

            // Regular label
            byte[] labelBytes = new byte[len];
            buf.get(labelBytes);
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(labelBytes));
        }

        // Restore position if we followed a compression pointer
        if (jumped && savedPosition >= 0) {
            buf.position(savedPosition);
        }

        name.append('.');  // Trailing dot (FQDN convention)
        return name.toString();
    }

    /**
     * Skips over a domain name in the wire format without reading it.
     * Handles both regular labels and compression pointers.
     */
    private void skipDomainName(ByteBuffer buf) {
        while (true) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;  // Root label
            }
            if ((len & 0xC0) == 0xC0) {
                buf.get();  // Skip second byte of pointer
                break;      // Pointer is always the end
            }
            buf.position(buf.position() + len);  // Skip label bytes
        }
    }

    /**
     * Builds a SERVFAIL error response when the upstream request fails.
     */
    private DnsResponse buildErrorResponse(int id) {
        DefaultDnsResponse response = new DefaultDnsResponse(id);
        response.setResponseCode(DnsResponseCode.SERVFAIL);
        response.setRecursionDesired(true);
        response.setRecursionAvailable(true);
        return response;
    }
}
