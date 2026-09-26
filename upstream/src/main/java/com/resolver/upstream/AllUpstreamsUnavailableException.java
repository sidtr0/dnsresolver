package com.resolver.upstream;

/**
 * Thrown when all configured upstream DoH providers are unavailable or have
 * been marked unhealthy by the circuit-breaker, leaving no viable option
 * to forward the DNS query.
 */
public class AllUpstreamsUnavailableException extends RuntimeException {

    public AllUpstreamsUnavailableException(String message) {
        super(message);
    }

    public AllUpstreamsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
