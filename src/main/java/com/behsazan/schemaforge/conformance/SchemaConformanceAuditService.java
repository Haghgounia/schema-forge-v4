package com.behsazan.schemaforge.conformance;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.ServiceUnavailableException;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.repository.FailureIsolatingMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.validation.constraint.CheckConstraintReferenceAnalyzer;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;
import com.behsazan.schemaforge.validation.naming.PhysicalObjectNamingAnalyzer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only conformance audit for database objects that may have been created outside SchemaForge.
 *
 * <p>The service reads live metadata, converts it through the existing metadata repositories into
 * the canonical model, then reuses the existing structural, metadata, datatype, constraint-reference
 * and physical-name validation rules. It never executes DDL or mutates the target database.</p>
 */
@Service
public class SchemaConformanceAuditService {
    public static final String STRUCTURAL = "STRUCTURAL";
    public static final String METADATA_CONVENTION = "METADATA_CONVENTION";
    public static final String DATATYPE_COMPATIBILITY = "DATATYPE_COMPATIBILITY";
    public static final String CONSTRAINT_REFERENCES = "CONSTRAINT_REFERENCES";
    public static final String KEY_CONSTRAINTS = "KEY_CONSTRAINTS";
    public static final String REFERENTIAL_INTEGRITY = "REFERENTIAL_INTEGRITY";
    public static final String INDEX_COVERAGE = "INDEX_COVERAGE";
    public static final String PHYSICAL_NAMING = "PHYSICAL_NAMING";

    public static final List<String> TABLE_RULE_FAMILIES = List.of(
            STRUCTURAL,
            METADATA_CONVENTION,
            DATATYPE_COMPATIBILITY,
            CONSTRAINT_REFERENCES,
            KEY_CONSTRAINTS,
            REFERENTIAL_INTEGRITY,
            INDEX_COVERAGE,
            PHYSICAL_NAMING);

    private final MetadataRepositoryResolver repositoryResolver;
    private final SpecificationValidator specificationValidator = new SpecificationValidator();
    private final DatatypeCompatibilityAnalyzer datatypeCompatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();
    private final PhysicalObjectNamingAnalyzer physicalObjectNamingAnalyzer = new PhysicalObjectNamingAnalyzer();

    public SchemaConformanceAuditService(MetadataRepositoryResolver repositoryResolver) {
        this.repositoryResolver = Objects.requireNonNull(repositoryResolver, "repositoryResolver must not be null");
    }

    /** Audits one existing live table using metadata only. */
    public SchemaConformanceReport auditTable(DatabasePlatform platform, String schemaName, String tableName) {
        Objects.requireNonNull(platform, "platform must not be null");
        String schema = requireIdentifierText(schemaName, "schema");
        String table = requireIdentifierText(tableName, "table");

        MetadataRepository repository = repository(platform);
        java.util.Optional<Table> liveTableResult = repository.findTable(schema, table);
        ensureAvailable(repository, "reading " + schema + "." + table);
        Table liveTable = liveTableResult
                .orElseThrow(() -> new IllegalArgumentException(
                        "Live table was not found: " + schema + "." + table));

        String actualSchema = liveTable.qualifiedName().schemaName()
                .map(identifier -> identifier.value())
                .orElse(schema);
        DatabaseSchema canonical = DatabaseSchema.builder(actualSchema)
                .metadata("source.origin", "LIVE_DATABASE")
                .metadata("source.platform", platform.commandLineName())
                .addTable(liveTable)
                .build();

        List<FamilyIssue> issues = analyze(canonical, dialectFor(platform), repository);
        return report(platform, SchemaConformanceScope.TABLE, actualSchema,
                liveTable.qualifiedName().name().value(), TABLE_RULE_FAMILIES, canonical, issues);
    }

    /** Audits every existing live table in one schema using metadata only. */
    public SchemaConformanceReport auditSchema(DatabasePlatform platform, String schemaName) {
        Objects.requireNonNull(platform, "platform must not be null");
        String schema = requireIdentifierText(schemaName, "schema");

        MetadataRepository repository = repository(platform);
        if (repository.schemaExistenceAuthoritative() && !repository.schemaExists(schema)) {
            ensureAvailable(repository, "checking schema " + schema);
            throw new IllegalArgumentException("Live schema was not found: " + schema);
        }

        List<String> tableNames = repository.findTableNames(schema);
        ensureAvailable(repository, "listing schema " + schema);

        DatabaseSchema.Builder builder = DatabaseSchema.builder(schema)
                .metadata("source.origin", "LIVE_DATABASE")
                .metadata("source.platform", platform.commandLineName());
        if (!tableNames.isEmpty()) {
            java.util.Set<String> requested = new java.util.LinkedHashSet<>(tableNames);
            Map<String, Table> loaded = repository.findTables(schema, requested);
            for (String tableName : tableNames) {
                Table liveTable = loaded.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(tableName))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
                if (liveTable != null) builder.addTable(liveTable);
            }
            ensureAvailable(repository, "reading schema " + schema);
        }
        DatabaseSchema canonical = builder.build();

        Dialect dialect = dialectFor(platform);
        List<FamilyIssue> issues = new ArrayList<>();
        if (canonical.tables().isEmpty()) {
            issues.add(new FamilyIssue(STRUCTURAL, new ValidationIssue(
                    "WARNING", "SCHEMA_NO_TABLE", "schema",
                    "Schema contains no auditable base tables.")));
            addFamilyIssues(issues, METADATA_CONVENTION,
                    new MetadataComparisonValidator(dialect, repository).validate(canonical).issues());
            addFamilyIssues(issues, DATATYPE_COMPATIBILITY,
                    datatypeCompatibilityAnalyzer.analyze(canonical, dialect).issues());
            addFamilyIssues(issues, PHYSICAL_NAMING,
                    physicalObjectNamingAnalyzer.analyze(canonical, dialect));
        } else {
            issues.addAll(analyze(canonical, dialect, repository));
        }

        return report(platform, SchemaConformanceScope.SCHEMA, schema, null,
                TABLE_RULE_FAMILIES, canonical, issues);
    }

    private List<FamilyIssue> analyze(
            DatabaseSchema canonical, Dialect dialect, MetadataRepository repository) {
        List<FamilyIssue> issues = new ArrayList<>();
        addFamilyIssues(issues, STRUCTURAL, specificationValidator.validate(canonical).issues());
        addFamilyIssues(issues, METADATA_CONVENTION,
                new MetadataComparisonValidator(dialect, repository).validate(canonical).issues());
        addFamilyIssues(issues, DATATYPE_COMPATIBILITY,
                datatypeCompatibilityAnalyzer.analyze(canonical, dialect).issues());
        addFamilyIssues(issues, CONSTRAINT_REFERENCES, checkConstraintFindings(canonical));
        addFamilyIssues(issues, KEY_CONSTRAINTS, keyConstraintFindings(canonical));
        addFamilyIssues(issues, REFERENTIAL_INTEGRITY,
                referentialIntegrityFindings(canonical, repository));
        addFamilyIssues(issues, INDEX_COVERAGE, indexCoverageFindings(canonical));
        addFamilyIssues(issues, PHYSICAL_NAMING,
                physicalObjectNamingAnalyzer.analyze(canonical, dialect));
        return issues;
    }

    private static List<ValidationIssue> checkConstraintFindings(DatabaseSchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            table.checkConstraints().forEach(check -> {
                java.util.Set<String> unknown = CheckConstraintReferenceAnalyzer.unknownColumns(
                        table, check.expression());
                if (!unknown.isEmpty()) {
                    String checkName = check.name() == null ? "<unnamed>" : check.name().value();
                    issues.add(new ValidationIssue(
                            "ERROR",
                            "CHECK_UNKNOWN_COLUMN",
                            "tables." + table.qualifiedName().name().value()
                                    + ".checks." + checkName,
                            "Check constraint references unknown column(s): "
                                    + String.join(",", unknown) + "."));
                }
            });
        }
        return issues;
    }

    private static List<ValidationIssue> keyConstraintFindings(DatabaseSchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            String tablePath = "tables." + table.qualifiedName().name().value();
            if (table.primaryKey().isEmpty()) {
                issues.add(new ValidationIssue(
                        "WARNING",
                        "TABLE_PRIMARY_KEY_MISSING",
                        tablePath,
                        "Table has no primary key. SchemaForge recommends an explicit primary key for stable identity and referential design."));
                continue;
            }
            for (Identifier columnName : table.primaryKey().orElseThrow().columns()) {
                table.findColumn(columnName.value()).ifPresent(column -> {
                    if (column.nullable()) {
                        issues.add(new ValidationIssue(
                                "ERROR",
                                "PRIMARY_KEY_COLUMN_NULLABLE",
                                tablePath + ".primaryKey." + column.name().value(),
                                "Primary-key column is reported nullable by live metadata."));
                    }
                });
            }
        }
        return issues;
    }

    private static List<ValidationIssue> referentialIntegrityFindings(
            DatabaseSchema schema, MetadataRepository repository) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            String ownerSchema = table.qualifiedName().schemaName()
                    .map(Identifier::value)
                    .orElse(schema.name().value());
            for (ForeignKey foreignKey : table.foreignKeys()) {
                if (!foreignKey.physicalReference()) continue;
                String referencedSchema = foreignKey.referencedTable().schemaName()
                        .map(Identifier::value)
                        .orElse(ownerSchema);
                String referencedTableName = foreignKey.referencedTable().name().value();
                Table referencedTable = findCanonicalTable(schema, referencedSchema, referencedTableName);
                if (referencedTable == null) {
                    referencedTable = repository.findTable(referencedSchema, referencedTableName).orElse(null);
                    if (!repository.available()) {
                        continue;
                    }
                }
                if (referencedTable == null) {
                    // MetadataComparisonValidator already owns missing/ambiguous target-table findings.
                    continue;
                }
                Table targetTable = referencedTable;

                String path = "tables." + table.qualifiedName().name().value()
                        + ".foreignKeys." + foreignKeyName(foreignKey);
                List<String> missing = foreignKey.referencedColumns().stream()
                        .filter(column -> targetTable.findColumn(column.value()).isEmpty())
                        .map(Identifier::value)
                        .toList();
                if (!missing.isEmpty()) {
                    issues.add(new ValidationIssue(
                            "ERROR",
                            "FK_REFERENCED_COLUMN_NOT_FOUND",
                            path,
                            "Referenced column(s) " + String.join(",", missing)
                                    + " were not found in " + referencedSchema + "." + referencedTableName + "."));
                    continue;
                }
                if (!referencesUniqueTarget(targetTable, foreignKey.referencedColumns())) {
                    issues.add(new ValidationIssue(
                            "ERROR",
                            "FK_TARGET_NOT_UNIQUE",
                            path,
                            "Referenced column set (" + identifierList(foreignKey.referencedColumns())
                                    + ") is not backed by a primary key, unique key, or unique index on "
                                    + referencedSchema + "." + referencedTableName + "."));
                }
            }
        }
        return issues;
    }

    private static List<ValidationIssue> indexCoverageFindings(DatabaseSchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            for (ForeignKey foreignKey : table.foreignKeys()) {
                if (!foreignKey.physicalReference() || hasSupportingIndex(table, foreignKey.columns())) {
                    continue;
                }
                issues.add(new ValidationIssue(
                        "INFO",
                        "PHYS-FK-INDEX-001",
                        "tables." + table.qualifiedName().name().value()
                                + ".foreignKeys." + foreignKeyName(foreignKey),
                        "Foreign key has no supporting index whose leading columns match ("
                                + identifierList(foreignKey.columns()) + ")."));
            }
        }
        return issues;
    }

    private static Table findCanonicalTable(
            DatabaseSchema schema, String schemaName, String tableName) {
        for (Table table : schema.tables()) {
            String candidateSchema = table.qualifiedName().schemaName()
                    .map(Identifier::value)
                    .orElse(schema.name().value());
            if (candidateSchema.equalsIgnoreCase(schemaName)
                    && table.qualifiedName().name().value().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    private static boolean referencesUniqueTarget(Table table, List<Identifier> referencedColumns) {
        if (table.primaryKey().isPresent()
                && sameColumnSet(table.primaryKey().orElseThrow().columns(), referencedColumns)) {
            return true;
        }
        if (table.uniqueKeys().stream()
                .anyMatch(unique -> sameColumnSet(unique.columns(), referencedColumns))) {
            return true;
        }
        return table.indexes().stream()
                .filter(index -> index.type() == IndexType.UNIQUE)
                .map(index -> index.columns().stream()
                        .filter(column -> !column.expressionBased())
                        .map(IndexColumn::column)
                        .toList())
                .anyMatch(columns -> sameColumnSet(columns, referencedColumns));
    }

    private static boolean sameColumnSet(List<Identifier> left, List<Identifier> right) {
        if (left.size() != right.size()) return false;
        java.util.Set<String> leftNames = left.stream()
                .map(Identifier::normalized)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Set<String> rightNames = right.stream()
                .map(Identifier::normalized)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return leftNames.equals(rightNames);
    }

    private static boolean hasSupportingIndex(Table table, List<Identifier> foreignKeyColumns) {
        if (table.primaryKey().isPresent()
                && leadingColumnsMatch(table.primaryKey().orElseThrow().columns(), foreignKeyColumns)) {
            return true;
        }
        if (table.uniqueKeys().stream()
                .anyMatch(unique -> leadingColumnsMatch(unique.columns(), foreignKeyColumns))) {
            return true;
        }
        for (var index : table.indexes()) {
            List<Identifier> indexColumns = new ArrayList<>();
            boolean expressionBeforeMatchBoundary = false;
            for (IndexColumn indexColumn : index.columns()) {
                if (indexColumn.expressionBased()) {
                    expressionBeforeMatchBoundary = true;
                    break;
                }
                indexColumns.add(indexColumn.column());
                if (indexColumns.size() >= foreignKeyColumns.size()) break;
            }
            if (!expressionBeforeMatchBoundary
                    && leadingColumnsMatch(indexColumns, foreignKeyColumns)) {
                return true;
            }
        }
        return false;
    }

    private static boolean leadingColumnsMatch(List<Identifier> candidate, List<Identifier> required) {
        if (candidate.size() < required.size()) return false;
        for (int index = 0; index < required.size(); index++) {
            if (!candidate.get(index).normalized().equals(required.get(index).normalized())) {
                return false;
            }
        }
        return true;
    }

    private static String identifierList(List<Identifier> identifiers) {
        return identifiers.stream().map(Identifier::value)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String foreignKeyName(ForeignKey foreignKey) {
        return foreignKey.name() == null || foreignKey.name().value().isBlank()
                ? "<unnamed>"
                : foreignKey.name().value();
    }

    private static void addFamilyIssues(
            List<FamilyIssue> target, String family, List<ValidationIssue> issues) {
        if (issues == null) return;
        issues.stream().filter(Objects::nonNull)
                .map(issue -> new FamilyIssue(family, issue))
                .forEach(target::add);
    }

    private MetadataRepository repository(DatabasePlatform platform) {
        MetadataRepository repository = FailureIsolatingMetadataRepository.wrap(
                platform, repositoryResolver.resolve(platform));
        if (!repository.available()) {
            throw new ServiceUnavailableException(
                    "Metadata repository is unavailable for platform " + platform.commandLineName());
        }
        return repository;
    }

    private static void ensureAvailable(MetadataRepository repository, String operation) {
        if (!repository.available()) {
            throw new ServiceUnavailableException(
                    "Metadata repository became unavailable while " + operation);
        }
    }

    private static SchemaConformanceReport report(
            DatabasePlatform platform,
            SchemaConformanceScope scope,
            String schema,
            String table,
            List<String> ruleFamilies,
            DatabaseSchema canonical,
            List<FamilyIssue> rawIssues) {
        List<SchemaConformanceFinding> findings = normalizeFindings(rawIssues);
        int errors = count(findings, "ERROR");
        int warnings = count(findings, "WARNING");
        int infos = findings.size() - errors - warnings;
        int tables = canonical.tables().size();
        int columns = canonical.tables().stream().mapToInt(value -> value.columns().size()).sum();
        SchemaConformanceSummary summary = new SchemaConformanceSummary(
                tables, columns, errors, warnings, infos, findings.size(), errors == 0 && warnings == 0);
        List<SchemaConformanceRuleFamilySummary> familySummaries = ruleFamilies.stream()
                .map(family -> familySummary(family, findings))
                .toList();
        return new SchemaConformanceReport(
                SchemaConformanceReport.CONTRACT,
                platform,
                scope,
                schema,
                table,
                ruleFamilies,
                familySummaries,
                summary,
                findings);
    }

    private static SchemaConformanceRuleFamilySummary familySummary(
            String family, List<SchemaConformanceFinding> findings) {
        List<SchemaConformanceFinding> selected = findings.stream()
                .filter(finding -> family.equals(finding.ruleFamily()))
                .toList();
        int errors = count(selected, "ERROR");
        int warnings = count(selected, "WARNING");
        int infos = selected.size() - errors - warnings;
        return new SchemaConformanceRuleFamilySummary(
                family, errors, warnings, infos, selected.size());
    }

    private static List<SchemaConformanceFinding> normalizeFindings(List<FamilyIssue> issues) {
        Map<String, SchemaConformanceFinding> unique = new LinkedHashMap<>();
        for (FamilyIssue item : issues) {
            if (item == null || item.issue() == null) continue;
            ValidationIssue issue = item.issue();
            SchemaConformanceFinding finding = new SchemaConformanceFinding(
                    item.family(), issue.severity(), issue.code(), issue.path(), issue.message());
            String key = safe(finding.ruleFamily()).toUpperCase(Locale.ROOT) + "|"
                    + safe(finding.severity()).toUpperCase(Locale.ROOT) + "|"
                    + safe(finding.code()) + "|" + safe(finding.path()) + "|" + safe(finding.message());
            unique.putIfAbsent(key, finding);
        }
        return unique.values().stream()
                .sorted(Comparator
                        .comparingInt((SchemaConformanceFinding issue) -> severityRank(issue.severity()))
                        .thenComparing(SchemaConformanceFinding::ruleFamily)
                        .thenComparing(SchemaConformanceFinding::code)
                        .thenComparing(SchemaConformanceFinding::path)
                        .thenComparing(SchemaConformanceFinding::message))
                .toList();
    }

    private static int count(List<SchemaConformanceFinding> issues, String severity) {
        return (int) issues.stream().filter(issue -> severity.equalsIgnoreCase(issue.severity())).count();
    }

    private static int severityRank(String severity) {
        if ("ERROR".equalsIgnoreCase(severity)) return 0;
        if ("WARNING".equalsIgnoreCase(severity)) return 1;
        return 2;
    }

    private static String requireIdentifierText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Dialect dialectFor(DatabasePlatform platform) {
        return switch (platform) {
            case ORACLE -> new OracleDialect();
            case POSTGRESQL -> new PostgreSqlDialect();
            case DB2_ZOS -> new Db2ZosDialect();
            case DB2_LUW -> new Db2LuwDialect();
            case SQLSERVER -> new SqlServerDialect();
            case MYSQL -> new MySqlDialect();
        };
    }

    private record FamilyIssue(String family, ValidationIssue issue) {
    }
}
