package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import com.resolver.transport.QueryValidator.ValidationResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.*;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles incoming TCP DNS queries.
 *
 * TCP DNS differs from UDP in several ways:
 * <ul>
 *   <li>Uses {@link DefaultDnsQuery}/{@link DefaultDnsResponse} (no datagram addresses).</li>
 *   <li>Framing (2-byte length prefix per RFC 1035 §4.2.2) is handled by
 *       {@code LengthFieldBasedFrameDecoder} and {@code LengthFieldPrepender}
 *       in the pipeline.</li>
 *   <li>No truncation needed — TCP supports up to 65,535-byte responses.</li>
 *   <li>Supports pipelined queries on a single connection.</li>
 *   <li>Idle connections are closed after a configurable timeout.</li>
 * </ul>
 */
public class TcpDnsHandler extends SimpleChannelInboundHandler<DefaultDnsQuery> {

    private static final Logger logger = LoggerFactory.getLogger(TcpDnsHandler.class);

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
     * Creates a TCP handler wired to the given pipeline.
     *
     * @param pipeline the resolution pipeline to forward valid queries to
     */
    public TcpDnsHandler(ResolutionPipeline pipeline) {
        this.pipeline = pipeline;
        this.validator = new QueryValidator();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DefaultDnsQuery query) {
        queriesReceived.incrementAndGet();
        int transactionId = query.id();

        if (logger.isDebugEnabled()) {
            String name = query.count(DnsSection.QUESTION) > 0
                    ? query.recordAt(DnsSection.QUESTION, 0).name() : "<none>";
            logger.debug("TCP query id={} name={}", transactionId, name);
        }

        // ── Validation ──────────────────────────────────────────────────
        ValidationResult vr = validator.validate(query);
        if (vr != ValidationResult.VALID) {
            queriesFailedValidation.incrementAndGet();
            logger.debug("TCP query id={} rejected: {}", transactionId, vr);
            ctx.writeAndFlush(buildErrorResponse(query, validator.toResponseCode(vr)));
            return;
        }

        // Retain before async hop
        query.retain();

        // ── Forward to pipeline ─────────────────────────────────────────
        CompletableFuture<DnsResponse> future = pipeline.resolve(query);

        future.orTimeout(PIPELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((response, ex) -> {
                    try {
                        if (ex != null) {
                            if (ex instanceof java.util.concurrent.TimeoutException) {
                                queriesFailedTimeout.incrementAndGet();
                                logger.warn("Pipeline timeout for TCP query id={}", transactionId);
                            } else {
                                queriesFailedError.incrementAndGet();
                                logger.error("Pipeline error for TCP query id={}: {}",
                                        transactionId, ex.getMessage());
                            }
                            writeOnEventLoop(ctx, buildServfailResponse(query));
                        } else {
                            DefaultDnsResponse tcpResponse = buildTcpResponse(query, response);
                            writeOnEventLoop(ctx, tcpResponse);
                        }
                    } finally {
                        query.release();
                    }
                });
    }

    // ── Response builders ───────────────────────────────────────────────

    /**
     * Builds a complete TCP response by copying records from the pipeline result.
     */
    private DefaultDnsResponse buildTcpResponse(DefaultDnsQuery query, DnsResponse pipelineResponse) {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id(), query.opCode(), pipelineResponse.code());

        response.setRecursionDesired(query.isRecursionDesired());
        response.setRecursionAvailable(true);
        response.setAuthoritativeAnswer(pipelineResponse.isAuthoritativeAnswer());

        // Copy all records from the pipeline response
        copyRecords(pipelineResponse, response, DnsSection.ANSWER);
        copyRecords(pipelineResponse, response, DnsSection.AUTHORITY);
        copyRecords(pipelineResponse, response, DnsSection.ADDITIONAL);

        return response;
    }

    /**
     * Builds an error response for a query that failed validation.
     */
    private DefaultDnsResponse buildErrorResponse(DefaultDnsQuery query, DnsResponseCode code) {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id(), query.opCode(), code);
        response.setRecursionDesired(query.isRecursionDesired());
        response.setRecursionAvailable(true);
        return response;
    }

    /**
     * Builds a SERVFAIL response for pipeline failures.
     */
    private DefaultDnsResponse buildServfailResponse(DefaultDnsQuery query) {
        return buildErrorResponse(query, DnsResponseCode.SERVFAIL);
    }

    // ── Record copy ─────────────────────────────────────────────────────

    /**
     * Copies DNS records between responses, retaining reference counts.
     */
    private void copyRecords(DnsResponse from, DefaultDnsResponse to, DnsSection section) {
        int count = from.count(section);
        for (int i = 0; i < count; i++) {
            DnsRecord record = from.recordAt(section, i);
            if (record instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) record).retain();
            }
            to.addRecord(section, record);
        }
    }

    // ── Idle timeout ────────────────────────────────────────────────────

    /**
     * Closes the TCP connection when it has been idle for too long.
     * The timeout duration is configured via {@code IdleStateHandler} in the
     * channel pipeline (typically 10 seconds for DNS).
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            logger.debug("TCP connection idle, closing: {}", ctx.channel().remoteAddress());
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    /**
     * Ensures writeAndFlush executes on the channel's EventLoop thread.
     */
    private void writeOnEventLoop(ChannelHandlerContext ctx, Object msg) {
        if (ctx.channel().eventLoop().inEventLoop()) {
            ctx.writeAndFlush(msg);
        } else {
            ctx.channel().eventLoop().execute(() -> ctx.writeAndFlush(msg));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in TCP DNS handler: {}", cause.getMessage(), cause);
        ctx.close();
    }

    // ── Metric accessors for Person 4 ───────────────────────────────────

    public long getQueriesReceived()         { return queriesReceived.get(); }
    public long getQueriesFailedValidation() { return queriesFailedValidation.get(); }
    public long getQueriesFailedTimeout()    { return queriesFailedTimeout.get(); }
    public long getQueriesFailedError()      { return queriesFailedError.get(); }
}
