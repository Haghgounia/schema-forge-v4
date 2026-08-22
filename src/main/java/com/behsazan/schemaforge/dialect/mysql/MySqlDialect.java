package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * MySQL logical DDL dialect.
 *
 * <p>P1 activates MySQL in the platform/factory path while deliberately keeping
 * metadata/JDBC and physical tuning out of scope. The dialect therefore renders
 * only evidence-safe logical constructs and rejects unsupported semantics rather
 * than translating them approximately.</p>
 */
public final class MySqlDialect implements Dialect {
    private static final int UTF8MB4_MAX_BYTES_PER_CHARACTER = 4;
    private static final int MYSQL_MAX_LOGICAL_ROW_BYTES = 65_535;
    private static final int MYSQL_TEXT_MAX_BYTES = 65_535;
    private static final int MYSQL_MEDIUMTEXT_MAX_BYTES = 16_777_215;
    private static final int OFF_ROW_POINTER_BUDGET_BYTES = 12;
    private static final int VARCHAR_LENGTH_PREFIX_BYTES = 2;
    private static final int MAX_UTF8MB4_VARCHAR_CHARACTERS =
            (MYSQL_MAX_LOGICAL_ROW_BYTES - VARCHAR_LENGTH_PREFIX_BYTES) / UTF8MB4_MAX_BYTES_PER_CHARACTER;

    private static final Set<DialectFeature> FEATURES = Set.of(
            DialectFeature.IDENTITY_COLUMN,
            DialectFeature.GENERATED_COLUMN,
            DialectFeature.TABLE_COMMENT,
            DialectFeature.COLUMN_COMMENT,
            DialectFeature.GRANT,
            DialectFeature.EXPRESSION_INDEX);

    private final MySqlTypeMapper typeMapper = new MySqlTypeMapper();
    private final MySqlIdentifierRenderer identifierRenderer = new MySqlIdentifierRenderer();
    private final MySqlExpressionMapper expressionMapper = new MySqlExpressionMapper();

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public Set<DialectFeature> supportedFeatures() {
        return FEATURES;
    }

    @Override
    public String sqlType(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        String mapped = typeMapper.map(column.dataType());
        if (!column.identity()) {
            return mapped;
        }
        if (autoIncrementCompatible(mapped)) {
            return mapped;
        }
        String safeInteger = losslessIdentityInteger(column.dataType());
        if (safeInteger != null) {
            return safeInteger;
        }
        throw new IllegalArgumentException(
                "MySQL AUTO_INCREMENT requires an integer column; no lossless integer mapping exists for "
                        + column.dataType() + " on " + column.name().value());
    }

    @Override
    public String sqlType(Table table, Column column) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(column, "column must not be null");
        if (textStoragePromotions(table).contains(column.name().normalized())) {
            return promotedTextType(column);
        }
        return sqlType(column);
    }

    @Override
    public String inlineColumnConstraintClause(Table table, Column column) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(column, "column must not be null");
        if (!textStoragePromotions(table).contains(column.name().normalized())) {
            return "";
        }
        int logicalLength = Objects.requireNonNull(column.dataType().length());
        String source = column.dataType().name().normalized().toUpperCase(Locale.ROOT);
        String target = promotedTextType(column);
        return " CHECK (CHAR_LENGTH(" + quote(column.name()) + ") <= " + logicalLength + ")"
                + " /* SchemaForge MySQL storage adaptation: " + source + "(" + logicalLength
                + ") -> " + target + "; logical max length preserved */";
    }

    @Override
    public void validateTable(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        List<Column> identities = table.columns().stream().filter(Column::identity).toList();
        if (identities.size() > 1) {
            throw new IllegalArgumentException(
                    "MySQL permits only one AUTO_INCREMENT column per table: " + table.qualifiedName());
        }
        if (identities.isEmpty()) {
            return;
        }
        Column identity = identities.get(0);
        // Forces the lossless type check before any SQL is rendered.
        sqlType(identity);
        if (!isLeftmostIndexed(table, identity.name())) {
            throw new IllegalArgumentException(
                    "MySQL AUTO_INCREMENT column must be the first or only column of an index: "
                            + table.qualifiedName() + "." + identity.name().value());
        }
    }

    @Override
    public boolean emitSequence(DatabaseSchema schema, Sequence sequence) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(sequence, "sequence must not be null");

        boolean identityReference = false;
        boolean nonIdentityReference = false;
        for (Table table : schema.tables()) {
            for (Column column : table.columns()) {
                if (!column.defaultValue().isPresent()) {
                    continue;
                }
                String expression = column.defaultValue().expression();
                if (!referencesSequence(expression, sequence)) {
                    continue;
                }
                if (column.identity()) {
                    identityReference = true;
                } else {
                    nonIdentityReference = true;
                }
            }
        }
        // WordSpecificationParser materializes a backing SEQ_<TABLE> for logical IDENTITY.
        // MySQL implements that same identity intent with AUTO_INCREMENT, so this parser-
        // generated sequence is suppressed only when it is used exclusively by identity columns.
        return !identityReference || nonIdentityReference;
    }

    @Override
    public String quote(Identifier identifier) {
        return identifierRenderer.render(identifier);
    }

    @Override
    public String expression(String expression) {
        return expressionMapper.map(expression);
    }

    @Override
    public String identityClause(Column column) {
        return " AUTO_INCREMENT";
    }

    @Override
    public String defaultClause(Column column) {
        // Legacy Word preserves IDENTITY together with an Oracle sequence NEXTVAL default.
        // MySQL implements the logical identity directly and must not emit a DEFAULT here.
        if (column.identity()) {
            return identityClause(column);
        }
        String sourceExpression = column.defaultValue().expression();
        // MySQL rejects an explicit DEFAULT NULL on a NOT NULL column (ER_INVALID_DEFAULT).
        // Omitting that contradictory explicit default preserves the effective strict-mode
        // behavior: callers still must supply a non-null value. A quoted string literal
        // such as 'NULL' is intentionally not suppressed.
        if (!column.nullable() && sourceExpression != null
                && sourceExpression.trim().equalsIgnoreCase("NULL")) {
            return "";
        }
        return " DEFAULT " + expression(sourceExpression);
    }

    @Override
    public String generatedColumnClause(Column column) {
        return " GENERATED ALWAYS AS (" + expression(column.generatedExpression()) + ") STORED";
    }

    @Override
    public boolean commentsInline() {
        return true;
    }

    @Override
    public String inlineColumnCommentClause(Column column) {
        if (column.description().isEmpty()) {
            return "";
        }
        return " COMMENT '" + escapeLiteral(column.description().value()) + "'";
    }

    @Override
    public String inlineTableCommentClause(String comment) {
        if (comment == null || comment.isBlank()) {
            return "";
        }
        return " COMMENT='" + escapeLiteral(comment) + "'";
    }

    @Override
    public String qualifyIndexName(QualifiedName tableName, String renderedIndexName) {
        // MySQL index names belong to the table namespace and are not schema-qualified.
        return renderedIndexName;
    }

    @Override
    public String tableTablespaceClause(String tablespace) {
        // Physical placement is intentionally deferred to the MySQL physical phase.
        return "";
    }

    @Override
    public String indexTablespaceClause(String tablespace) {
        // MySQL CREATE INDEX placement is not mapped from cross-DBMS TABLESPACE evidence in P1.
        return "";
    }

    @Override
    public String referentialActionClause(String clause, ReferentialAction action) {
        if (action == ReferentialAction.SET_DEFAULT) {
            throw new UnsupportedOperationException(
                    "MySQL does not emit SET DEFAULT referential actions");
        }
        return Dialect.super.referentialActionClause(clause, action);
    }

    @Override
    public String schemaBootstrapStatement(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        // In MySQL, SCHEMA is a synonym for DATABASE. Qualified table names therefore use
        // the canonical schema as the database name and bootstrap it idempotently.
        return "CREATE DATABASE IF NOT EXISTS " + quote(schemaName) + statementTerminator();
    }

    private Set<String> textStoragePromotions(Table table) {
        LinkedHashSet<String> promoted = new LinkedHashSet<>();

        for (Column column : table.columns()) {
            if (!isVariableCharacter(column)) {
                continue;
            }
            Integer length = column.dataType().length();
            if (length != null && length > MAX_UTF8MB4_VARCHAR_CHARACTERS) {
                requireTextPromotionEligible(table, column,
                        "declared character length exceeds the utf8mb4 VARCHAR capacity of "
                                + MAX_UTF8MB4_VARCHAR_CHARACTERS);
                promoted.add(column.name().normalized());
            }
        }

        long remainingVarcharBytes = table.columns().stream()
                .filter(this::isVariableCharacter)
                .mapToLong(column -> promoted.contains(column.name().normalized())
                        ? OFF_ROW_POINTER_BUDGET_BYTES
                        : varcharMaximumBytes(column))
                .sum();

        if (remainingVarcharBytes <= MYSQL_MAX_LOGICAL_ROW_BYTES) {
            return Set.copyOf(promoted);
        }

        List<Column> candidates = new ArrayList<>(table.columns().stream()
                .filter(this::isVariableCharacter)
                .filter(column -> !promoted.contains(column.name().normalized()))
                .filter(column -> textPromotionEligible(table, column))
                .toList());
        candidates.sort(Comparator
                .comparingLong(this::varcharMaximumBytes).reversed()
                .thenComparing(column -> column.ordinalPosition() == null ? Integer.MAX_VALUE : column.ordinalPosition())
                .thenComparing(column -> column.name().normalized()));

        for (Column candidate : candidates) {
            promoted.add(candidate.name().normalized());
            remainingVarcharBytes -= Math.max(0, varcharMaximumBytes(candidate) - OFF_ROW_POINTER_BUDGET_BYTES);
            if (remainingVarcharBytes <= MYSQL_MAX_LOGICAL_ROW_BYTES) {
                break;
            }
        }

        if (remainingVarcharBytes > MYSQL_MAX_LOGICAL_ROW_BYTES) {
            throw new IllegalArgumentException(
                    "MySQL row-size adaptation cannot preserve indexed/key/defaulted VARCHAR semantics for "
                            + table.qualifiedName() + "; remaining declared utf8mb4 VARCHAR budget="
                            + remainingVarcharBytes + " bytes");
        }
        return Set.copyOf(promoted);
    }

    private boolean isVariableCharacter(Column column) {
        String source = column.dataType().name().normalized().toUpperCase(Locale.ROOT);
        return Set.of("VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2").contains(source)
                && column.dataType().length() != null
                && column.dataType().length() > 0;
    }

    private long varcharMaximumBytes(Column column) {
        return (long) column.dataType().length() * UTF8MB4_MAX_BYTES_PER_CHARACTER
                + VARCHAR_LENGTH_PREFIX_BYTES;
    }

    private String promotedTextType(Column column) {
        long maximumBytes = (long) column.dataType().length() * UTF8MB4_MAX_BYTES_PER_CHARACTER;
        if (maximumBytes <= MYSQL_TEXT_MAX_BYTES) {
            return "TEXT";
        }
        if (maximumBytes <= MYSQL_MEDIUMTEXT_MAX_BYTES) {
            return "MEDIUMTEXT";
        }
        return "LONGTEXT";
    }

    private void requireTextPromotionEligible(Table table, Column column, String reason) {
        if (!textPromotionEligible(table, column)) {
            throw new IllegalArgumentException(
                    "MySQL requires off-row text storage for " + table.qualifiedName() + "."
                            + column.name().value() + " because " + reason
                            + ", but the column participates in key/index/FK/default/generated semantics; "
                            + "SchemaForge will not guess a prefix index or alter those semantics");
        }
    }

    private boolean textPromotionEligible(Table table, Column column) {
        if (column.identity() || column.generated() || column.defaultValue().isPresent()) {
            return false;
        }
        String name = column.name().normalized();
        if (table.primaryKey().isPresent()
                && table.primaryKey().get().columns().stream().anyMatch(id -> id.normalized().equals(name))) {
            return false;
        }
        if (table.uniqueKeys().stream()
                .flatMap(key -> key.columns().stream())
                .anyMatch(id -> id.normalized().equals(name))) {
            return false;
        }
        if (table.foreignKeys().stream()
                .flatMap(fk -> fk.columns().stream())
                .anyMatch(id -> id.normalized().equals(name))) {
            return false;
        }
        return table.indexes().stream().noneMatch(index ->
                index.columns().stream().anyMatch(indexColumn ->
                        !indexColumn.expressionBased() && indexColumn.column().normalized().equals(name))
                        || index.includeColumns().stream().anyMatch(id -> id.normalized().equals(name)));
    }

    private boolean autoIncrementCompatible(String mappedType) {
        String base = mappedType.replaceFirst("\\(.*$", "").trim().toUpperCase(Locale.ROOT);
        return Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT").contains(base);
    }

    private String losslessIdentityInteger(DataType type) {
        String source = type.name().normalized().toUpperCase(Locale.ROOT);
        if (!Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC").contains(source)) {
            return null;
        }
        Integer precision = type.precision();
        int scale = type.scale() == null ? 0 : type.scale();
        if (precision == null || scale != 0 || precision < 1 || precision > 18) {
            return null;
        }
        // Signed BIGINT covers the full signed range of every exact decimal with <=18 digits.
        return "BIGINT";
    }

    private boolean isLeftmostIndexed(Table table, Identifier column) {
        if (table.primaryKey().isPresent()
                && first(table.primaryKey().get().columns(), column)) {
            return true;
        }
        if (table.uniqueKeys().stream().anyMatch(key -> first(key.columns(), column))) {
            return true;
        }
        for (Index index : table.indexes()) {
            if (index.columns().isEmpty()) {
                continue;
            }
            IndexColumn first = index.columns().get(0);
            if (!first.expressionBased() && first.column().normalized().equals(column.normalized())) {
                return true;
            }
        }
        return false;
    }

    private boolean first(List<Identifier> columns, Identifier expected) {
        return !columns.isEmpty() && columns.get(0).normalized().equals(expected.normalized());
    }

    private boolean referencesSequence(String expression, Sequence sequence) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String normalized = normalizeExpression(expression);
        String qualified = normalizeExpression(sequence.qualifiedName().toString());
        String simple = normalizeExpression(sequence.qualifiedName().name().value());
        return normalized.contains(qualified + ".NEXTVAL")
                || normalized.contains(simple + ".NEXTVAL");
    }

    private String normalizeExpression(String value) {
        return value.toUpperCase(Locale.ROOT)
                .replace("`", "")
                .replace("\"", "")
                .replaceAll("\\s+", "");
    }

    private String escapeLiteral(String value) {
        // Doubling quotes is accepted by MySQL unless NO_BACKSLASH_ESCAPES changes only
        // backslash handling; this representation does not depend on backslashes.
        return value.replace("'", "''");
    }
}
