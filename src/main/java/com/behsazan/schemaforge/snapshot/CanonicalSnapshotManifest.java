package com.behsazan.schemaforge.snapshot;

import java.util.List;

/** Audit manifest for one recursive Word-to-canonical-JSON snapshot run. */
public record CanonicalSnapshotManifest(
        String snapshotVersion,
        String modelVersion,
        String parserVersion,
        String generatedAtUtc,
        String inputDirectory,
        String outputDirectory,
        int documentsDiscovered,
        int snapshotsWritten,
        int cacheHits,
        int skippedNoTable,
        int failures,
        List<Entry> entries) {

    /** Per-source result recorded in {@code manifest.json}. */
    public record Entry(
            String source,
            String sha256,
            String snapshot,
            String status,
            String parserId,
            int tableCount,
            int columnCount,
            String error) {
    }
}
