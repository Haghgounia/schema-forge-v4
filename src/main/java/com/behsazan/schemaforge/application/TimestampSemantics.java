package com.behsazan.schemaforge.application;

import java.util.Locale;

/**
 * Request-level semantic choice for source columns declared as an unqualified TIMESTAMP.
 *
 * <p>Explicit source qualifiers such as {@code TIMESTAMP WITH TIME ZONE} and
 * {@code TIMESTAMP WITH LOCAL TIME ZONE} remain authoritative and are never overwritten.</p>
 */
public enum TimestampSemantics {
    SOURCE("source", "TIMESTAMP"),
    WITHOUT_TIME_ZONE("without-time-zone", "TIMESTAMP"),
    WITH_TIME_ZONE("with-time-zone", "TIMESTAMP_WITH_TIME_ZONE"),
    WITH_LOCAL_TIME_ZONE("with-local-time-zone", "TIMESTAMP_WITH_LOCAL_TIME_ZONE");

    private final String requestValue;
    private final String canonicalTypeName;

    TimestampSemantics(String requestValue, String canonicalTypeName) {
        this.requestValue = requestValue;
        this.canonicalTypeName = canonicalTypeName;
    }

    public String requestValue() {
        return requestValue;
    }

    public String canonicalTypeName() {
        return canonicalTypeName;
    }

    public boolean rewritesUnqualifiedTimestamp() {
        return this == WITH_TIME_ZONE || this == WITH_LOCAL_TIME_ZONE;
    }

    public static TimestampSemantics parse(String raw) {
        if (raw == null || raw.isBlank()) return SOURCE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (TimestampSemantics value : values()) {
            if (value.requestValue.equals(normalized)) return value;
        }
        throw new IllegalArgumentException(
                "Unsupported timestampSemantics: " + raw
                        + ". Expected source, without-time-zone, with-time-zone, or with-local-time-zone");
    }
}
