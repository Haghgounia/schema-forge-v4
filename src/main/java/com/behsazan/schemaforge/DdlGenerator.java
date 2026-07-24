package com.behsazan.schemaforge;

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

    public DdlGenerator(Dialect dialect) {
        this(dialect, Clock.systemDefaultZone());
    }

    public DdlGenerator(Dialect dialect, Clock clock) {
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns one SQL text containing all sequences, tables, constraints, indexes, comments and grants. */
    public String generate(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");

        List<String> statements = new ArrayList<>();
        schema.sequences().stream()
                .sorted(Comparator.comparing(sequence -> sequence.qualifiedName().toString()))
                .map(this::createSequence)
                .forEach(statements::add);

        for (Table table : schema.tables()) {
            statements.add(createTable(table));
            table.checkConstraints().stream().map(check -> createCheck(table, check)).forEach(statements::add);
            table.uniqueKeys().stream().map(unique -> createUnique(table, unique)).forEach(statements::add);
            table.foreignKeys().stream().map(foreignKey -> createForeignKey(table, foreignKey)).forEach(statements::add);
            table.indexes().stream().map(index -> createIndex(table, index)).forEach(statements::add);
            addComments(statements, table);
            addGrants(statements, table);
        }

        String body = statements.stream()
                .filter(statement -> statement != null && !statement.isBlank())
                .collect(Collectors.joining(NL + NL));
        String warnings = warningHeader(schema);
        return (warnings.isBlank() ? "" : warnings + NL + NL)
                + body + NL + NL + footer(schema);
    }

    private String createSequence(Sequence sequence) {
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ")
                .append(qualifiedName(sequence.qualifiedName()))
                .append(" INCREMENT BY ").append(sequence.incrementBy());
        if (sequence.maxValue() != null) sql.append(" MAXVALUE ").append(sequence.maxValue());
        if (sequence.minValue() != null) sql.append(" MINVALUE ").append(sequence.minValue());
        sql.append(sequence.cacheSize() == null || sequence.cacheSize() == 0
                ? " NOCACHE"
                : " CACHE " + sequence.cacheSize());
        sql.append(sequence.cycle() ? " CYCLE" : " NOCYCLE");
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String createTable(Table table) {
        List<Column> columns = new ArrayList<>(table.columns());
        columns.sort(Comparator.comparing(Column::ordinalPosition, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> definitions = columns.stream().map(this::columnDefinition).collect(Collectors.toCollection(ArrayList::new));
        table.primaryKey().map(primaryKey -> primaryKeyDefinition(table, primaryKey)).ifPresent(definitions::add);

        StringBuilder sql = new StringBuilder("CREATE TABLE ")
                .append(qualifiedName(table.qualifiedName())).append(NL)
                .append("(").append(NL)
                .append(String.join("," + NL, definitions)).append(NL)
                .append(")");
        option(table, "TABLESPACE").ifPresent(value -> sql.append(" TABLESPACE ").append(value));
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String columnDefinition(Column column) {
        StringBuilder sql = new StringBuilder("  ")
                .append(dialect.quote(column.name())).append(" ")
                .append(dialect.sqlType(column));
        if (column.generated()) {
            sql.append(" AS (").append(column.generatedExpression()).append(") VIRTUAL");
        } else if (column.identity()) {
            sql.append(" GENERATED BY DEFAULT AS IDENTITY");
        } else if (column.defaultValue().isPresent()) {
            sql.append(" DEFAULT ").append(column.defaultValue().expression());
        }
        if (!column.nullable()) sql.append(" NOT NULL");
        return sql.toString();
    }

    private String primaryKeyDefinition(Table table, PrimaryKey primaryKey) {
        String constraintName = primaryKey.name() == null
                ? "PK_" + table.qualifiedName().name().normalized()
                : dialect.quote(primaryKey.name());
        String indexTablespace = option(table, "INDEX_TABLESPACE")
                .orElseGet(() -> option(table, "PK_TABLESPACE").orElse(null));
        String tableName = qualifiedName(table.qualifiedName());
        String columns = identifiers(primaryKey.columns());

        StringBuilder sql = new StringBuilder("CONSTRAINT ").append(constraintName)
                .append(" PRIMARY KEY (").append(columns).append(")")
                .append(NL).append("USING INDEX (CREATE UNIQUE INDEX ")
                .append(qualifyLikeTable(table, constraintName)).append(" ON ")
                .append(tableName).append("(").append(columns).append(")");
        if (indexTablespace != null && !indexTablespace.isBlank()) sql.append(NL).append("TABLESPACE ").append(indexTablespace.trim());
        return sql.append(")").toString();
    }

    private String createCheck(Table table, CheckConstraint check) {
        String name = check.name() == null
                ? "CHK_" + table.qualifiedName().name().normalized()
                : dialect.quote(check.name());
        return "ALTER TABLE " + qualifiedName(table.qualifiedName())
                + " ADD CONSTRAINT " + name
                + " CHECK(" + check.expression() + ") ENABLE" + dialect.statementTerminator();
    }

    private String createUnique(Table table, UniqueKey unique) {
        String name = unique.name() == null
                ? "UK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(unique.columns())
                : dialect.quote(unique.name());
        String columns = identifiers(unique.columns());
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualifiedName(table.qualifiedName()))
                .append(" ADD CONSTRAINT ").append(name).append(" UNIQUE(").append(columns).append(")")
                .append(NL).append(" USING INDEX (CREATE UNIQUE INDEX ")
                .append(qualifyLikeTable(table, name)).append(" ON ")
                .append(qualifiedName(table.qualifiedName())).append("(").append(columns).append(")");
        option(table, "INDEX_TABLESPACE").ifPresent(value -> sql.append(" TABLESPACE ").append(value));
        return sql.append(")").append(dialect.statementTerminator()).toString();
    }

    private String createForeignKey(Table table, ForeignKey foreignKey) {
        String name = foreignKey.name() == null
                ? "FK_" + table.qualifiedName().name().normalized() + "_" + rawIdentifiers(foreignKey.columns())
                : dialect.quote(foreignKey.name());
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualifiedName(table.qualifiedName()))
                .append(" ADD CONSTRAINT ").append(name)
                .append(" FOREIGN KEY (").append(identifiers(foreignKey.columns())).append(")")
                .append(" REFERENCES ").append(qualifiedName(foreignKey.referencedTable()))
                .append("(").append(identifiers(foreignKey.referencedColumns())).append(")");
        if (foreignKey.onDelete() == ReferentialAction.CASCADE) sql.append(" ON DELETE CASCADE");
        else if (foreignKey.onDelete() == ReferentialAction.SET_NULL) sql.append(" ON DELETE SET NULL");
        return sql.append(" ENABLE").append(dialect.statementTerminator()).toString();
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
        option(table, "INDEX_TABLESPACE").ifPresent(value -> sql.append(" TABLESPACE ").append(value));
        return sql.append(dialect.statementTerminator()).toString();
    }

    private String indexColumn(IndexColumn indexColumn) {
        String value = dialect.quote(indexColumn.column());
        return indexColumn.direction() == SortDirection.DESC ? value + " DESC" : value;
    }

    private void addComments(List<String> statements, Table table) {
        if (!table.description().isEmpty()) {
            statements.add("COMMENT ON TABLE " + qualifiedName(table.qualifiedName())
                    + " IS '" + escapeLiteral(table.description().value()) + "'" + dialect.statementTerminator());
        }
        for (Column column : table.columns()) {
            if (!column.description().isEmpty()) {
                statements.add("COMMENT ON COLUMN " + qualifiedName(table.qualifiedName()) + "." + dialect.quote(column.name())
                        + " IS '" + escapeLiteral(column.description().value()) + "'" + dialect.statementTerminator());
            }
        }
    }

    private void addGrants(List<String> statements, Table table) {
        option(table, "GRANTS").ifPresent(value -> {
            for (String grant : value.split("[;\\r\\n]+")) {
                String trimmed = grant.trim();
                if (trimmed.isEmpty()) continue;
                // Format: SELECT, INSERT, UPDATE, DELETE TO U_DEVELOPER
                statements.add("GRANT " + trimmed + " ON " + qualifiedName(table.qualifiedName()) + dialect.statementTerminator());
            }
        });
    }

    private String warningHeader(DatabaseSchema schema) {
        String rawWarnings = firstMetadata(schema.metadata(), "recovery.warnings");
        if (rawWarnings == null || rawWarnings.isBlank()) {
            return "";
        }

        List<String> duplicateWarnings = rawWarnings.lines()
                .filter(line -> line.startsWith("DUPLICATE_COLUMN|"))
                .toList();
        if (duplicateWarnings.isEmpty()) {
            return "";
        }

        StringBuilder sql = new StringBuilder();
        for (String warning : duplicateWarnings) {
            Map<String, String> values = parseWarning(warning);
            String name = values.getOrDefault("name", "UNKNOWN");
            String firstRow = values.getOrDefault("firstRow", "?");
            String duplicateRow = values.getOrDefault("duplicateRow", "?");
            String definition = values.getOrDefault("definition", name);

            if (!sql.isEmpty()) {
                sql.append(NL);
            }
            sql.append("PROMPT **************************************************************").append(NL)
                    .append("PROMPT SCHEMAFORGE WARNING : DUPLICATE COLUMN DEFINITION").append(NL)
                    .append("PROMPT COLUMN              : ").append(name).append(NL)
                    .append("PROMPT FIRST WORD ROW      : ").append(firstRow).append(NL)
                    .append("PROMPT DUPLICATE WORD ROW  : ").append(duplicateRow).append(NL)
                    .append("PROMPT ACTION              : FIRST DEFINITION IS EXECUTABLE;").append(NL)
                    .append("PROMPT                       DUPLICATE IS SHOWN BELOW AS COMMENT.").append(NL)
                    .append("PROMPT **************************************************************").append(NL)
                    .append("-- DUPLICATE DEFINITION (NOT EXECUTABLE):").append(NL)
                    .append("-- ").append(definition).append(dialect.statementTerminator());
        }
        return sql.toString();
    }

    private Map<String, String> parseWarning(String warning) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String part : warning.split("\\|")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        return values;
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
        return columns.stream().map(column -> column.column().normalized()).collect(Collectors.joining("_"));
    }

    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
