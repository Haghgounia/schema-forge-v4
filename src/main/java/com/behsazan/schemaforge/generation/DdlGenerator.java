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
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.issue.InlineIssueRenderer;
import com.behsazan.schemaforge.generation.issue.SqlIssueCatalog;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalCommentRendererResolver;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final DatabaseSchema typeMappingContext;
    private final InlineIssueRenderer inlineIssueRenderer;
    private final PhysicalCommentRenderer physicalCommentRenderer;
    private final DatatypeCompatibilityAnalyzer datatypeCompatibilityAnalyzer;

    public DdlGenerator(Dialect dialect) {
        this(dialect, Clock.systemDefaultZone(), null);
    }

    public DdlGenerator(Dialect dialect, Clock clock) {
        this(dialect, clock, null);
    }

    /**
     * Creates a generator that renders the supplied output schema while resolving dialect
     * datatype adaptations against a broader canonical schema context. This is used by
     * per-table artifact generation where relationships to other canonical tables still
     * influence target datatype compatibility.
     */
    public DdlGenerator(Dialect dialect, DatabaseSchema typeMappingContext) {
        this(dialect, Clock.systemDefaultZone(),
                Objects.requireNonNull(typeMappingContext, "typeMappingContext must not be null"));
    }

    private DdlGenerator(Dialect dialect, Clock clock, DatabaseSchema typeMappingContext) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.typeMappingContext = typeMappingContext;
        this.inlineIssueRenderer = new InlineIssueRenderer();
        this.physicalCommentRenderer = PhysicalCommentRendererResolver.resolve(dialect);
        this.datatypeCompatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();
    }

    /**
     * Renders schema bootstrap and sequence statements for integrated deployment.
     *
     * <p>This API deliberately reuses the exact statement renderers used by {@link #generate(DatabaseSchema)}
     * so the integrated deployment path cannot drift from the already validated historical DDL syntax.</p>
     */
    public List<String> renderIntegratedPreTableStatements(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        List<String> statements = new ArrayList<>();
        generatedObjectSchemas(schema).stream()
                .map(dialect::schemaBootstrapStatement)
                .filter(statement -> statement != null && !statement.isBlank())
                .forEach(statements::add);
        List<Sequence> sequences = emittedSequences(schema);
        if (!sequences.isEmpty()) {
            dialect.require(DialectFeature.SEQUENCE);
            sequences.stream()
                    .sorted(Comparator.comparing(sequence -> sequence.qualifiedName().toString()))
                    .map(this::createSequence)
                    .forEach(statements::add);
        }
        return List.copyOf(statements);
    }

    /** Renders only CREATE TABLE (including the primary key) for integrated phase 1. */
    public String renderIntegratedCreateTable(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        DatabaseSchema singleTableSchema = DatabaseSchema.builder(
                        table.qualifiedName().schemaName().map(Identifier::value).orElse("INTEGRATED"))
                .addTable(table)
                .build();
        MetadataComparisonResult metadata = new MetadataComparisonResult(
                List.of(), Map.of(), Map.of(), false);
        SqlIssueCatalog issues = issueCatalog(
                singleTableSchema, new ValidationReport(true, List.of()), metadata);
        return createTable(effectiveTypeMappingContext(singleTableSchema), table, issues, metadata);
    }

    /**
     * Renders table-local post-create objects for integrated phase 2.
     * Foreign keys, comments and grants are intentionally excluded.
     */
    public List<String> renderIntegratedTableLocalStatements(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        List<String> statements = new ArrayList<>();
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
        emittedIndexes(table).stream().map(index -> createIndex(table, index)).forEach(statements::add);
        return List.copyOf(statements);
    }

    /** Renders ALTER statements that add the desired primary key to an existing table. */
    public List<String> renderMigrationAddPrimaryKey(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        PrimaryKey primaryKey = table.primaryKey().orElseThrow(
                () -> new IllegalArgumentException("table has no primary key: " + table.qualifiedName()));
        String tableName = qualifiedName(table.qualifiedName());
        List<String> statements = new ArrayList<>();
        statements.add("ALTER TABLE " + tableName + " ADD "
                + primaryKeyDefinition(table, primaryKey) + dialect.statementTerminator());
        if (dialect.requiresExplicitConstraintIndexes()) {
            statements.add(createPrimaryKeyIndex(table, primaryKey));
        }
        return List.copyOf(statements);
    }

    /** Renders ALTER/CREATE statements that add one desired unique key to an existing table. */
    public List<String> renderMigrationAddUniqueKey(Table table, UniqueKey uniqueKey) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(uniqueKey, "uniqueKey must not be null");
        List<String> statements = new ArrayList<>();
        statements.add(createUnique(table, uniqueKey));
        if (dialect.requiresExplicitConstraintIndexes()) {
            statements.add(createUniqueKeyIndex(table, uniqueKey));
        }
        return List.copyOf(statements);
    }

    /** Renders one desired check constraint for an existing table. */
    public String renderMigrationAddCheck(Table table, CheckConstraint check) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(check, "check must not be null");
        return createCheck(table, check);
    }

    /** Renders one desired standalone index for an existing table. */
    public String renderMigrationAddIndex(Table table, Index index) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(index, "index must not be null");
        return createIndex(table, index);
    }

    /** Renders one desired physical foreign key using its canonical referenced table. */
    public String renderMigrationAddForeignKey(Table table, ForeignKey foreignKey) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(foreignKey, "foreignKey must not be null");
        QualifiedName referenced = foreignKey.referencedTable().schemaName().isPresent()
                ? foreignKey.referencedTable()
                : QualifiedName.of(
                        table.qualifiedName().schemaName().map(Identifier::value).orElse(null),
                        foreignKey.referencedTable().name().value());
        return createForeignKey(table, foreignKey, referenced);
    }

    /** Renders one resolved physical foreign key for integrated phase 3. */
    public String renderIntegratedForeignKey(
            Table table, ForeignKey foreignKey, QualifiedName referencedTable) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(foreignKey, "foreignKey must not be null");
        Objects.requireNonNull(referencedTable, "referencedTable must not be null");
        return createForeignKey(table, foreignKey, referencedTable);
    }

    /** Renders comments/descriptions and grants for integrated phase 4. */
    public List<String> renderIntegratedMetadataStatements(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        List<String> statements = new ArrayList<>();
        addComments(statements, table);
        addGrants(statements, table);
        return List.copyOf(statements);
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
        SqlIssueCatalog issueCatalog = issueCatalog(schema, report, metadata);
        DatabaseSchema mappingContext = effectiveTypeMappingContext(schema);

        List<String> statements = new ArrayList<>();
        List<String> grantStatements = new ArrayList<>();
        List<Sequence> emittedSequences = emittedSequences(schema);
        generatedObjectSchemas(schema).stream()
                .map(dialect::infrastructureProvisioningTemplate)
                .filter(statement -> statement != null && !statement.isBlank())
                .forEach(statements::add);
        generatedObjectSchemas(schema).stream()
                .filter(schemaName -> !metadata.schemaKnownToExist(schemaName.value()))
                .map(dialect::schemaBootstrapStatement)
                .filter(statement -> statement != null && !statement.isBlank())
                .forEach(statements::add);
        if (!emittedSequences.isEmpty()) {
            dialect.require(DialectFeature.SEQUENCE);
            emittedSequences.stream()
                    .sorted(Comparator.comparing(sequence -> sequence.qualifiedName().toString()))
                    .map(this::createSequence)
                    .forEach(statements::add);
        }

        for (Table table : schema.tables()) {
            statements.add(createTable(mappingContext, table, issueCatalog, metadata));
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
            if (dialect.commentsBeforeForeignKeys()) {
                // SQL Server descriptions are independent metadata. Emit them before
                // referential dependencies so they survive a later missing-parent failure.
                addComments(statements, table);
            }
            emittedIndexes(table).stream()
                    .map(index -> createIndex(table, index))
                    .forEach(statements::add);
            table.foreignKeys().stream()
                    .map(foreignKey -> createForeignKey(table, foreignKey, metadata))
                    .forEach(statements::add);
            if (!dialect.commentsBeforeForeignKeys()) {
                addComments(statements, table);
            }
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

    private SqlIssueCatalog issueCatalog(
            DatabaseSchema schema, ValidationReport report, MetadataComparisonResult metadata) {
        List<ValidationIssue> combinedIssues = new ArrayList<>(report.issues());
        combinedIssues.addAll(metadata.issues());
        combinedIssues.addAll(datatypeCompatibilityAnalyzer.analyze(schema, dialect).issues());
        ValidationReport combinedReport = new ValidationReport(
                combinedIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                combinedIssues);
        return SqlIssueCatalog.from(schema, combinedReport);
    }

    private String createSequence(Sequence sequence) {
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ")
                .append(qualifiedName(sequence.qualifiedName()))
                .append(" START WITH ").append(sequence.startWith())
                .append(" INCREMENT BY ").append(sequence.incrementBy());
        if (sequence.maxValue() != null) sql.append(" MAXVALUE ").append(sequence.maxValue());
        if (sequence.minValue() != null) sql.append(" MINVALUE ").append(sequence.minValue());
        sql.append(dialect.sequenceOptions(sequence.cacheSize(), sequence.cycle()));
        sql.append(dialect.sequenceTail());
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String createTable(
            DatabaseSchema schemaContext,
            Table table,
            SqlIssueCatalog issueCatalog,
            MetadataComparisonResult metadata) {
        dialect.validateTable(table);
        List<String> supplementalDefinitions = dialect.supplementalCreateTableDefinitions(table);
        List<Column> columns = new ArrayList<>(table.columns());
        columns.sort(Comparator.comparing(Column::ordinalPosition, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> definitions = new ArrayList<>();
        boolean hasPrimaryKey = table.primaryKey().isPresent();
        boolean hasSupplementalDefinitions = !supplementalDefinitions.isEmpty();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            String path = MetadataComparisonValidator.path(table, column);
            String definition = columnDefinition(
                    schemaContext, table, column, metadata.frequency(path), metadata.metadataAvailable());
            if (index < columns.size() - 1 || hasPrimaryKey || hasSupplementalDefinitions) {
                definition += ",";
            }
            definition += inlineIssueRenderer.render(
                    issueCatalog.forColumn(table, column.name().value()));
            definitions.add(definition);
        }
        table.primaryKey().map(primaryKey -> {
            String definition = primaryKeyDefinition(table, primaryKey);
            return hasSupplementalDefinitions ? definition + "," : definition;
        }).ifPresent(definitions::add);
        for (int index = 0; index < supplementalDefinitions.size(); index++) {
            String definition = supplementalDefinitions.get(index);
            if (index < supplementalDefinitions.size() - 1) {
                definition += ",";
            }
            definitions.add(definition);
        }

        StringBuilder sql = new StringBuilder();
        if (!table.persianName().isEmpty()) {
            sql.append("-- Persian table name: ").append(table.persianName().value()).append(NL);
        }
        if (!table.description().isEmpty()
                && !normalizeCommentText(table.description().value())
                        .equals(normalizeCommentText(table.persianName().value()))) {
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
        String activePlacement = dialect.tableTablespaceClause(tablespace);
        String physicalComment = physicalCommentRenderer.tableOptions(
                table, !activePlacement.isBlank());
        sql.append(dialect.tableTailWithPhysical(activePlacement, physicalComment));
        String tableComment = table.persianName().isEmpty()
                ? table.description().value()
                : table.persianName().value();
        if (!tableComment.isBlank()) {
            sql.append(dialect.inlineTableCommentClause(tableComment));
        }
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String columnDefinition(
            DatabaseSchema schemaContext,
            Table table,
            Column column,
            long metadataFrequency,
            boolean metadataAvailable) {
        StringBuilder sql = new StringBuilder("  ");
        if (metadataAvailable) {
            sql.append("/* ").append(String.format(Locale.ROOT, "%3d", metadataFrequency)).append("*/  ");
        }
        sql.append(dialect.quote(column.name()));
        if ("MISSING_DATA_TYPE".equalsIgnoreCase(column.dataType().name().normalized())) {
            throw new IllegalArgumentException(
                    "Unresolved canonical datatype for "
                            + table.qualifiedName() + "." + column.name().value()
                            + "; source specification must provide an exact datatype before DDL generation");
        }
        if (!column.generated() || dialect.generatedColumnIncludesDataType()) {
            sql.append(" ").append(dialect.sqlType(schemaContext, table, column));
        }
        sql.append(dialect.columnPhysicalClause(column));
        if (column.generated()) {
            dialect.require(DialectFeature.GENERATED_COLUMN);
            sql.append(dialect.generatedColumnClause(column));
        } else if (column.identity() && column.defaultValue().isPresent()) {
            // Word specifications use IDENTITY as a logical marker. When the parser has
            // supplied a sequence NEXTVAL expression, sequence-based identity is emitted.
            sql.append(dialect.defaultClause(column));
        } else if (column.identity() && dialect.identityUsesNamedSequence()) {
            dialect.require(DialectFeature.SEQUENCE);
            sql.append(dialect.identitySequenceClause(identitySequenceName(table, column)));
        } else if (column.identity()) {
            dialect.require(DialectFeature.IDENTITY_COLUMN);
            sql.append(dialect.identityClause(column));
        } else if (column.defaultValue().isPresent()) {
            sql.append(dialect.defaultClause(column));
        }
        if (!column.nullable()
                && (!column.generated() || dialect.generatedColumnIncludesNullability())) {
            sql.append(" NOT NULL");
        }
        if (!column.description().isEmpty()) {
            sql.append(dialect.inlineColumnCommentClause(column));
        }
        sql.append(dialect.inlineColumnConstraintClause(schemaContext, table, column));
        return sql.toString();
    }

    private DatabaseSchema effectiveTypeMappingContext(DatabaseSchema generatedSchema) {
        return typeMappingContext == null ? generatedSchema : typeMappingContext;
    }

    private String primaryKeyDefinition(Table table, PrimaryKey primaryKey) {
        String constraintName = primaryKey.name() == null
                ? "PK_" + table.qualifiedName().name().normalized()
                : dialect.quote(primaryKey.name());
        Index physicalIndex = constraintPhysicalIndex(primaryKey.name(), primaryKey.columns(), primaryKey.physicalOptions());
        String indexTablespace = option(physicalIndex, table, "INDEX_TABLESPACE", "PK_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        String tableName = qualifiedName(table.qualifiedName());
        String columns = identifiers(primaryKey.columns());
        String qualifiedIndexName = dialect.qualifyIndexName(table.qualifiedName(), constraintName);
        String activeIndexPlacement = dialect.indexTablespaceClause(indexTablespace);
        String physicalIndexComment = physicalCommentRenderer.constraintIndexOptions(
                table, physicalIndex, primaryKey.columns(), !activeIndexPlacement.isBlank());
        return dialect.primaryKeyConstraintWithPhysical(
                constraintName, tableName, columns, qualifiedIndexName, indexTablespace,
                physicalIndexComment, physicalIndex, primaryKey.deferrable(), primaryKey.initiallyDeferred());
    }

    private String createPrimaryKeyIndex(Table table, PrimaryKey primaryKey) {
        String defaultName = "PK_" + table.qualifiedName().name().normalized();
        String indexName = enforcingIndexName(primaryKey.name(), defaultName);
        return createEnforcingUniqueIndex(table, indexName, primaryKey.columns(), primaryKey.physicalOptions());
    }

    private String createUniqueKeyIndex(Table table, UniqueKey unique) {
        String defaultName = "UK_" + table.qualifiedName().name().normalized()
                + "_" + rawIdentifiers(unique.columns());
        String indexName = enforcingIndexName(unique.name(), defaultName);
        return createEnforcingUniqueIndex(table, indexName, unique.columns(), unique.physicalOptions());
    }

    private String enforcingIndexName(Identifier constraintName, String defaultName) {
        String base = constraintName == null ? defaultName : constraintName.value();
        int maximumBaseLength = 125;
        if (base.length() > maximumBaseLength) {
            base = base.substring(0, maximumBaseLength);
        }
        return dialect.quote(Identifier.of(base + "_IX"));
    }

    private String createEnforcingUniqueIndex(
            Table table, String indexName, List<Identifier> columns, Map<String, String> physicalOptions) {
        Index physicalIndex = constraintPhysicalIndex(null, columns, physicalOptions);
        String indexTablespace = option(physicalIndex, table, "INDEX_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        String activeIndexPlacement = dialect.indexTablespaceClause(indexTablespace);
        String physicalIndexComment = physicalCommentRenderer.indexOptions(
                table, physicalIndex, columns, !activeIndexPlacement.isBlank(), true);
        return "CREATE UNIQUE " + dialect.indexOrganizationClause(physicalIndex) + "INDEX "
                + dialect.qualifyIndexName(table.qualifiedName(), indexName)
                + " ON " + qualifiedName(table.qualifiedName())
                + "(" + identifiers(columns) + ")"
                + dialect.indexTailWithPhysical(null, physicalIndexComment, indexTablespace, null)
                + dialect.statementTerminator();
    }

    private String createCheck(Table table, CheckConstraint check) {
        String name = check.name() == null
                ? "CHK_" + table.qualifiedName().name().normalized()
                : dialect.quote(check.name());
        String tableName = qualifiedName(table.qualifiedName());
        String create = dialect.alterTableAddConstraintPrefix(tableName)
                + name
                + " CHECK(" + dialect.expression(check.expression()) + ")"
                + dialect.constraintValidationClause() + dialect.statementTerminator();
        String postCreate = dialect.postCreateConstraintStatement(tableName, name);
        return appendStatement(create, postCreate);
    }

    private String createUnique(Table table, UniqueKey unique) {
        String name = unique.name() == null
                ? "UK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(unique.columns())
                : dialect.quote(unique.name());
        String columns = identifiers(unique.columns());
        String tableName = qualifiedName(table.qualifiedName());
        Index physicalIndex = constraintPhysicalIndex(unique.name(), unique.columns(), unique.physicalOptions());
        String indexTablespace = option(physicalIndex, table, "INDEX_TABLESPACE", "UK_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        String qualifiedIndexName = dialect.qualifyIndexName(table.qualifiedName(), name);
        String activeIndexPlacement = dialect.indexTablespaceClause(indexTablespace);
        String physicalIndexComment = physicalCommentRenderer.constraintIndexOptions(
                table, physicalIndex, unique.columns(), !activeIndexPlacement.isBlank());
        return dialect.uniqueConstraintWithPhysical(
                name, tableName, columns, qualifiedIndexName, indexTablespace,
                physicalIndexComment, physicalIndex, unique.deferrable(), unique.initiallyDeferred());
    }

    private String createForeignKey(Table table, ForeignKey foreignKey, MetadataComparisonResult metadata) {
        return createForeignKey(table, foreignKey, resolvedReferencedTable(table, foreignKey, metadata));
    }

    private String createForeignKey(Table table, ForeignKey foreignKey, QualifiedName referencedTable) {
        String name = foreignKey.name() == null
                ? "FK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(foreignKey.columns())
                : dialect.quote(foreignKey.name());
        if (!foreignKey.physicalReference()) {
            return dialect.warningLine("[LOGICAL FOREIGN KEY] " + name + ": "
                    + qualifiedName(table.qualifiedName()) + "(" + identifiers(foreignKey.columns()) + ") -> "
                    + qualifiedName(referencedTable) + "(" + identifiers(foreignKey.referencedColumns()) + ")");
        }
        String tableName = qualifiedName(table.qualifiedName());
        StringBuilder sql = new StringBuilder(dialect.alterTableAddConstraintPrefix(tableName))
                .append(name)
                .append(" FOREIGN KEY (").append(identifiers(foreignKey.columns())).append(")")
                .append(" REFERENCES ").append(qualifiedName(referencedTable))
                .append("(").append(identifiers(foreignKey.referencedColumns())).append(")");
        appendReferentialAction(sql, "ON DELETE", foreignKey.onDelete());
        appendReferentialAction(sql, "ON UPDATE", foreignKey.onUpdate());
        sql.append(dialect.deferrabilityClause(foreignKey.deferrable(), foreignKey.initiallyDeferred()));
        String create = sql.append(dialect.constraintValidationClause())
                .append(dialect.statementTerminator()).toString();
        String postCreate = dialect.postCreateConstraintStatement(tableName, name);
        String rendered = appendStatement(create, postCreate);
        String recommendation = foreignKeySupportingIndexRecommendation(table, foreignKey, name);
        return recommendation.isBlank() ? rendered : rendered + NL + recommendation;
    }

    private String foreignKeySupportingIndexRecommendation(
            Table table, ForeignKey foreignKey, String renderedForeignKeyName) {
        if (!foreignKey.physicalReference() || hasSupportingIndex(table, foreignKey.columns())) {
            return "";
        }
        return "-- [RECOMMENDATION][PHYS-FK-INDEX-001] Foreign key "
                + renderedForeignKeyName
                + " has no supporting index whose leading columns match ("
                + identifiers(foreignKey.columns()) + ").";
    }

    private boolean hasSupportingIndex(Table table, List<Identifier> foreignKeyColumns) {
        if (table.primaryKey().isPresent()
                && leadingColumnsMatch(table.primaryKey().get().columns(), foreignKeyColumns)) {
            return true;
        }
        for (UniqueKey unique : table.uniqueKeys()) {
            if (leadingColumnsMatch(unique.columns(), foreignKeyColumns)) {
                return true;
            }
        }
        for (Index index : table.indexes()) {
            List<Identifier> indexColumns = new ArrayList<>();
            boolean expressionBeforeMatchBoundary = false;
            for (IndexColumn indexColumn : index.columns()) {
                if (indexColumn.expressionBased()) {
                    expressionBeforeMatchBoundary = true;
                    break;
                }
                indexColumns.add(indexColumn.column());
                if (indexColumns.size() >= foreignKeyColumns.size()) {
                    break;
                }
            }
            if (!expressionBeforeMatchBoundary && leadingColumnsMatch(indexColumns, foreignKeyColumns)) {
                return true;
            }
        }
        return false;
    }

    private boolean leadingColumnsMatch(List<Identifier> candidate, List<Identifier> required) {
        if (candidate.size() < required.size()) {
            return false;
        }
        for (int i = 0; i < required.size(); i++) {
            if (!candidate.get(i).normalized().equals(required.get(i).normalized())) {
                return false;
            }
        }
        return true;
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

    private String appendStatement(String first, String second) {
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + NL + second;
    }

    private List<Index> emittedIndexes(Table table) {
        Set<String> signatures = new LinkedHashSet<>();
        table.primaryKey().ifPresent(primaryKey ->
                signatures.add(identifierSignature(primaryKey.columns())));
        for (UniqueKey uniqueKey : table.uniqueKeys()) {
            signatures.add(identifierSignature(uniqueKey.columns()));
        }

        List<Index> result = new ArrayList<>();
        for (Index index : table.indexes()) {
            List<IndexColumn> normalizedColumns = deduplicateIndexColumns(index.columns());
            String signature = indexSignature(normalizedColumns);
            if (!signatures.add(signature)) {
                continue;
            }
            if (normalizedColumns.equals(index.columns())) {
                result.add(index);
            } else {
                result.add(new Index(index.name(), normalizedColumns, index.type(), index.description(),
                        index.includeColumns(), index.predicate(), index.physicalOptions(), index.buildOptions()));
            }
        }
        return List.copyOf(result);
    }

    private List<IndexColumn> deduplicateIndexColumns(List<IndexColumn> columns) {
        Set<String> seen = new LinkedHashSet<>();
        List<IndexColumn> result = new ArrayList<>();
        for (IndexColumn column : columns) {
            String signature = indexColumnSignature(column);
            if (seen.add(signature)) {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    private String identifierSignature(List<Identifier> columns) {
        return columns.stream()
                .map(identifier -> dialect.quote(identifier).toUpperCase(Locale.ROOT) + ":ASC")
                .collect(Collectors.joining("|"));
    }

    private String indexSignature(List<IndexColumn> columns) {
        return columns.stream().map(this::indexColumnSignature).collect(Collectors.joining("|"));
    }

    private String indexColumnSignature(IndexColumn column) {
        if (column.expressionBased()) {
            return "EXPR:" + column.expression().replaceAll("\\s+", " ")
                    .trim().toUpperCase(Locale.ROOT) + ":" + column.direction();
        }
        return dialect.quote(column.column()).toUpperCase(Locale.ROOT) + ":" + column.direction();
    }

    private String createIndex(Table table, Index index) {
        String name = index.name() == null
                ? "IDX_" + table.qualifiedName().name().normalized() + "_" + rawIndexColumns(index.columns())
                : dialect.quote(index.name());
        String unique = index.type() == IndexType.UNIQUE ? "UNIQUE " : "";
        String columns = index.columns().stream().map(this::indexColumn).collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder("CREATE ").append(unique)
                .append(dialect.indexOrganizationClause(index)).append("INDEX")
                .append(dialect.indexCreateModifier(index)).append(" ")
                .append(dialect.qualifyIndexName(table.qualifiedName(), name)).append(" ON ")
                .append(qualifiedName(table.qualifiedName())).append("(").append(columns).append(")");
        String includeColumns = index.includeColumns().isEmpty()
                ? null : identifiers(index.includeColumns());
        String indexTablespace = option(index, table, "INDEX_TABLESPACE")
                .orElseGet(() -> dialect.defaultIndexTablespace(table.qualifiedName()));
        String activeIndexPlacement = dialect.indexTablespaceClause(indexTablespace);
        List<Identifier> physicalKeyColumns = index.columns().stream()
                .filter(column -> !column.expressionBased())
                .map(IndexColumn::column)
                .toList();
        String physicalIndexComment = physicalCommentRenderer.indexOptions(
                table, index, physicalKeyColumns, !activeIndexPlacement.isBlank(),
                index.type() == IndexType.UNIQUE);
        sql.append(dialect.indexTailWithPhysical(
                includeColumns, physicalIndexComment, indexTablespace, index.predicate(), index));
        String statement = sql.append(dialect.statementTerminator()).toString();
        String buildReview = dialect.indexBuildReviewComment(index);
        return buildReview == null || buildReview.isBlank()
                ? statement
                : buildReview + NL + statement;
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

    private static String normalizeCommentText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private void addComments(List<String> statements, Table table) {
        if (dialect.commentsInline()) {
            return;
        }
        String tableComment = table.persianName().isEmpty()
                ? table.description().value()
                : table.persianName().value();
        if (dialect.supports(DialectFeature.TABLE_COMMENT) && !tableComment.isBlank()) {
            statements.add(dialect.tableCommentStatement(
                    table.qualifiedName(), tableComment));
        }
        if (!dialect.supports(DialectFeature.COLUMN_COMMENT)) {
            return;
        }
        for (Column column : table.columns()) {
            if (!column.description().isEmpty()) {
                statements.add(dialect.columnCommentStatement(
                        table.qualifiedName(), column.name(), column.description().value()));
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
                // Canonical option format: SELECT, INSERT, UPDATE, DELETE TO APP_ROLE
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

    private List<Sequence> emittedSequences(DatabaseSchema schema) {
        Map<String, Sequence> sequences = new LinkedHashMap<>();
        for (Sequence sequence : schema.sequences()) {
            if (dialect.emitSequence(schema, sequence)) {
                sequences.put(sequence.qualifiedName().toString().toUpperCase(Locale.ROOT), sequence);
            }
        }
        if (dialect.identityUsesNamedSequence()) {
            for (Table table : schema.tables()) {
                for (Column column : identityColumnsWithoutDefault(table)) {
                    QualifiedName name = identitySequenceName(table, column);
                    sequences.putIfAbsent(
                            name.toString().toUpperCase(Locale.ROOT),
                            new Sequence(name, 1, 1, null, null, false, null, Description.empty()));
                }
            }
        }
        return List.copyOf(sequences.values());
    }

    private QualifiedName identitySequenceName(Table table, Column column) {
        boolean multipleIdentityColumns = identityColumnsWithoutDefault(table).size() > 1;
        return dialect.identitySequenceName(
                table.qualifiedName(), column, multipleIdentityColumns);
    }

    private List<Column> identityColumnsWithoutDefault(Table table) {
        return table.columns().stream()
                .filter(Column::identity)
                .filter(column -> !column.generated())
                .filter(column -> !column.defaultValue().isPresent())
                .toList();
    }

    private String summary(DatabaseSchema schema) {
        int schemaCount = generatedObjectSchemas(schema).size();
        int tableCount = schema.tables().size();
        int columnCount = schema.tables().stream().mapToInt(table -> table.columns().size()).sum();
        int primaryKeyCount = (int) schema.tables().stream().filter(table -> table.primaryKey().isPresent()).count();
        int uniqueCount = schema.tables().stream().mapToInt(table -> table.uniqueKeys().size()).sum();
        int checkCount = schema.tables().stream().mapToInt(table -> table.checkConstraints().size()).sum();
        int foreignKeyCount = schema.tables().stream().mapToInt(table -> table.foreignKeys().size()).sum();
        int physicalForeignKeyCount = schema.tables().stream()
                .mapToInt(table -> (int) table.foreignKeys().stream().filter(ForeignKey::physicalReference).count())
                .sum();
        int logicalForeignKeyCount = foreignKeyCount - physicalForeignKeyCount;
        int indexCount = schema.tables().stream().mapToInt(table -> table.indexes().size()).sum();
        int enforcingIndexCount = dialect.requiresExplicitConstraintIndexes()
                ? primaryKeyCount + uniqueCount
                : 0;
        int emittedIndexCount = indexCount + enforcingIndexCount;
        return "/*" + NL
                + "SchemaForge Object Summary" + NL
                + "Schemas      : " + schemaCount + NL
                + "Sequences    : " + emittedSequences(schema).size() + NL
                + "Tables       : " + tableCount + NL
                + "Columns      : " + columnCount + NL
                + "Primary Keys : " + primaryKeyCount + NL
                + "Unique Keys  : " + uniqueCount + NL
                + "Checks       : " + checkCount + NL
                + "Foreign Keys : " + foreignKeyCount + NL
                + "Physical FKs : " + physicalForeignKeyCount + NL
                + "Logical FKs  : " + logicalForeignKeyCount + NL
                + "Indexes      : " + emittedIndexCount + NL
                + (enforcingIndexCount == 0 ? ""
                        : "Enforcing    : " + enforcingIndexCount + NL)
                + "*/";
    }

    private List<Identifier> generatedObjectSchemas(DatabaseSchema schema) {
        Map<String, Identifier> schemas = new LinkedHashMap<>();
        for (Sequence sequence : schema.sequences()) {
            addSchema(schemas, sequence.qualifiedName().schemaName().orElse(schema.name()));
        }
        for (Table table : schema.tables()) {
            addSchema(schemas, table.qualifiedName().schemaName().orElse(schema.name()));
        }
        if (schemas.isEmpty()) {
            addSchema(schemas, schema.name());
        }
        return List.copyOf(schemas.values());
    }

    private void addSchema(Map<String, Identifier> schemas, Identifier schema) {
        schemas.putIfAbsent(schema.normalized(), schema);
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

    private Index constraintPhysicalIndex(
            Identifier name, List<Identifier> columns, Map<String, String> physicalOptions) {
        List<IndexColumn> indexColumns = columns.stream()
                .map(column -> new IndexColumn(column, SortDirection.ASC))
                .toList();
        return new Index(name, indexColumns, IndexType.UNIQUE, Description.empty(),
                List.of(), null, physicalOptions);
    }

    private java.util.Optional<String> option(Index index, Table table, String... keys) {
        if (index != null && keys != null) {
            for (String key : keys) {
                if (key == null || key.isBlank()) continue;
                java.util.Optional<String> value = index.physicalOptions().entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                        .map(Map.Entry::getValue)
                        .filter(raw -> raw != null && !raw.isBlank())
                        .map(String::trim)
                        .findFirst();
                if (value.isPresent()) return value;
            }
        }
        if (keys != null) {
            for (String key : keys) {
                java.util.Optional<String> value = option(table, key);
                if (value.isPresent()) return value;
            }
        }
        return java.util.Optional.empty();
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

}
