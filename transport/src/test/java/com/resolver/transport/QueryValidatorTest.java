package com.resolver.transport;

import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryValidatorTest {

    private QueryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QueryValidator();
    }

    @Test
    void testValidAQuery() {
        DnsQuery query = buildQuery(DnsOpCode.QUERY, "google.com", 1, DnsRecordType.A);
        assertEquals(QueryValidator.ValidationResult.VALID, validator.validate(query));
    }

    @Test
    void testValidAAAAQuery() {
        DnsQuery query = buildQuery(DnsOpCode.QUERY, "example.com", 1, DnsRecordType.AAAA);
        assertEquals(QueryValidator.ValidationResult.VALID, validator.validate(query));
    }

    @Test
    void testInvalidOpcode() {
        // UPDATE instead of QUERY
        DnsQuery query = buildQuery(DnsOpCode.UPDATE, "google.com", 1, DnsRecordType.A);
        assertEquals(QueryValidator.ValidationResult.INVALID_OPCODE, validator.validate(query));
    }

    @Test
    void testInvalidQuestionCount() {
        DnsQuery query = buildQuery(DnsOpCode.QUERY, "google.com", 2, DnsRecordType.A);
        assertEquals(QueryValidator.ValidationResult.INVALID_QUESTION_COUNT, validator.validate(query));

        DnsQuery empty = buildQuery(DnsOpCode.QUERY, "google.com", 0, DnsRecordType.A);
        assertEquals(QueryValidator.ValidationResult.INVALID_QUESTION_COUNT, validator.validate(empty));
    }

    @Test
    void testUnsupportedRecordTypeAXFR() {
        DnsQuery query = buildQuery(DnsOpCode.QUERY, "google.com", 1, DnsRecordType.valueOf("AXFR"));
        assertEquals(QueryValidator.ValidationResult.UNSUPPORTED_RECORD_TYPE, validator.validate(query));
    }

    @Test
    void testDomainNameValidation() {
        // Valid
        assertTrue(validator.isValidDomainName("example.com"));
        assertTrue(validator.isValidDomainName("example.com.")); // trailing dot okay
        assertTrue(validator.isValidDomainName("sub-domain.example.com"));
        assertTrue(validator.isValidDomainName(".")); // root okay

        // Invalid lengths / empty
        assertFalse(validator.isValidDomainName(""));
        assertFalse(validator.isValidDomainName("a".repeat(64) + ".com")); // label limit (63)
        assertFalse(validator.isValidDomainName("a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + "." + "a".repeat(63) + ".com")); // total limit (253)

        // Invalid chars
        assertFalse(validator.isValidDomainName("-example.com"));
        assertFalse(validator.isValidDomainName("example-.com"));
        assertFalse(validator.isValidDomainName("exam!ple.com"));
    }

    private DnsQuery buildQuery(DnsOpCode opCode, String name, int qdcount, DnsRecordType type) {
        DefaultDnsQuery query = new DefaultDnsQuery(1, opCode);
        for (int i = 0; i < qdcount; i++) {
            query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, type));
        }
        return query;
    }
}
