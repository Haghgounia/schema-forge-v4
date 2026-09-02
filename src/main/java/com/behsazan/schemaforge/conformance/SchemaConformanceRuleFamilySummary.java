package com.behsazan.schemaforge.conformance;

/** Finding counts for one executed conformance rule family. */
public record SchemaConformanceRuleFamilySummary(
        String ruleFamily,
        int errorCount,
        int warningCount,
        int infoCount,
        int findingCount) {

    public SchemaConformanceRuleFamilySummary {
        if (ruleFamily == null || ruleFamily.isBlank()) {
            throw new IllegalArgumentException("ruleFamily must not be blank");
        }
        if (errorCount < 0 || warningCount < 0 || infoCount < 0 || findingCount < 0) {
            throw new IllegalArgumentException("rule-family counts must not be negative");
        }
        if (findingCount != errorCount + warningCount + infoCount) {
            throw new IllegalArgumentException("findingCount must equal error + warning + info counts");
        }
    }
}
