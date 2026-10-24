package com.behsazan.schemaforge.conformance;

import java.util.List;

/** The actual rule families executed by SchemaConformanceAuditService; not a selectable simulation. */
public final class SchemaConformanceRuleCatalog {
    private SchemaConformanceRuleCatalog() { }

    public record Rule(String id, String description) { }

    public static final List<Rule> RULES = List.of(
            new Rule(SchemaConformanceAuditService.STRUCTURAL,
                    "Existing SchemaForge structural validation of live-catalog-derived objects."),
            new Rule(SchemaConformanceAuditService.METADATA_CONVENTION,
                    "Existing metadata convention checks against live database schema and tables."),
            new Rule(SchemaConformanceAuditService.DATATYPE_COMPATIBILITY,
                    "Datatype compatibility with the selected DBMS dialect."),
            new Rule(SchemaConformanceAuditService.CONSTRAINT_REFERENCES,
                    "CHECK expression references to columns that exist on the table."),
            new Rule(SchemaConformanceAuditService.KEY_CONSTRAINTS,
                    "Primary-key presence and reported nullability of primary-key columns."),
            new Rule(SchemaConformanceAuditService.REFERENTIAL_INTEGRITY,
                    "Foreign-key target columns and supporting unique/primary keys."),
            new Rule(SchemaConformanceAuditService.INDEX_COVERAGE,
                    "Index coverage of foreign-key columns (informational findings)."),
            new Rule(SchemaConformanceAuditService.PHYSICAL_NAMING,
                    "DBMS-aware physical object naming restrictions and conventions."));
}
