package com.resolver.upstream;

/**
 * Thrown when an upstream DoH provider returns an error response or the
 * request cannot be completed due to a network or protocol fault.
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
