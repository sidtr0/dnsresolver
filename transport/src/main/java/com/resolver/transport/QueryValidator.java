package com.resolver.transport;

import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;

import java.util.Set;

/**
 * Stateless validator for incoming DNS queries.
 *
 * Inspects each query for protocol compliance and returns a {@link ValidationResult}
 * indicating whether the query is valid or the specific reason it was rejected.
 * Validation covers opcode, question count, domain name syntax, and record type support.
 */
public class QueryValidator {

    /** Record types that we refuse to handle (zone transfers). */
    private static final Set<DnsRecordType> UNSUPPORTED_TYPES = Set.of(
            DnsRecordType.valueOf("AXFR"),   // Full zone transfer (252)
            DnsRecordType.valueOf("IXFR")    // Incremental zone transfer (251)
    );

    /**
     * Result of validating a DNS query.
     */
    public enum ValidationResult {
        VALID,
        INVALID_OPCODE,
        INVALID_QUESTION_COUNT,
        INVALID_DOMAIN_NAME,
        UNSUPPORTED_RECORD_TYPE
    }

    /**
     * Validates an incoming DNS query.
     *
     * @param query the DNS query to validate
     * @return {@link ValidationResult#VALID} if the query passes all checks,
     *         or the specific failure reason otherwise
     */
    public ValidationResult validate(DnsQuery query) {
        // Check 1: Only standard QUERY opcode is supported
        if (query.opCode() != DnsOpCode.QUERY) {
            return ValidationResult.INVALID_OPCODE;
        }

        // Check 2: Exactly one question required
        int qdcount = query.count(DnsSection.QUESTION);
        if (qdcount != 1) {
            return ValidationResult.INVALID_QUESTION_COUNT;
        }

        // Extract the single question
        DnsQuestion question = query.recordAt(DnsSection.QUESTION, 0);

        // Check 3: Domain name must be syntactically valid
        if (!isValidDomainName(question.name())) {
            return ValidationResult.INVALID_DOMAIN_NAME;
        }

        // Check 4: Record type must be supported (reject zone transfers)
        if (isUnsupportedType(question.type())) {
            return ValidationResult.UNSUPPORTED_RECORD_TYPE;
        }

        return ValidationResult.VALID;
    }

    /**
     * Maps a validation failure to the appropriate DNS response code.
     *
     * @param result the validation result (must not be {@link ValidationResult#VALID})
     * @return the corresponding DNS response code
     * @throws IllegalArgumentException if called with {@link ValidationResult#VALID}
     */
    public DnsResponseCode toResponseCode(ValidationResult result) {
        return switch (result) {
            case VALID -> throw new IllegalArgumentException("No error code for valid queries");
            case INVALID_OPCODE -> DnsResponseCode.NOTIMP;
            case INVALID_QUESTION_COUNT -> DnsResponseCode.FORMERR;
            case INVALID_DOMAIN_NAME -> DnsResponseCode.FORMERR;
            case UNSUPPORTED_RECORD_TYPE -> DnsResponseCode.NOTIMP;
        };
    }

    /**
     * Validates a DNS domain name according to RFC 1035 and RFC 952 rules.
     *
     * <ul>
     *   <li>Total length ≤ 253 characters (excluding trailing root dot)</li>
     *   <li>Each label ≤ 63 characters</li>
     *   <li>Labels contain only [a-zA-Z0-9-]</li>
     *   <li>Labels must not start or end with a hyphen</li>
     *   <li>Domain must not be empty</li>
     * </ul>
     *
     * @param name the domain name to validate (may have a trailing dot)
     * @return true if the name is valid
     */
    boolean isValidDomainName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // Netty often appends a trailing dot — strip it for validation
        String normalized = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;

        // The root zone "." normalizes to an empty string, which is valid
        if (normalized.isEmpty()) {
            return true;
        }

        // Total length check (excluding trailing dot)
        if (normalized.length() > 253) {
            return false;
        }

        String[] labels = normalized.split("\\.", -1);
        for (String label : labels) {
            if (!isValidLabel(label)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validates a single DNS label.
     */
    private boolean isValidLabel(String label) {
        if (label == null || label.isEmpty() || label.length() > 63) {
            return false;
        }

        // Must not start or end with a hyphen
        if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return false;
        }

        // Only alphanumeric and hyphen allowed
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether the given record type is one we refuse to handle.
     *
     * @param type the DNS record type
     * @return true if the type is unsupported (AXFR, IXFR)
     */
    boolean isUnsupportedType(DnsRecordType type) {
        return UNSUPPORTED_TYPES.contains(type);
    }
}
