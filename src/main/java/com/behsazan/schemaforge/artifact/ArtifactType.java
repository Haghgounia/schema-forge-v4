package com.behsazan.schemaforge.artifact;

/**
 * Source-derived semantic families of artifacts currently emitted by SchemaForge V4.
 *
 * <p>Diagram variants such as conceptual, dependency, clustered, compact, and overview diagrams
 * remain Mermaid or Graphviz artifacts; their finer role is intentionally not encoded as a new
 * top-level artifact type in Contract V1.</p>
 */
public enum ArtifactType {
    DDL,
    MIGRATION,
    CRUD,
    CANONICAL_JSON,
    COMPARISON_WORKBOOK,
    MERMAID_DIAGRAM,
    GRAPHVIZ_DIAGRAM,
    MANIFEST,
    RUN_SCRIPT,
    SUMMARY_REPORT,
    ERROR_REPORT,
    ISSUE_REPORT
}
