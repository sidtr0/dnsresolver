package com.resolver.upstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asynchronous DNS-over-HTTPS (DoH) client using Java's built-in {@link HttpClient}
 * with HTTP/2 multiplexing.
 *
 * <p>Sends RFC 8484 binary DNS messages via HTTP POST with
 * {@code Content-Type: application/dns-message} and returns the raw response bytes.
 *
 * <p>A single {@code HttpClient} instance is shared across all queries to benefit
 * from HTTP/2 connection pooling and stream multiplexing.
 */
public class DoHClient {

    private static final Logger logger = LoggerFactory.getLogger(DoHClient.class);

    private static final String CONTENT_TYPE_DNS = "application/dns-message";

    private final HttpClient httpClient;
    private final Duration timeout;

    /**
     * Creates a new DoH client.
     *
     * @param executor the executor for async HTTP operations
     * @param timeout  connection and request timeout
     */
    public DoHClient(Executor executor, Duration timeout) {
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(timeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Sends a DNS wire-format message to the specified DoH upstream URI.
     *
     * @param upstreamUri    the DoH endpoint (e.g., {@code https://1.1.1.1/dns-query})
     * @param dnsWireMessage the RFC 1035 encoded DNS query bytes
     * @return a future completing with the raw DNS response bytes
     */
    public CompletableFuture<byte[]> query(URI upstreamUri, byte[] dnsWireMessage) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(upstreamUri)
                .version(HttpClient.Version.HTTP_2)
                .timeout(timeout)
                .header("Content-Type", CONTENT_TYPE_DNS)
                .header("Accept", CONTENT_TYPE_DNS)
                .POST(HttpRequest.BodyPublishers.ofByteArray(dnsWireMessage))
                .build();

        logger.debug("Sending DoH query to {} ({} bytes)", upstreamUri, dnsWireMessage.length);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 200) {
                        logger.debug("DoH response from {}: {} bytes",
                                upstreamUri, response.body().length);
                        return response.body();
                    } else if (status == 429) {
                        throw new UpstreamException(
                                "Rate limited by upstream: " + upstreamUri);
                    } else {
                        throw new UpstreamException(
                                "HTTP error " + status + " from upstream: " + upstreamUri);
                    }
                });
    }
}
