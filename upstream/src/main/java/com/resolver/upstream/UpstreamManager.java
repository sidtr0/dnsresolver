package com.resolver.upstream;

import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * High-availability upstream DNS resolver manager that routes queries across multiple
 * DoH providers using selectable routing strategies and active health checking.
 *
 * <h2>Routing Strategies</h2>
 * <ul>
 *   <li>{@link RoutingStrategy#LOWEST_LATENCY} — routes queries to the healthy provider with
 *       the lowest exponentially-weighted moving average (EWMA) latency.</li>
 *   <li>{@link RoutingStrategy#FAILOVER_CHAIN} — tries healthy providers in ascending order of
 *       priority; moves to the next provider only if the current one fails.</li>
 *   <li>{@link RoutingStrategy#PARALLEL_RACE} — sends the query to <em>all</em> healthy providers
 *       simultaneously and returns the first valid response (hedging against tail latency).</li>
 * </ul>
 *
 * <h2>Health Checking</h2>
 * <p>A background thread periodically probes any server marked {@link UpstreamServer.HealthState#UNHEALTHY}
 * with a lightweight DoH probe for {@code cloudflare.com (A)}. Once a probe succeeds, the provider
 * is restored to {@code HEALTHY}.
 */
public class UpstreamManager implements UpstreamResolver, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamManager.class);

    public enum RoutingStrategy { LOWEST_LATENCY, FAILOVER_CHAIN, PARALLEL_RACE }

    private final List<UpstreamServer> servers;
    private final DoHClient dohClient;
    private final RoutingStrategy strategy;
    private final ScheduledExecutorService healthCheckScheduler;

    /**
     * Creates an {@code UpstreamManager} with custom servers, client, and strategy.
     *
     * @param servers  list of upstream DoH servers
     * @param dohClient DoH HTTP client
     * @param strategy  routing strategy
     */
    public UpstreamManager(List<UpstreamServer> servers,
                          DoHClient dohClient,
                          RoutingStrategy strategy) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("At least one upstream server must be configured");
        }
        this.servers = List.copyOf(servers);
        this.dohClient = Objects.requireNonNull(dohClient, "dohClient must not be null");
        this.strategy  = Objects.requireNonNull(strategy,  "strategy must not be null");

        // Start background health-checker thread
        this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "doh-health-checker");
            t.setDaemon(true);
            return t;
        });

        // Run health checks every 10 seconds
        this.healthCheckScheduler.scheduleWithFixedDelay(
                this::runHealthChecks, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Forwards a DNS query to upstream provider(s) according to the configured routing strategy.
     *
     * @param query Netty DNS query
     * @return future completing with the decoded {@link DnsResponse}
     */
    @Override
    public CompletableFuture<DnsResponse> forward(DnsQuery query) {
        byte[] wireQuery = WireFormatEncoder.encode(query);

        List<UpstreamServer> healthy = servers.stream()
                .filter(UpstreamServer::isHealthy)
                .collect(Collectors.toList());

        if (healthy.isEmpty()) {
            // Fall back to all servers if every single one is marked unhealthy
            logger.warn("All upstream servers marked unhealthy; attempting fallback to full list");
            healthy = new ArrayList<>(servers);
        }

        switch (strategy) {
            case LOWEST_LATENCY:
                return executeLowestLatency(healthy, wireQuery);
            case FAILOVER_CHAIN:
                return executeFailoverChain(healthy, wireQuery);
            case PARALLEL_RACE:
                return executeParallelRace(healthy, wireQuery);
            default:
                throw new IllegalStateException("Unknown routing strategy: " + strategy);
        }
    }

    // --- Strategy Implementations ---

    private CompletableFuture<DnsResponse> executeLowestLatency(List<UpstreamServer> healthy,
                                                               byte[] wireQuery) {
        // Pick the healthy server with the smallest EWMA latency
        UpstreamServer best = healthy.stream()
                .min(Comparator.comparingLong(UpstreamServer::ewmaLatencyMs))
                .orElseThrow(() -> new AllUpstreamsUnavailableException("No upstream servers available"));

        return queryServer(best, wireQuery)
                .exceptionallyCompose(ex -> {
                    logger.warn("Primary lowest-latency server {} failed ({}); falling back to chain",
                            best.name(), ex.getMessage());
                    // Exclude the failed server and try the remaining healthy list
                    List<UpstreamServer> remaining = healthy.stream()
                            .filter(s -> s != best)
                            .collect(Collectors.toList());
                    if (remaining.isEmpty()) {
                        CompletableFuture<DnsResponse> failed = new CompletableFuture<>();
                        failed.completeExceptionally(new AllUpstreamsUnavailableException(
                                "All upstreams failed after lowest-latency attempt", ex));
                        return failed;
                    }
                    return executeFailoverChain(remaining, wireQuery);
                });
    }

    private CompletableFuture<DnsResponse> executeFailoverChain(List<UpstreamServer> healthy,
                                                               byte[] wireQuery) {
        List<UpstreamServer> ordered = healthy.stream()
                .sorted(Comparator.comparingInt(UpstreamServer::priority))
                .collect(Collectors.toList());

        return tryNextInChain(ordered, 0, wireQuery);
    }

    private CompletableFuture<DnsResponse> tryNextInChain(List<UpstreamServer> chain,
                                                         int index,
                                                         byte[] wireQuery) {
        if (index >= chain.size()) {
            CompletableFuture<DnsResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new AllUpstreamsUnavailableException(
                    "All upstream providers in failover chain failed"));
            return failed;
        }

        UpstreamServer server = chain.get(index);
        return queryServer(server, wireQuery)
                .exceptionallyCompose(ex -> {
                    logger.warn("Upstream {} (priority {}) failed: {}; trying next provider",
                            server.name(), server.priority(), ex.getMessage());
                    return tryNextInChain(chain, index + 1, wireQuery);
                });
    }

    private CompletableFuture<DnsResponse> executeParallelRace(List<UpstreamServer> healthy,
                                                              byte[] wireQuery) {
        CompletableFuture<DnsResponse> winner = new CompletableFuture<>();

        List<CompletableFuture<Void>> futures = healthy.stream()
                .map(server -> queryServer(server, wireQuery)
                        .thenAccept(response -> {
                            // Complete the winner future with the first response received
                            winner.complete(response);
                        })
                        .exceptionally(ex -> {
                            logger.debug("Server {} lost race or errored: {}", server.name(), ex.getMessage());
                            return null;
                        }))
                .collect(Collectors.toList());

        // If all futures complete without completing the winner, fail the winner
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!winner.isDone()) {
                        winner.completeExceptionally(new AllUpstreamsUnavailableException(
                                "All parallel DoH race requests failed"));
                    }
                });

        return winner;
    }

    // --- Helper: Dispatch query to single server and record metrics ---

    private CompletableFuture<DnsResponse> queryServer(UpstreamServer server, byte[] wireQuery) {
        long startTime = System.currentTimeMillis();

        return dohClient.query(server.uri(), wireQuery)
                .thenApply(responseBytes -> {
                    long latency = System.currentTimeMillis() - startTime;
                    server.recordSuccess(latency);
                    return (DnsResponse) WireFormatDecoder.decode(responseBytes);
                })
                .exceptionally(ex -> {
                    server.recordFailure();
                    throw (ex instanceof RuntimeException)
                            ? (RuntimeException) ex
                            : new UpstreamException("Query failed for " + server.name(), ex);
                });
    }

    // --- Active Health Checking ---

    private void runHealthChecks() {
        for (UpstreamServer server : servers) {
            if (!server.isHealthy()) {
                logger.debug("Running background health probe for unhealthy server: {}", server.name());
                probeServer(server);
            }
        }
    }

    private void probeServer(UpstreamServer server) {
        // Simple probe query: A query for cloudflare.com
        DefaultDnsQuery probeQuery = new DefaultDnsQuery(0, DnsOpCode.QUERY);
        probeQuery.addRecord(DnsSection.QUESTION,
                new DefaultDnsQuestion("cloudflare.com", DnsRecordType.A));

        try {
            byte[] wire = WireFormatEncoder.encode(probeQuery);
            dohClient.query(server.uri(), wire)
                    .thenAccept(bytes -> {
                        logger.info("Upstream server {} recovered — restored to HEALTHY", server.name());
                        server.markHealthy();
                    })
                    .exceptionally(ex -> {
                        logger.debug("Health probe for {} still failing: {}", server.name(), ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.debug("Failed to build health probe for {}: {}", server.name(), e.getMessage());
        }
    }

    @Override
    public void close() {
        healthCheckScheduler.shutdownNow();
    }

    public List<UpstreamServer> servers() {
        return servers;
    }
}
