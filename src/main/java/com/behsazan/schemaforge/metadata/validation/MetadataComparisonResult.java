package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import java.util.List;
import java.util.Map;

/**
 * Represents the immutable metadata comparison result produced by the SchemaForge workflow.
 *
 * @since 4.1
 */
public record MetadataComparisonResult(List<ValidationIssue> issues,
                                       Map<String, Long> columnFrequencies,
                                       Map<String, String> resolvedForeignKeySchemas,
                                       boolean metadataAvailable) {
    public MetadataComparisonResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        columnFrequencies = columnFrequencies == null ? Map.of() : Map.copyOf(columnFrequencies);
        resolvedForeignKeySchemas = resolvedForeignKeySchemas == null ? Map.of() : Map.copyOf(resolvedForeignKeySchemas);
    }
    public MetadataComparisonResult(List<ValidationIssue> issues, Map<String, Long> frequencies, boolean available) {
        this(issues, frequencies, Map.of(), available);
    }
    public long frequency(String path) { return columnFrequencies.getOrDefault(path, 0L); }
    public String resolvedForeignKeySchema(String path) { return resolvedForeignKeySchemas.get(path); }
}
