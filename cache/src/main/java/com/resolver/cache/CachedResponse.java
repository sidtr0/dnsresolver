package com.resolver.cache;

import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsPtrRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class CachedResponse {
    private final DnsResponse response;
    private final Instant fetchedAt;
    private final long originalTtlSeconds;
    private final AtomicInteger queryCount = new AtomicInteger(1);
    private final boolean isNegative;

    public CachedResponse(DnsResponse response, long originalTtlSeconds, boolean isNegative) {
        this.response = response;
        this.fetchedAt = Instant.now();
        this.originalTtlSeconds = originalTtlSeconds;
        this.isNegative = isNegative;
    }

    public long getOriginalTtlSeconds() {
        return originalTtlSeconds;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public boolean isNegative() {
        return isNegative;
    }

    public long getRemainingTtl() {
        long elapsedSeconds = Duration.between(fetchedAt, Instant.now()).toSeconds();
        return Math.max(0, originalTtlSeconds - elapsedSeconds);
    }

    public boolean isExpired() {
        return getRemainingTtl() <= 0;
    }

    public void incrementQueryCount() {
        queryCount.incrementAndGet();
    }

    public int getQueryCount() {
        return queryCount.get();
    }

    public DnsResponse getResponseWithAdjustedTtl(int id) {
        long currentTtl = Math.max(1, getRemainingTtl()); // Don't return 0 for active queries unless actually dead

        // Sometimes for SWR it's technically 0 but returned as 1 to tell clients it's dying

        DefaultDnsResponse copied = new DefaultDnsResponse(id, response.opCode(), response.code());
        copied.setAuthoritativeAnswer(response.isAuthoritativeAnswer());
        copied.setRecursionDesired(response.isRecursionDesired());
        copied.setRecursionAvailable(response.isRecursionAvailable());
        copied.setZ(response.z());
        copied.setTruncated(response.isTruncated());

        // Copy Question
        for (int i = 0; i < response.count(DnsSection.QUESTION); i++) {
            copied.addRecord(DnsSection.QUESTION, response.recordAt(DnsSection.QUESTION, i));
        }

        // Copy Answers with adjusted TTL
        copyAndAdjustRecords(response, copied, DnsSection.ANSWER, currentTtl);
        copyAndAdjustRecords(response, copied, DnsSection.AUTHORITY, currentTtl);
        copyAndAdjustRecords(response, copied, DnsSection.ADDITIONAL, currentTtl);

        return copied;
    }

    private void copyAndAdjustRecords(DnsResponse src, DefaultDnsResponse dest, DnsSection section, long ttl) {
        for (int i = 0; i < src.count(section); i++) {
            DnsRecord record = src.recordAt(section, i);
            if (record instanceof DnsRawRecord) {
                DnsRawRecord raw = (DnsRawRecord) record;
                dest.addRecord(section, new DefaultDnsRawRecord(
                    raw.name(), raw.type(), raw.dnsClass(), ttl,
                    raw.content().retainedDuplicate() // Important: increment ref count for new response
                ));
            } else if (record instanceof DnsPtrRecord) {
                DnsPtrRecord ptr = (DnsPtrRecord) record;
                dest.addRecord(section, new DefaultDnsPtrRecord(
                    ptr.name(), ptr.dnsClass(), ttl, ptr.hostname()
                ));
            } else {
                // For other record types (including OPT), just add them as-is
                // OPT records in ADDITIONAL section are typically handled separately
                dest.addRecord(section, record);
            }
        }
    }
}
