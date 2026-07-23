package com.behsazan.schemaforge.dialect.postgresql;

import com.behsazan.schemaforge.dialect.DatabaseDialect;
import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.DatabaseProduct;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PostgreSqlDialect implements DatabaseDialect {
    @Override
    public DatabaseProduct product() {
        return DatabaseProduct.POSTGRESQL;
    }

    @Override
    public String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String sqlType(ColumnDefinition column) {
        return switch (column.logicalType()) {
            case "STRING", "VARCHAR", "VARCHAR2" -> "VARCHAR(" + defaultLength(column.length()) + ")";
            case "INTEGER" -> "INTEGER";
            case "LONG" -> "BIGINT";
            case "DECIMAL", "NUMBER" -> decimal(column);
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            case "BOOLEAN" -> "BOOLEAN";
            default -> throw new IllegalArgumentException("Unsupported PostgreSQL type: " + column.logicalType());
        };
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private int defaultLength(Integer length) {
        return length == null ? 255 : length;
    }

    private String decimal(ColumnDefinition column) {
        if (column.precision() == null) {
            return "NUMERIC";
        }
        return column.scale() == null
                ? "NUMERIC(" + column.precision() + ")"
                : "NUMERIC(" + column.precision() + "," + column.scale() + ")";
    }
}
