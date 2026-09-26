package com.resolver.cache;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;

import java.util.Locale;

public record CacheKey(
    String normalizedDomainName,
    DnsRecordType recordType,
    int recordClass
) {
    public static CacheKey fromQuery(DnsQuery query) {
        if (query.count(DnsSection.QUESTION) == 0) {
            throw new IllegalArgumentException("Query has no question");
        }
        DnsQuestion q = query.recordAt(DnsSection.QUESTION, 0);
        String name = q.name().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".")) {
            name = name + ".";
        }
        return new CacheKey(name, q.type(), q.dnsClass());
    }
}
