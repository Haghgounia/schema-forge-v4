package com.behsazan.schemaforge.snapshot;

/**
 * Central version contract for persisted canonical schema snapshots.
 *
 * <p>The snapshot format is deliberately versioned independently from database dialects.
 * Bump {@link #SNAPSHOT_VERSION} only when the JSON contract becomes incompatible, bump
 * {@link #MODEL_VERSION} when the canonical domain semantics change, and bump
 * {@link #PARSER_VERSION} whenever Word parsing/normalization changes in a way that requires
 * rebuilding cached snapshots.</p>
 *
 * <p>There are intentionally two compatibility levels:</p>
 * <ul>
 *   <li><b>Contract compatibility</b>: snapshot/model versions are readable by the current
 *       canonical mapper. This is enough for a persisted JSON corpus that is itself the source.</li>
 *   <li><b>Cache compatibility</b>: contract compatibility plus the current parser version.
 *       This remains the strict rule for deciding whether a Word-derived cache can be reused
 *       instead of reparsing its source document.</li>
 * </ul>
 */
public final class CanonicalSnapshotVersions {
    public static final String SNAPSHOT_VERSION = "1.0";
    public static final String MODEL_VERSION = "4";
    public static final String PARSER_VERSION = "word-pipeline-v4-2026-08-29-legacy-constraint-key-routing-p7";

    private CanonicalSnapshotVersions() {
    }

    /** Returns whether the persisted JSON contract can be mapped into the current canonical model. */
    public static boolean contractCompatible(CanonicalSchemaSnapshot snapshot) {
        return snapshot != null
                && SNAPSHOT_VERSION.equals(snapshot.snapshotVersion())
                && MODEL_VERSION.equals(snapshot.modelVersion());
    }

    /** Returns whether a compatible snapshot was produced by the current parser semantics. */
    public static boolean parserCurrent(CanonicalSchemaSnapshot snapshot) {
        return snapshot != null && PARSER_VERSION.equals(snapshot.parserVersion());
    }

    /** Returns whether a Word-derived cache can be reused without reparsing its source document. */
    public static boolean cacheCompatible(CanonicalSchemaSnapshot snapshot) {
        return contractCompatible(snapshot) && parserCurrent(snapshot);
    }

    /** Rejects a snapshot whose JSON/model contract cannot be read by the current mapper. */
    public static void requireContractCompatible(CanonicalSchemaSnapshot snapshot) {
        if (!contractCompatible(snapshot)) {
            throw new IllegalArgumentException("Unsupported canonical snapshot contract: snapshot="
                    + value(snapshot == null ? null : snapshot.snapshotVersion())
                    + ", model=" + value(snapshot == null ? null : snapshot.modelVersion())
                    + ", parser=" + value(snapshot == null ? null : snapshot.parserVersion()));
        }
    }

    /** Rejects a stale cache before it is reused instead of reparsing its Word source. */
    public static void requireCompatible(CanonicalSchemaSnapshot snapshot) {
        if (!cacheCompatible(snapshot)) {
            throw new IllegalArgumentException("Unsupported canonical snapshot cache version: snapshot="
                    + value(snapshot == null ? null : snapshot.snapshotVersion())
                    + ", model=" + value(snapshot == null ? null : snapshot.modelVersion())
                    + ", parser=" + value(snapshot == null ? null : snapshot.parserVersion()));
        }
    }

    private static String value(String value) {
        return value == null ? "<null>" : value;
    }
}
