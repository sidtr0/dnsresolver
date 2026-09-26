package com.resolver.upstream;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single upstream DoH provider with health-state tracking and
 * latency statistics computed via an exponentially weighted moving average (EWMA).
 *
 * <p>Health is modelled as a simple circuit-breaker:
 * <ul>
 *   <li>{@link HealthState#HEALTHY} — provider is accepting queries normally.</li>
 *   <li>{@link HealthState#UNHEALTHY} — three or more consecutive failures have been
 *       recorded; queries are not routed here until a successful health-check probe
 *       restores it to {@code HEALTHY}.</li>
 * </ul>
 *
 * <p>All mutable state is updated through atomic variables so multiple threads
 * (query threads + background health-checker) can operate concurrently without
 * explicit locking.
 */
public class UpstreamServer {

    /** Number of consecutive failures before the server is marked unhealthy. */
    private static final int FAILURE_THRESHOLD = 3;

    /** EWMA smoothing factor α — higher values weight recent samples more. */
    private static final double EWMA_ALPHA = 0.25;

    /** Initial latency estimate used before any real measurements arrive (ms). */
    private static final long INITIAL_LATENCY_MS = 100L;

    public enum HealthState { HEALTHY, UNHEALTHY }

    // --- Identity ---
    private final String name;
    private final URI uri;
    private final int priority;          // Lower number = higher priority for failover

    // --- Circuit-breaker state ---
    private final AtomicReference<HealthState> healthState =
            new AtomicReference<>(HealthState.HEALTHY);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong totalQueries  = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    // --- Latency tracking (EWMA in microseconds for precision) ---
    private final AtomicLong ewmaLatencyMs = new AtomicLong(INITIAL_LATENCY_MS);

    /**
     * Creates a new upstream server descriptor.
     *
     * @param name     human-readable label (e.g., "cloudflare")
     * @param uri      DoH endpoint URI (e.g., {@code https://1.1.1.1/dns-query})
     * @param priority failover priority; 1 = highest
     */
    public UpstreamServer(String name, URI uri, int priority) {
        this.name = name;
        this.uri  = uri;
        this.priority = priority;
    }

    // --- Accessors ---

    public String name()     { return name; }
    public URI    uri()      { return uri; }
    public int    priority() { return priority; }

    public boolean isHealthy() {
        return healthState.get() == HealthState.HEALTHY;
    }

    public HealthState healthState() {
        return healthState.get();
    }

    /** Returns the current EWMA latency estimate in milliseconds. */
    public long ewmaLatencyMs() {
        return ewmaLatencyMs.get();
    }

    public long totalQueries()  { return totalQueries.get(); }
    public long totalFailures() { return totalFailures.get(); }

    // --- State mutations ---

    /**
     * Records a successful query completion and updates the latency EWMA.
     *
     * @param latencyMs round-trip time in milliseconds
     */
    public void recordSuccess(long latencyMs) {
        totalQueries.incrementAndGet();
        consecutiveFailures.set(0);
        healthState.set(HealthState.HEALTHY);

        // Update EWMA atomically (best-effort; slight race is acceptable for a stat)
        long prev = ewmaLatencyMs.get();
        long next = Math.round(EWMA_ALPHA * latencyMs + (1.0 - EWMA_ALPHA) * prev);
        ewmaLatencyMs.compareAndSet(prev, next);
    }

    /**
     * Records a failed query and trips the circuit-breaker if consecutive
     * failures have reached {@link #FAILURE_THRESHOLD}.
     */
    public void recordFailure() {
        totalQueries.incrementAndGet();
        totalFailures.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            healthState.set(HealthState.UNHEALTHY);
        }
    }

    /**
     * Resets the circuit-breaker back to {@link HealthState#HEALTHY}.
     * Called by the background health-checker after a successful probe.
     */
    public void markHealthy() {
        consecutiveFailures.set(0);
        healthState.set(HealthState.HEALTHY);
    }

    @Override
    public String toString() {
        return name + "[" + uri + ", " + healthState.get() +
               ", latency=" + ewmaLatencyMs.get() + "ms]";
    }
}
