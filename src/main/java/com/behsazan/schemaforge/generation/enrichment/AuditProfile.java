package com.behsazan.schemaforge.generation.enrichment;

import java.util.Locale;

/** Supported audit-column naming conventions for request-level enrichment. */
public enum AuditProfile {
    AUTO,
    CREATED_UPDATED,
    CREATED_LAST_MODIFIED;

    public static AuditProfile parse(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return AuditProfile.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported auditProfile: " + value
                            + ". Expected AUTO, CREATED_UPDATED, or CREATED_LAST_MODIFIED");
        }
    }
}
