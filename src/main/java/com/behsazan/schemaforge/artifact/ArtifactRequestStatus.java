package com.behsazan.schemaforge.artifact;

/**
 * Aggregate outcome of one SchemaForge artifact-generation request.
 *
 * <p>SUCCESS and PARTIAL_SUCCESS describe requests that completed far enough
 * to produce their final package/manifest contract. FAILED represents a
 * request-level failure where a trustworthy final result cannot be produced.</p>
 */
public enum ArtifactRequestStatus {

    /**
     * The request completed successfully and contains no blocked or failed artifacts.
     */
    SUCCESS,

    /**
     * The request completed and its package/manifest were produced, but one or more
     * requested artifacts were blocked or failed while other valid output was preserved.
     */
    PARTIAL_SUCCESS,

    /**
     * The request itself failed and a trustworthy completed result cannot be produced.
     */
    FAILED
}