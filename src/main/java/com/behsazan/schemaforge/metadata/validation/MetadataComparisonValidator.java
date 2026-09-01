package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.NumericTypeEquivalenceService;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataTypeFrequency;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Compares document definitions against database metadata. */
public final class MetadataComparisonValidator {
    private static final Set<String> S_ENDING_WORDS = Set.of(
            "STATUS", "SUCCESS", "ADDRESS", "PROCESS", "CLASS", "BUSINESS", "ACCESS",
            "ANALYSIS", "BASIS", "CRISIS", "DIAGNOSIS", "EMPHASIS", "THESIS");
    private static final Set<String> VALID_NON_S_PLURAL_TABLE_WORDS = Set.of(
            "DATA", "METADATA", "INFORMATION");

    private final Dialect dialect;
    private final MetadataRepository repository;
    private final NumericTypeEquivalenceService typeEquivalence = new NumericTypeEquivalenceService();

    public MetadataComparisonValidator(Dialect dialect, MetadataRepository repository) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public MetadataComparisonResult validate(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        DatabaseSchema normalizedSchema = new SpecificationNormalizer().normalize(schema);
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Long> frequencies = new LinkedHashMap<>();
        Map<String, String> resolvedForeignKeySchemas = new LinkedHashMap<>();
        Map<String, Boolean> schemaExistence = new LinkedHashMap<>();
        Set<String> columnNames = normalizedSchema.tables().stream()
                .flatMap(table -> table.columns().stream())
                .map(column -> column.name().value().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean metadataAvailable = repository.available();
        if (metadataAvailable && repository.schemaExistenceAuthoritative()) {
            metadataAvailable = inspectSchemaExistence(normalizedSchema, schemaExistence);
        }
        boolean allDocumentSchemasVerifiedMissing = repository.schemaExistenceAuthoritative()
                && !schemaExistence.isEmpty()
                && schemaExistence.values().stream().noneMatch(Boolean.TRUE::equals);
        Map<String, MetadataColumnProfile> profiles = metadataAvailable && !allDocumentSchemasVerifiedMissing
                ? repository.loadColumnProfiles(columnNames)
                : Map.of();
        metadataAvailable = metadataAvailable && repository.available();

        if (metadataAvailable && !allDocumentSchemasVerifiedMissing
                && repository.bulkTableSchemaReadOptimized()) {
            Set<String> locationNames = new LinkedHashSet<>();
            normalizedSchema.tables().forEach(table -> {
                locationNames.add(table.qualifiedName().name().value());
                table.foreignKeys().forEach(foreignKey ->
                        locationNames.add(foreignKey.referencedTable().name().value()));
            });
            repository.findTableSchemas(locationNames);
            metadataAvailable = repository.available();
        }

        for (Table table : normalizedSchema.tables()) {
            validateSingularColumnNames(table, issues);
            validateTableNames(table, issues);
            if (metadataAvailable) {
                metadataAvailable = validateTableLocation(table, issues, schemaExistence);
                if (metadataAvailable) {
                    metadataAvailable = validateForeignKeys(
                            table, issues, resolvedForeignKeySchemas, schemaExistence);
                }
            }
            for (Column column : table.columns()) {
                MetadataColumnProfile profile = profiles.get(MetadataColumnProfile.normalizeName(column.name().value()));
                String path = path(table, column);
                if (metadataAvailable) frequencies.put(path, profile == null ? 0L : profile.totalFrequency());
                if (profile == null) continue;
                String documentType = MetadataTypeFrequency.normalize(
                        dialect.sqlType(normalizedSchema, table, column));
                boolean knownType = profile.typeFrequencies().stream().anyMatch(item ->
                        typeEquivalence.equivalent(
                                dialect.name(), documentType, item.typeSignature(),
                                dialect.numericMappingStrategy()));
                if (!knownType) {
                    issues.add(new ValidationIssue("WARNING", "METADATA_DATATYPE_MISMATCH", path,
                            message(column.name().value(), documentType, profile)));
                }
            }
        }
        return new MetadataComparisonResult(issues, frequencies, resolvedForeignKeySchemas,
                schemaExistence, metadataAvailable);
    }

    private boolean inspectSchemaExistence(DatabaseSchema schema, Map<String, Boolean> schemaExistence) {
        Set<String> schemas = new LinkedHashSet<>();
        schema.tables().forEach(table -> schemas.add(table.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value())));
        schema.sequences().forEach(sequence -> schemas.add(sequence.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema.name().value())));
        schema.tables().forEach(table -> table.foreignKeys().forEach(foreignKey ->
                foreignKey.referencedTable().schemaName().ifPresent(identifier -> schemas.add(identifier.value()))));
        if (schemas.isEmpty()) schemas.add(schema.name().value());

        for (String schemaName : schemas) {
            boolean exists = repository.schemaExists(schemaName);
            if (!repository.available()) {
                return false;
            }
            schemaExistence.put(normalizeSchema(schemaName), exists);
        }
        return true;
    }

    private static String normalizeSchema(String schemaName) {
        return schemaName.trim().toUpperCase(Locale.ROOT);
    }

    private boolean validateTableLocation(Table table, List<ValidationIssue> issues,
                                          Map<String, Boolean> schemaExistence) {
        String tableName = table.qualifiedName().name().value();
        String schemaName = table.qualifiedName().schemaName().map(i -> i.value()).orElse(null);
        if (schemaName != null) {
            Boolean schemaExists = schemaExistence.get(normalizeSchema(schemaName));
            if (Boolean.FALSE.equals(schemaExists)) {
                issues.add(new ValidationIssue("WARNING", "SCHEMA_NOT_FOUND", tablePath(table),
                        "Schema " + schemaName + " does not exist in database metadata."));
                return true;
            }
        }
        List<String> schemas = repository.findTableSchemas(tableName);
        if (!repository.available()) {
            return false;
        }
        if (schemaName != null && !containsIgnoreCase(schemas, schemaName) && !schemas.isEmpty()) {
            issues.add(new ValidationIssue("WARNING", "TABLE_IN_DIFFERENT_SCHEMA", tablePath(table),
                    "Table " + tableName + " was not found in schema " + schemaName
                            + ", but exists in schema(s): " + String.join(", ", schemas) + "."));
        }
        return true;
    }

    private boolean validateForeignKeys(Table table, List<ValidationIssue> issues,
                                     Map<String, String> resolvedSchemas,
                                     Map<String, Boolean> schemaExistence) {
        String ownerSchema = table.qualifiedName().schemaName().map(i -> i.value()).orElse(null);
        for (ForeignKey fk : table.foreignKeys()) {
            String refTable = fk.referencedTable().name().value();
            String explicitSchema = fk.referencedTable().schemaName().map(i -> i.value()).orElse(null);
            String preferredSchema = explicitSchema != null ? explicitSchema : ownerSchema;
            String path = foreignKeyPath(table, fk);
            if (preferredSchema != null
                    && Boolean.FALSE.equals(schemaExistence.get(normalizeSchema(preferredSchema)))) {
                issues.add(new ValidationIssue("WARNING", "FK_TABLE_NOT_FOUND", path,
                        "Referenced table " + refTable + " cannot exist in missing schema "
                                + preferredSchema + "."));
                continue;
            }
            List<String> schemas = repository.findTableSchemas(refTable);
            if (!repository.available()) {
                return false;
            }

            if (schemas.isEmpty()) {
                issues.add(new ValidationIssue("WARNING", "FK_TABLE_NOT_FOUND", path,
                        "Referenced table " + refTable + " does not exist in database metadata."));
                continue;
            }
            if (preferredSchema != null && containsIgnoreCase(schemas, preferredSchema)) {
                resolvedSchemas.put(path, matchingValue(schemas, preferredSchema));
                continue;
            }
            if (schemas.size() == 1) {
                resolvedSchemas.put(path, schemas.get(0));
                issues.add(new ValidationIssue("WARNING", "FK_SCHEMA_RESOLVED", path,
                        "Referenced table " + refTable + " was resolved to schema " + schemas.get(0) + "."));
            } else {
                issues.add(new ValidationIssue("WARNING", "FK_SCHEMA_AMBIGUOUS", path,
                        "Referenced table " + refTable + " exists in multiple schemas: "
                                + String.join(", ", schemas) + "."));
            }
        }
        return true;
    }


    private static void validateTableNames(Table table, List<ValidationIssue> issues) {
        String tableName = table.qualifiedName().name().value();
        if (!looksLikePluralTableName(tableName)) {
            issues.add(new ValidationIssue("WARNING", "TABLE_NAME_NOT_PLURAL", tablePath(table),
                    "Table name " + tableName + " appears to be singular. Table names should be plural."));
        }
        for (ForeignKey fk : table.foreignKeys()) {
            String referencedTable = fk.referencedTable().name().value();
            if (!looksLikePluralTableName(referencedTable)) {
                issues.add(new ValidationIssue("WARNING", "TABLE_NAME_NOT_PLURAL", foreignKeyPath(table, fk),
                        "Referenced table " + referencedTable
                                + " appears to be singular. Table names should be plural."));
            }
        }
    }

    private static boolean looksLikePluralTableName(String identifier) {
        String[] parts = identifier.toUpperCase(Locale.ROOT).split("_");
        String word = parts[parts.length - 1];
        if (VALID_NON_S_PLURAL_TABLE_WORDS.contains(word)) return true;
        if (S_ENDING_WORDS.contains(word)) return false;
        return word.endsWith("S");
    }
    private static void validateSingularColumnNames(Table table, List<ValidationIssue> issues) {
        for (Column column : table.columns()) {
            List<String> pluralParts = new ArrayList<>();
            for (String part : column.name().value().toUpperCase(Locale.ROOT).split("_")) {
                if (looksPlural(part)) pluralParts.add(part);
            }
            if (!pluralParts.isEmpty()) {
                issues.add(new ValidationIssue("WARNING", "PLURAL_COLUMN_COMPONENT", path(table, column),
                        "Plural identifier component(s) detected: " + String.join(", ", pluralParts) + "."));
            }
        }
    }

    private static boolean looksPlural(String word) {
        if (word.length() < 4 || S_ENDING_WORDS.contains(word)) return false;
        if (word.endsWith("SS") || word.endsWith("US") || word.endsWith("IS")) return false;
        return word.endsWith("S");
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(target));
    }

    private static String matchingValue(List<String> values, String target) {
        return values.stream().filter(v -> v.equalsIgnoreCase(target)).findFirst().orElse(target);
    }

    private static String message(String columnName, String documentType, MetadataColumnProfile profile) {
        String knownTypes = profile.typeFrequencies().stream()
                .map(type -> type.typeSignature() + " [" + type.frequency() + "]")
                .collect(Collectors.joining(", "));
        return "Document type " + documentType + " differs from database metadata for " + columnName
                + ". Metadata frequencies: " + knownTypes + ". Total occurrences: "
                + profile.totalFrequency() + ".";
    }

    public static String path(Table table, Column column) {
        return "tables." + table.qualifiedName().name().value() + ".columns." + column.name().value();
    }

    public static String tablePath(Table table) {
        return "tables." + table.qualifiedName().name().value();
    }

    public static String foreignKeyPath(Table table, ForeignKey fk) {
        String name = fk.name() == null ? fk.columns().stream().map(i -> i.value()).collect(Collectors.joining("_"))
                : fk.name().value();
        return tablePath(table) + ".foreignKeys." + name;
    }
}
