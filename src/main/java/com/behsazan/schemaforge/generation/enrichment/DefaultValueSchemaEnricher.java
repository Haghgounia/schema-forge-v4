package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Performs conservative, DBMS-independent default-value normalization.
 *
 * <p>Only an unquoted bare identifier on a character column is rewritten, and only when it is not
 * a known SQL context expression. This fixes legacy defaults such as {@code ACTIVE -> 'ACTIVE'}
 * while preserving expressions such as {@code CURRENT_TIMESTAMP}, {@code SYSDATE},
 * {@code SYSTIMESTAMP}, {@code USER}, function calls and sequence expressions.</p>
 */
public final class DefaultValueSchemaEnricher implements SchemaEnricher {
    private static final Pattern BARE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final Set<String> SQL_BARE_EXPRESSIONS = Set.of(
            "NULL",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "CURRENT_TIMESTAMP",
            "LOCALTIME",
            "LOCALTIMESTAMP",
            "SYSDATE",
            "SYSTIMESTAMP",
            "USER",
            "CURRENT_USER",
            "SESSION_USER",
            "SYSTEM_USER",
            "CURRENT_SCHEMA",
            "CURRENT_ROLE");

    @Override
    public DatabaseSchema enrich(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        DatabaseSchema.Builder result = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(result::metadata);
        schema.tables().stream().map(this::normalizeTable).forEach(result::addTable);
        schema.sequences().forEach(result::addSequence);
        return result.build();
    }

    private Table normalizeTable(Table table) {
        String schemaName = table.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        Table.Builder result = Table.builder(schemaName, table.qualifiedName().name().value())
                .persianName(table.persianName().value())
                .description(table.description().value());
        for (Column column : table.columns()) result.addColumn(normalizeColumn(column));
        table.primaryKey().ifPresent(result::primaryKey);
        table.foreignKeys().forEach(result::addForeignKey);
        table.uniqueKeys().forEach(result::addUniqueKey);
        table.checkConstraints().forEach(result::addCheck);
        table.indexes().forEach(result::addIndex);
        table.physicalOptions().forEach(result::physicalOption);
        return result.build();
    }

    private Column normalizeColumn(Column column) {
        DefaultValue current = column.defaultValue();
        if (!current.isPresent()) return column;
        String expression = current.expression();
        String normalized = normalizeExpression(column, expression);
        if (expression.equals(normalized)) return column;
        return new Column(
                column.name(),
                column.dataType(),
                column.nullable(),
                new DefaultValue(normalized),
                column.description(),
                column.identity(),
                column.ordinalPosition(),
                column.generatedExpression(),
                column.physicalOptions());
    }

    private String normalizeExpression(Column column, String expression) {
        String value = expression.trim();
        if (!isCharacterType(column)) return value;
        if (isQuotedLiteral(value)) return value;
        if (!BARE_IDENTIFIER.matcher(value).matches()) return value;
        if (SQL_BARE_EXPRESSIONS.contains(value.toUpperCase(Locale.ROOT))) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean isCharacterType(Column column) {
        String name = column.dataType().name().normalized();
        return name.contains("CHAR")
                || name.contains("TEXT")
                || name.contains("CLOB")
                || name.equals("STRING")
                || name.equals("CODE")
                || name.equals("HASH");
    }

    private boolean isQuotedLiteral(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) return true;
        return value.length() >= 3
                && (value.startsWith("N'") || value.startsWith("n'"))
                && value.endsWith("'");
    }
}
