package com.behsazan.schemaforge.snapshot;

/**
 * Central version contract for persisted canonical schema snapshots.
 *
 * <p>The snapshot format is deliberately versioned independently from database dialects.
 * Bump {@link #SNAPSHOT_VERSION} only when the JSON contract becomes incompatible, bump
 * {@link #MODEL_VERSION} when the canonical domain semantics change, and bump
 * {@link #PARSER_VERSION} whenever Word parsing/normalization changes in a way that requires
 * rebuilding cached snapshots.</p>
 */
public final class CanonicalSnapshotVersions {
    public static final String SNAPSHOT_VERSION = "1.0";
    public static final String MODEL_VERSION = "4";
    public static final String PARSER_VERSION = "word-pipeline-v4-2026-08-08";

    private CanonicalSnapshotVersions() {
    }

    /** Returns whether a snapshot can be reused without reparsing its source document. */
    public static boolean cacheCompatible(CanonicalSchemaSnapshot snapshot) {
        return snapshot != null
                && SNAPSHOT_VERSION.equals(snapshot.snapshotVersion())
                && MODEL_VERSION.equals(snapshot.modelVersion())
                && PARSER_VERSION.equals(snapshot.parserVersion());
    }

    /** Rejects an incompatible snapshot before it is mapped back into the domain model. */
    public static void requireCompatible(CanonicalSchemaSnapshot snapshot) {
        if (!cacheCompatible(snapshot)) {
            throw new IllegalArgumentException("Unsupported canonical snapshot version: snapshot="
                    + value(snapshot == null ? null : snapshot.snapshotVersion())
                    + ", model=" + value(snapshot == null ? null : snapshot.modelVersion())
                    + ", parser=" + value(snapshot == null ? null : snapshot.parserVersion()));
        }
    }

    private static String value(String value) {
        return value == null ? "<null>" : value;
    }
}
