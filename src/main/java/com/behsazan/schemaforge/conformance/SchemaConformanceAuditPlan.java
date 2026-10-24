package com.behsazan.schemaforge.conformance;

import java.util.List;

/** User-visible plan returned before any table/schema auditing starts. */
public record SchemaConformanceAuditPlan(
        String reportContract,
        SchemaConformanceSelectedTarget target,
        List<SchemaConformanceRuleCatalog.Rule> ruleFamilies,
        boolean readOnly,
        List<String> notIncluded) {
    public static final String CONTRACT = "schemaforge-schema-conformance-plan/v1";

    public SchemaConformanceAuditPlan {
        ruleFamilies = List.copyOf(ruleFamilies);
        notIncluded = List.copyOf(notIncluded);
    }
}
