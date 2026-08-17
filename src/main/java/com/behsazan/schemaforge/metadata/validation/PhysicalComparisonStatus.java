package com.behsazan.schemaforge.metadata.validation;

/** Status of one expected-vs-actual physical metadata property. */
public enum PhysicalComparisonStatus {
    MATCH,
    MISMATCH,
    NOT_SPECIFIED,
    NOT_AVAILABLE,
    REVIEW
}
