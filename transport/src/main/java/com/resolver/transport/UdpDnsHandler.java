package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import com.resolver.transport.QueryValidator.ValidationResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles incoming UDP DNS queries.
 *
 * For each query this handler:
 * <ol>
 *   <li>Validates the query via {@link QueryValidator}.</li>
 *   <li>Forwards valid queries to the {@link ResolutionPipeline}.</li>
 *   <li>Builds a {@link DatagramDnsResponse} from the pipeline result and
 *       writes it back to the client.</li>
 *   <li>Applies EDNS0-aware truncation when the response exceeds the client's
 *       advertised buffer size.</li>
 * </ol>
 *
 * The channel is never closed on error because UDP uses a single shared channel
 * for all clients.
 */
public class UdpDnsHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    private static final Logger logger = LoggerFactory.getLogger(UdpDnsHandler.class);

    /** Default maximum UDP payload when the client sends no EDNS0 OPT record. */
    private static final int DEFAULT_UDP_PAYLOAD_SIZE = 512;

    /** Our advertised EDNS0 UDP payload size. */
    private static final int SERVER_UDP_PAYLOAD_SIZE = 4096;

    /** Timeout for pipeline resolution in milliseconds. */
    private static final long PIPELINE_TIMEOUT_MS = 5000;

    private final ResolutionPipeline pipeline;
    private final QueryValidator validator;

    // ── Metric counters ─────────────────────────────────────────────────
    private final AtomicLong queriesReceived = new AtomicLong();
    private final AtomicLong queriesFailedValidation = new AtomicLong();
    private final AtomicLong queriesFailedTimeout = new AtomicLong();
    private final AtomicLong queriesFailedError = new AtomicLong();

    /**
     * Creates a UDP handler wired to the given pipeline.
     *
     * @param pipeline the resolution pipeline to forward valid queries to
     */
    public UdpDnsHandler(ResolutionPipeline pipeline) {
        this.pipeline = pipeline;
        this.validator = new QueryValidator();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
        queriesReceived.incrementAndGet();
        int transactionId = query.id();

        if (logger.isDebugEnabled()) {
            String name = query.count(DnsSection.QUESTION) > 0
                    ? query.recordAt(DnsSection.QUESTION, 0).name() : "<none>";
            logger.debug("UDP query id={} name={}", transactionId, name);
        }

        // ── Validation ──────────────────────────────────────────────────
        ValidationResult vr = validator.validate(query);
        if (vr != ValidationResult.VALID) {
            queriesFailedValidation.incrementAndGet();
            logger.debug("Query id={} rejected: {}", transactionId, vr);
            ctx.writeAndFlush(buildErrorResponse(query, validator.toResponseCode(vr)));
            return;
        }

        // ── Extract EDNS0 client buffer size ────────────────────────────
        int clientBufferSize = extractClientBufferSize(query);

        // Retain query before async hop — SimpleChannelInboundHandler will
        // release it after this method returns, but we still need it inside
        // the callback.
        query.retain();

        // ── Forward to pipeline ─────────────────────────────────────────
        CompletableFuture<DnsResponse> future = pipeline.resolve(query);

        // Apply a timeout so a stuck pipeline doesn't leave the client hanging
        future.orTimeout(PIPELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((response, ex) -> {
                    try {
                        if (ex != null) {
                            if (ex instanceof java.util.concurrent.TimeoutException) {
                                queriesFailedTimeout.incrementAndGet();
                                logger.warn("Pipeline timeout for query id={}", transactionId);
                            } else {
                                queriesFailedError.incrementAndGet();
                                logger.error("Pipeline error for query id={}: {}",
                                        transactionId, ex.getMessage());
                            }
                            writeOnEventLoop(ctx, buildServfailResponse(query));
                        } else {
                            DatagramDnsResponse udpResponse = buildUdpResponse(query, response);
                            // Truncation check for UDP
                            applyTruncationIfNeeded(udpResponse, clientBufferSize);
                            logger.debug("Writing UDP response for id={}", transactionId);
                            writeOnEventLoop(ctx, udpResponse);
                        }
                    } finally {
                        query.release();
                    }
                });
    }

    // ── Response builders ───────────────────────────────────────────────

    /**
     * Builds a complete UDP response by copying records from the pipeline result.
     */
    private DatagramDnsResponse buildUdpResponse(DatagramDnsQuery query, DnsResponse pipelineResponse) {
        DatagramDnsResponse response = new DatagramDnsResponse(
                query.recipient(), query.sender(), query.id(), query.opCode(), pipelineResponse.code());

        response.setRecursionDesired(query.isRecursionDesired());
        response.setRecursionAvailable(true);
        response.setAuthoritativeAnswer(pipelineResponse.isAuthoritativeAnswer());

        // Copy records from the pipeline response
        copyRecords(pipelineResponse, response, DnsSection.ANSWER);
        copyRecords(pipelineResponse, response, DnsSection.AUTHORITY);
        copyRecords(pipelineResponse, response, DnsSection.ADDITIONAL);

        // Add our own EDNS0 OPT record if the client sent one
        if (hasEdnsOpt(query)) {
            addEdnsOptRecord(response);
        }

        return response;
    }

    /**
     * Builds an error response for a query that failed validation.
     */
    private DatagramDnsResponse buildErrorResponse(DatagramDnsQuery query, DnsResponseCode code) {
        DatagramDnsResponse response = new DatagramDnsResponse(
                query.recipient(), query.sender(), query.id(), query.opCode(), code);
        response.setRecursionDesired(query.isRecursionDesired());
        response.setRecursionAvailable(true);
        return response;
    }

    /**
     * Builds a SERVFAIL response for pipeline failures.
     */
    private DatagramDnsResponse buildServfailResponse(DatagramDnsQuery query) {
        return buildErrorResponse(query, DnsResponseCode.SERVFAIL);
    }

    // ── EDNS0 helpers ───────────────────────────────────────────────────

    /**
     * Extracts the client's advertised UDP payload size from the EDNS0 OPT record.
     * Returns {@link #DEFAULT_UDP_PAYLOAD_SIZE} (512) if no OPT record is present.
     */
    private int extractClientBufferSize(DatagramDnsQuery query) {
        int additionalCount = query.count(DnsSection.ADDITIONAL);
        for (int i = 0; i < additionalCount; i++) {
            DnsRecord record = query.recordAt(DnsSection.ADDITIONAL, i);
            if (record.type() == DnsRecordType.OPT) {
                // The OPT record's "class" field holds the UDP payload size
                return record.dnsClass();
            }
        }
        return DEFAULT_UDP_PAYLOAD_SIZE;
    }

    /**
     * Checks whether the query contains an EDNS0 OPT record.
     */
    private boolean hasEdnsOpt(DatagramDnsQuery query) {
        int additionalCount = query.count(DnsSection.ADDITIONAL);
        for (int i = 0; i < additionalCount; i++) {
            DnsRecord record = query.recordAt(DnsSection.ADDITIONAL, i);
            if (record.type() == DnsRecordType.OPT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds an EDNS0 OPT pseudo-record to the response's additional section.
     */
    private void addEdnsOptRecord(DatagramDnsResponse response) {
        // OPT record: name="", type=OPT(41), class=udpPayloadSize, ttl=extRcode+version+flags
        DefaultDnsRawRecord opt = new DefaultDnsRawRecord(
                "", DnsRecordType.OPT, SERVER_UDP_PAYLOAD_SIZE, 0,
                io.netty.buffer.Unpooled.EMPTY_BUFFER);
        response.addRecord(DnsSection.ADDITIONAL, opt);
    }

    /**
     * Sets the TC (truncated) bit if the response exceeds the client's buffer size.
     * In a production system we would strip records; here we set the flag so the
     * client retries over TCP.
     */
    private void applyTruncationIfNeeded(DatagramDnsResponse response, int maxSize) {
        // Rough size estimation: 12 (header) + records.
        // Netty will handle actual encoding; we use a conservative estimate.
        int estimatedSize = 12; // DNS header
        estimatedSize += estimateRecordsSize(response, DnsSection.QUESTION);
        estimatedSize += estimateRecordsSize(response, DnsSection.ANSWER);
        estimatedSize += estimateRecordsSize(response, DnsSection.AUTHORITY);
        estimatedSize += estimateRecordsSize(response, DnsSection.ADDITIONAL);

        if (estimatedSize > maxSize) {
            response.setTruncated(true);
            logger.debug("Response truncated: estimated={}B, max={}B", estimatedSize, maxSize);
        }
    }

    /**
     * Rough size estimate for all records in a section.
     */
    private int estimateRecordsSize(DnsResponse response, DnsSection section) {
        int size = 0;
        int count = response.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord record = response.recordAt(section, i);
            // name + type(2) + class(2) + ttl(4) + rdlength(2) + conservative rdata
            size += record.name().length() + 2 + 10 + 16;
        }
        return size;
    }

    // ── Utility ─────────────────────────────────────────────────────────

    /**
     * Copies DNS records from one response to another, retaining reference counts.
     */
    private void copyRecords(DnsResponse from, DatagramDnsResponse to, DnsSection section) {
        int count = from.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord record = from.recordAt(section, i);
            if (record instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) record).retain();
            }
            to.addRecord(section, record);
        }
    }

    /**
     * Ensures writeAndFlush executes on the channel's EventLoop thread.
     */
    private void writeOnEventLoop(ChannelHandlerContext ctx, Object msg) {
        Runnable writeTask = () -> {
            ctx.writeAndFlush(msg).addListener(future -> {
                if (!future.isSuccess()) {
                    logger.error("Failed to write UDP response: ", future.cause());
                }
            });
        };

        if (ctx.channel().eventLoop().inEventLoop()) {
            writeTask.run();
        } else {
            ctx.channel().eventLoop().execute(writeTask);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in UDP DNS handler: {}", cause.getMessage(), cause);
        // Never close — UDP shares a single channel for all clients
    }

    // ── Metric accessors for Person 4 ───────────────────────────────────

    public long getQueriesReceived()         { return queriesReceived.get(); }
    public long getQueriesFailedValidation() { return queriesFailedValidation.get(); }
    public long getQueriesFailedTimeout()    { return queriesFailedTimeout.get(); }
    public long getQueriesFailedError()      { return queriesFailedError.get(); }
}
