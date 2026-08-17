package com.behsazan.schemaforge.metadata.validation;

/** One row in a physical metadata comparison report. */
public record PhysicalComparisonRow(
        String scope,
        String objectName,
        String property,
        String expectedValue,
        String actualValue,
        PhysicalComparisonStatus status,
        String note) {
}
