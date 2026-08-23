package com.behsazan.schemaforge.artifact;

/**
 * Top-level input/orchestration origin that caused an artifact generation attempt.
 *
 * <p>The origin is deliberately separate from the producer. For example, a comparison workbook
 * may originate from a Word request while being produced by the comparison subsystem.</p>
 */
public enum ArtifactOrigin {
    STANDARD_WORD,
    LEGACY_WORD,
    ZIP_BATCH,
    ENTERPRISE_ARCHITECT,
    CANONICAL_JSON,
    DATABASE_METADATA,
    INTERNAL
}
