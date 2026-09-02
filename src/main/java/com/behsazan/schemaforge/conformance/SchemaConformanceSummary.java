package com.behsazan.schemaforge.conformance;

/** Aggregate counts for one conformance audit. */
public record SchemaConformanceSummary(
        int tablesScanned,
        int columnsScanned,
        int errorCount,
        int warningCount,
        int infoCount,
        int findingCount,
        boolean compliant) {

    public SchemaConformanceSummary {
        if (tablesScanned < 0 || columnsScanned < 0 || errorCount < 0
                || warningCount < 0 || infoCount < 0 || findingCount < 0) {
            throw new IllegalArgumentException("conformance summary counts must not be negative");
        }
        if (findingCount != errorCount + warningCount + infoCount) {
            throw new IllegalArgumentException("findingCount must equal error + warning + info counts");
        }
        if (compliant != (errorCount == 0 && warningCount == 0)) {
            throw new IllegalArgumentException("compliant must be true only when there are no errors or warnings");
        }
    }
}
