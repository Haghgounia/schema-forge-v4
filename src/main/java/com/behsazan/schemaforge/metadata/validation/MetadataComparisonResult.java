package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Represents the immutable metadata comparison result produced by the SchemaForge workflow.
 *
 * <p>Verified schema existence is retained so DDL generation can omit schema-bootstrap
 * statements when the target schema is already present. Unknown/unavailable metadata is
 * deliberately different from a verified missing schema.</p>
 *
 * @since 4.1
 */
public record MetadataComparisonResult(List<ValidationIssue> issues,
                                       Map<String, Long> columnFrequencies,
                                       Map<String, String> resolvedForeignKeySchemas,
                                       Map<String, Boolean> schemaExistence,
                                       boolean metadataAvailable) {
    public MetadataComparisonResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        columnFrequencies = columnFrequencies == null ? Map.of() : Map.copyOf(columnFrequencies);
        resolvedForeignKeySchemas = resolvedForeignKeySchemas == null
                ? Map.of() : Map.copyOf(resolvedForeignKeySchemas);
        schemaExistence = normalizeSchemaExistence(schemaExistence);
    }

    public MetadataComparisonResult(List<ValidationIssue> issues,
                                    Map<String, Long> frequencies,
                                    Map<String, String> resolvedForeignKeySchemas,
                                    boolean available) {
        this(issues, frequencies, resolvedForeignKeySchemas, Map.of(), available);
    }

    public MetadataComparisonResult(List<ValidationIssue> issues,
                                    Map<String, Long> frequencies,
                                    boolean available) {
        this(issues, frequencies, Map.of(), Map.of(), available);
    }

    public long frequency(String path) {
        return columnFrequencies.getOrDefault(path, 0L);
    }

    public String resolvedForeignKeySchema(String path) {
        return resolvedForeignKeySchemas.get(path);
    }

    /** Returns true only when database metadata positively verified that the schema exists. */
    public boolean schemaKnownToExist(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        return Boolean.TRUE.equals(schemaExistence.get(normalizeSchema(schemaName)));
    }

    /** Returns true only when database metadata positively verified that the schema is missing. */
    public boolean schemaKnownToBeMissing(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        return Boolean.FALSE.equals(schemaExistence.get(normalizeSchema(schemaName)));
    }

    private static Map<String, Boolean> normalizeSchemaExistence(Map<String, Boolean> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        source.forEach((schema, exists) -> {
            if (schema != null && !schema.isBlank() && exists != null) {
                normalized.put(normalizeSchema(schema), exists);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalizeSchema(String schemaName) {
        return schemaName.trim().toUpperCase(Locale.ROOT);
    }
}
