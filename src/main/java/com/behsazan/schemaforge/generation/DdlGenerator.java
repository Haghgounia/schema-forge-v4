package com.behsazan.schemaforge.generation;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.issue.InlineIssueRenderer;
import com.behsazan.schemaforge.generation.issue.SqlIssueCatalog;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates a complete single-file SQL script from the canonical model.
 * The generator is deliberately offline: it never connects to a database.
 */
public final class DdlGenerator {
    private static final String NL = System.lineSeparator();
    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    private final Dialect dialect;
    private final Clock clock;
    private final InlineIssueRenderer inlineIssueRenderer;

    public DdlGenerator(Dialect dialect) {
        this(dialect, Clock.systemDefaultZone());
    }

    public DdlGenerator(Dialect dialect, Clock clock) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.inlineIssueRenderer = new InlineIssueRenderer();
    }

    /** Returns one SQL text containing all sequences, tables, constraints, indexes, comments and grants. */
    public String generate(DatabaseSchema schema) {
        return generate(schema, new ValidationReport(true, List.of()));
    }

    /** Generates SQL and renders all validation/recovery findings in one top block and inline comments. */
    public String generate(DatabaseSchema schema, ValidationReport report) {
        return generate(schema, report, MetadataRepository.empty());
    }

    /** Generates SQL enriched with metadata frequencies and metadata type mismatch findings. */
    public String generate(DatabaseSchema schema, ValidationReport report, MetadataRepository metadataRepository) {
        Objects.requireNonNull(metadataRepository, "metadataRepository must not be null");
        MetadataComparisonResult metadata = new MetadataComparisonValidator(dialect, metadataRepository).validate(schema);
        return generate(schema, report, metadata);
    }

    /** Generates SQL using an already calculated metadata comparison result. */
    public String generate(DatabaseSchema schema, ValidationReport report, MetadataComparisonResult metadata) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        List<ValidationIssue> combinedIssues = new ArrayList<>(report.issues());
        combinedIssues.addAll(metadata.issues());
        ValidationReport combinedReport = new ValidationReport(
                combinedIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                combinedIssues);
        SqlIssueCatalog issueCatalog = SqlIssueCatalog.from(schema, combinedReport);

        List<String> statements = new ArrayList<>();
        List<String> grantStatements = new ArrayList<>();
        if (!schema.sequences().isEmpty()) {
            dialect.require(DialectFeature.SEQUENCE);
            schema.sequences().stream()
                    .sorted(Comparator.comparing(sequence -> sequence.qualifiedName().toString()))
                    .map(this::createSequence)
                    .forEach(statements::add);
        }

        for (Table table : schema.tables()) {
            statements.add(createTable(table, issueCatalog, metadata));
            if (dialect.requiresExplicitConstraintIndexes()) {
                table.primaryKey().map(primaryKey -> createPrimaryKeyIndex(table, primaryKey))
                        .ifPresent(statements::add);
            }
            table.checkConstraints().stream().map(check -> createCheck(table, check)).forEach(statements::add);
            for (UniqueKey unique : table.uniqueKeys()) {
                statements.add(createUnique(table, unique));
                if (dialect.requiresExplicitConstraintIndexes()) {
                    statements.add(createUniqueKeyIndex(table, unique));
                }
            }
            table.foreignKeys().stream().map(foreignKey -> createForeignKey(table, foreignKey, metadata)).forEach(statements::add);
            table.indexes().stream().map(index -> createIndex(table, index)).forEach(statements::add);
            addComments(statements, table);
            addGrants(grantStatements, table);
        }
        // Grants are the final executable statements in the generated script.
        statements.addAll(grantStatements);

        String body = statements.stream()
                .filter(statement -> statement != null && !statement.isBlank())
                .collect(Collectors.joining(NL + NL));
        String warnings = warningHeader(issueCatalog);
        StringBuilder script = new StringBuilder();
        if (!warnings.isBlank()) {
            script.append(warnings).append(NL).append(NL);
        }
        script.append(scriptHeader(schema));
        if (!body.isBlank()) {
            script.append(NL).append(NL).append(body);
        }
        return script.append(NL).append(NL)
                .append(summary(schema)).append(NL).append(NL)
                .append(footer(schema))
                .toString();
    }

    private String createSequence(Sequence sequence) {
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ")
                .append(qualifiedName(sequence.qualifiedName()))
                .append(" START WITH ").append(sequence.startWith())
                .append(" INCREMENT BY ").append(sequence.incrementBy());
        if (sequence.maxValue() != null) sql.append(" MAXVALUE ").append(sequence.maxValue());
        if (sequence.minValue() != null) sql.append(" MINVALUE ").append(sequence.minValue());
        sql.append(dialect.sequenceCacheClause(sequence.cacheSize()));
        sql.append(dialect.sequenceCycleClause(sequence.cycle()));
        sql.append(dialect.sequenceTail());
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String createTable(Table table, SqlIssueCatalog issueCatalog, MetadataComparisonResult metadata) {
        List<Column> columns = new ArrayList<>(table.columns());
        columns.sort(Comparator.comparing(Column::ordinalPosition, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> definitions = new ArrayList<>();
        boolean hasPrimaryKey = table.primaryKey().isPresent();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            String path = MetadataComparisonValidator.path(table, column);
            String definition = columnDefinition(column, metadata.frequency(path), metadata.metadataAvailable());
            if (index < columns.size() - 1 || hasPrimaryKey) {
                definition += ",";
            }
            definition += inlineIssueRenderer.render(
                    issueCatalog.forColumn(table, column.name().value()));
            definitions.add(definition);
        }
        table.primaryKey().map(primaryKey -> primaryKeyDefinition(table, primaryKey)).ifPresent(definitions::add);

        StringBuilder sql = new StringBuilder();
        if (!table.description().isEmpty()) {
            sql.append("-- ").append(table.description().value()).append(NL);
        }
        sql.append("CREATE TABLE ")
                .append(qualifiedName(table.qualifiedName()))
                .append(inlineIssueRenderer.render(issueCatalog.forTable(table)))
                .append(NL)
                .append("(").append(NL)
                .append(String.join(NL, definitions)).append(NL)
                .append(")");
        String tablespace = option(table, "TABLESPACE")
                .orElseGet(() -> dialect.defaultTableTablespace(table.qualifiedName()));
        sql.append(dialect.tableTablespaceClause(tablespace));
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String columnDefinition(Column column, long metadataFrequency, boolean metadataAvailable) {
        StringBuilder sql = new StringBuilder("  ");
        if (metadataAvailable) {
            sql.append("/* ").append(String.format(Locale.ROOT, "%3d", metadataFrequency)).append("*/  ");
        }
        sql.append(dialect.quote(column.name())).append(" ")
                .append(dialect.sqlType(column));
        if (column.generated()) {
            dialect.require(DialectFeature.GENERATED_COLUMN);
            sql.append(dialect.generatedColumnClause(column));
        } else if (column.identity() && column.defaultValue().isPresent()) {
            // Word specifications use IDENTITY as a logical marker. When the parser has
            // supplied a sequence NEXTVAL expression, sequence-based identity is emitted.
            sql.append(dialect.defaultClause(column));
        } else if (column.identity()) {
            dialect.require(DialectFeature.IDENTITY_COLUMN);
            sql.append(dialect.identityClause(column));
        } else if (column.defaultValue().isPresent()) {
            sql.append(dialect.defaultClause(column));
        }
        if (!column.nullable()) sql.append(" NOT NULL");
        return sql.toString();
    }

    private String primaryKeyDefinition(Table table, PrimaryKey primaryKey) {
        String constraintName = primaryKey.name() == null
                ? "PK_" + table.qualifiedName().name().normalized()
                : dialect.quote(primaryKey.name());
        String indexTablespace = option(table, "INDEX_TABLESPACE")
                .orElseGet(() -> option(table, "PK_TABLESPACE")
                        .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName())));
        String tableName = qualifiedName(table.qualifiedName());
        String columns = identifiers(primaryKey.columns());
        String qualifiedIndexName = qualifyLikeTable(table, constraintName);
        return dialect.primaryKeyConstraint(
                constraintName, tableName, columns, qualifiedIndexName, indexTablespace, primaryKey.deferrable(), primaryKey.initiallyDeferred());
    }

    private String createPrimaryKeyIndex(Table table, PrimaryKey primaryKey) {
        String defaultName = "PK_" + table.qualifiedName().name().normalized();
        String indexName = enforcingIndexName(primaryKey.name(), defaultName);
        return createEnforcingUniqueIndex(table, indexName, primaryKey.columns());
    }

    private String createUniqueKeyIndex(Table table, UniqueKey unique) {
        String defaultName = "UK_" + table.qualifiedName().name().normalized()
                + "_" + rawIdentifiers(unique.columns());
        String indexName = enforcingIndexName(unique.name(), defaultName);
        return createEnforcingUniqueIndex(table, indexName, unique.columns());
    }

    private String enforcingIndexName(Identifier constraintName, String defaultName) {
        String base = constraintName == null ? defaultName : constraintName.value();
        int maximumBaseLength = 125;
        if (base.length() > maximumBaseLength) {
            base = base.substring(0, maximumBaseLength);
        }
        return dialect.quote(Identifier.of(base + "_IX"));
    }

    private String createEnforcingUniqueIndex(Table table, String indexName, List<Identifier> columns) {
        String indexTablespace = option(table, "INDEX_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        return "CREATE UNIQUE INDEX " + qualifyLikeTable(table, indexName)
                + " ON " + qualifiedName(table.qualifiedName())
                + "(" + identifiers(columns) + ")"
                + dialect.indexTablespaceClause(indexTablespace)
                + dialect.statementTerminator();
    }

    private String createCheck(Table table, CheckConstraint check) {
        String name = check.name() == null
                ? "CHK_" + table.qualifiedName().name().normalized()
                : dialect.quote(check.name());
        return "ALTER TABLE " + qualifiedName(table.qualifiedName())
                + " ADD CONSTRAINT " + name
                + " CHECK(" + dialect.expression(check.expression()) + ")"
                + dialect.constraintValidationClause() + dialect.statementTerminator();
    }

    private String createUnique(Table table, UniqueKey unique) {
        String name = unique.name() == null
                ? "UK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(unique.columns())
                : dialect.quote(unique.name());
        String columns = identifiers(unique.columns());
        String tableName = qualifiedName(table.qualifiedName());
        String indexTablespace = option(table, "INDEX_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        String qualifiedIndexName = qualifyLikeTable(table, name);
        return dialect.uniqueConstraint(name, tableName, columns, qualifiedIndexName, indexTablespace, unique.deferrable(), unique.initiallyDeferred());
    }

    private String createForeignKey(Table table, ForeignKey foreignKey, MetadataComparisonResult metadata) {
        String name = foreignKey.name() == null
                ? "FK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(foreignKey.columns())
                : dialect.quote(foreignKey.name());
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualifiedName(table.qualifiedName()))
                .append(" ADD CONSTRAINT ").append(name)
                .append(" FOREIGN KEY (").append(identifiers(foreignKey.columns())).append(")")
                                .append(" REFERENCES ").append(qualifiedName(resolvedReferencedTable(table, foreignKey, metadata)))
                .append("(").append(identifiers(foreignKey.referencedColumns())).append(")");
        appendReferentialAction(sql, "ON DELETE", foreignKey.onDelete());
        appendReferentialAction(sql, "ON UPDATE", foreignKey.onUpdate());
        sql.append(dialect.deferrabilityClause(foreignKey.deferrable(), foreignKey.initiallyDeferred()));
        return sql.append(dialect.constraintValidationClause()).append(dialect.statementTerminator()).toString();
    }

    private QualifiedName resolvedReferencedTable(Table table, ForeignKey foreignKey, MetadataComparisonResult metadata) {
        String path = MetadataComparisonValidator.foreignKeyPath(table, foreignKey);
        String resolvedSchema = metadata.resolvedForeignKeySchema(path);
        if (resolvedSchema == null || resolvedSchema.isBlank()) {
            if (foreignKey.referencedTable().schemaName().isPresent()) return foreignKey.referencedTable();
            String ownerSchema = table.qualifiedName().schemaName().map(Identifier::value).orElse(null);
            return QualifiedName.of(ownerSchema, foreignKey.referencedTable().name().value());
        }
        return QualifiedName.of(resolvedSchema, foreignKey.referencedTable().name().value());
    }

    private void appendReferentialAction(StringBuilder sql, String clause, ReferentialAction action) {
        sql.append(dialect.referentialActionClause(clause, action));
    }

    private String createIndex(Table table, Index index) {
        String name = index.name() == null
                ? "IDX_" + table.qualifiedName().name().normalized() + "_" + rawIndexColumns(index.columns())
                : dialect.quote(index.name());
        String unique = index.type() == IndexType.UNIQUE ? "UNIQUE " : "";
        String columns = index.columns().stream().map(this::indexColumn).collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder("CREATE ").append(unique).append("INDEX ")
                .append(qualifyLikeTable(table, name)).append(" ON ")
                .append(qualifiedName(table.qualifiedName())).append("(").append(columns).append(")");
        if (!index.includeColumns().isEmpty()) {
            sql.append(dialect.indexIncludeClause(identifiers(index.includeColumns())));
        }
        String indexTablespace = option(table, "INDEX_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        sql.append(dialect.indexTablespaceClause(indexTablespace));
        if (index.predicate() != null) {
            sql.append(dialect.partialIndexClause(index.predicate()));
        }
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String indexColumn(IndexColumn indexColumn) {
        String value;
        if (indexColumn.expressionBased()) {
            dialect.require(DialectFeature.EXPRESSION_INDEX);
            value = "(" + dialect.expression(indexColumn.expression()) + ")";
        } else {
            value = dialect.quote(indexColumn.column());
        }
        return indexColumn.direction() == SortDirection.DESC ? value + " DESC" : value;
    }

    private void addComments(List<String> statements, Table table) {
        if (dialect.supports(DialectFeature.TABLE_COMMENT) && !table.description().isEmpty()) {
            statements.add("COMMENT ON TABLE " + qualifiedName(table.qualifiedName())
                    + " IS '" + escapeLiteral(table.description().value()) + "'" + dialect.statementTerminator());
        }
        if (!dialect.supports(DialectFeature.COLUMN_COMMENT)) {
            return;
        }
        for (Column column : table.columns()) {
            if (!column.description().isEmpty()) {
                statements.add("COMMENT ON COLUMN " + qualifiedName(table.qualifiedName()) + "." + dialect.quote(column.name())
                        + " IS '" + escapeLiteral(column.description().value()) + "'" + dialect.statementTerminator());
            }
        }
    }

    private void addGrants(List<String> statements, Table table) {
        if (!dialect.supports(DialectFeature.GRANT)) {
            return;
        }
        option(table, "GRANTS").ifPresent(value -> {
            for (String grant : value.split("[;\\r\\n]+")) {
                String trimmed = grant.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // Canonical option format: SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER
                // The grantee is a database role/principal; it is not an application user id.
                int toIndex = trimmed.toUpperCase(Locale.ROOT).lastIndexOf(" TO ");
                if (toIndex <= 0 || toIndex + 4 >= trimmed.length()) {
                    throw new IllegalArgumentException("Invalid GRANTS option: " + trimmed);
                }
                String privileges = trimmed.substring(0, toIndex).trim();
                String grantee = trimmed.substring(toIndex + 4).trim();
                statements.add("GRANT " + privileges
                        + " ON " + qualifiedName(table.qualifiedName())
                        + " TO " + grantee
                        + dialect.statementTerminator());
            }
        });
    }

    private String scriptHeader(DatabaseSchema schema) {
        String source = firstMetadata(schema.metadata(),
                "source.fileName", "sourceFile", "source-file", "source", "fileName");
        return dialect.scriptPreamble(source, schema.name().normalized());
    }

    private String summary(DatabaseSchema schema) {
        int tableCount = schema.tables().size();
        int columnCount = schema.tables().stream().mapToInt(table -> table.columns().size()).sum();
        int primaryKeyCount = (int) schema.tables().stream().filter(table -> table.primaryKey().isPresent()).count();
        int uniqueCount = schema.tables().stream().mapToInt(table -> table.uniqueKeys().size()).sum();
        int checkCount = schema.tables().stream().mapToInt(table -> table.checkConstraints().size()).sum();
        int foreignKeyCount = schema.tables().stream().mapToInt(table -> table.foreignKeys().size()).sum();
        int indexCount = schema.tables().stream().mapToInt(table -> table.indexes().size()).sum();
        int enforcingIndexCount = dialect.requiresExplicitConstraintIndexes()
                ? primaryKeyCount + uniqueCount
                : 0;
        int emittedIndexCount = indexCount + enforcingIndexCount;
        return "/*" + NL
                + "SchemaForge Object Summary" + NL
                + "Sequences    : " + schema.sequences().size() + NL
                + "Tables       : " + tableCount + NL
                + "Columns      : " + columnCount + NL
                + "Primary Keys : " + primaryKeyCount + NL
                + "Unique Keys  : " + uniqueCount + NL
                + "Checks       : " + checkCount + NL
                + "Foreign Keys : " + foreignKeyCount + NL
                + "Indexes      : " + emittedIndexCount + NL
                + (enforcingIndexCount == 0 ? ""
                        : "Enforcing    : " + enforcingIndexCount + NL)
                + "*/";
    }

    private String warningHeader(SqlIssueCatalog issueCatalog) {
        List<ValidationIssue> issues = issueCatalog.all();
        if (issues.isEmpty()) {
            return "";
        }

        StringBuilder sql = new StringBuilder();
        sql.append(dialect.warningLine("==============================================================")).append(NL)
                .append(dialect.warningLine("SchemaForge Validation Findings")).append(NL)
                .append(dialect.warningLine("=============================================================="));
        for (ValidationIssue issue : issues) {
            sql.append(NL)
                    .append(dialect.warningLine("[" + safe(issue.severity()) + "] "
                            + safe(issue.code()) + " [" + safe(issue.path()) + "]: "
                            + oneLine(issue.message())));
        }
        sql.append(NL).append(dialect.warningLine("=============================================================="));
        return sql.toString();
    }

    private String oneLine(String value) {
        return safe(value).replace("\r", " ").replace("\n", " ").replaceAll("\s+", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String footer(DatabaseSchema schema) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        String source = firstMetadata(schema.metadata(), "source.fileName", "sourceFile", "source-file", "source", "fileName");
        return "/*" + NL
                + "Generated By : SchemaForge" + NL
                + "Generated On : " + FOOTER_TIME.format(now) + NL
                + (source == null ? "" : "Source File  : " + source + NL)
                + "Dialect      : " + dialect.name() + NL
                + "*/";
    }

    private String firstMetadata(Map<String, String> metadata, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && !entry.getValue().isBlank()) return entry.getValue();
            }
        }
        return null;
    }

    private java.util.Optional<String> option(Table table, String key) {
        return table.physicalOptions().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .findFirst();
    }

    private String qualifiedName(QualifiedName name) {
        return name.schemaName()
                .map(schema -> dialect.quote(schema) + "." + dialect.quote(name.name()))
                .orElseGet(() -> dialect.quote(name.name()));
    }

    private String qualifyLikeTable(Table table, String objectName) {
        return table.qualifiedName().schemaName()
                .map(schema -> dialect.quote(schema) + "." + objectName)
                .orElse(objectName);
    }

    private String identifiers(List<Identifier> identifiers) {
        return identifiers.stream().map(dialect::quote).collect(Collectors.joining(","));
    }

    private String rawIdentifiers(List<Identifier> identifiers) {
        return identifiers.stream().map(Identifier::normalized).collect(Collectors.joining("_"));
    }

    private String rawIndexColumns(List<IndexColumn> columns) {
        return columns.stream()
                .map(column -> column.expressionBased() ? "EXPR" : column.column().normalized())
                .collect(Collectors.joining("_"));
    }

    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
