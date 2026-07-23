package com.behsazan.schemaforge.dialect.oracle;

import com.behsazan.schemaforge.dialect.DatabaseDialect;
import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.DatabaseProduct;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class OracleDialect implements DatabaseDialect {
    @Override
    public DatabaseProduct product() {
        return DatabaseProduct.ORACLE;
    }

    @Override
    public String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String sqlType(ColumnDefinition column) {
        return switch (column.logicalType()) {
            case "STRING", "VARCHAR", "VARCHAR2" -> "VARCHAR2(" + defaultLength(column.length()) + ")";
            case "INTEGER", "LONG" -> "NUMBER(19)";
            case "DECIMAL", "NUMBER" -> decimal(column);
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            case "BOOLEAN" -> "NUMBER(1)";
            default -> throw new IllegalArgumentException("Unsupported Oracle type: " + column.logicalType());
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
            return "NUMBER";
        }
        return column.scale() == null
                ? "NUMBER(" + column.precision() + ")"
                : "NUMBER(" + column.precision() + "," + column.scale() + ")";
    }
}
