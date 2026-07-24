package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;

/** Oracle-specific type and identifier rendering. */
public final class OracleDialect implements Dialect {

    @Override
    public String sqlType(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        DataType type = column.dataType();
        String name = type.name().normalized();

        String oracleName = switch (name) {
            case "VARCHAR", "VARCHAR2" -> "VARCHAR2";
            case "NVARCHAR", "NVARCHAR2" -> "NVARCHAR2";
            case "NUMERIC", "DECIMAL", "NUMBER" -> "NUMBER";
            case "INT", "INTEGER", "BIGINT", "SMALLINT" -> "NUMBER";
            case "DOUBLE", "DOUBLE PRECISION" -> "BINARY_DOUBLE";
            case "REAL" -> "BINARY_FLOAT";
            default -> name.toUpperCase(Locale.ROOT);
        };

        if (type.length() != null) {
            return oracleName + "(" + type.length() + (oracleName.equals("VARCHAR2") || oracleName.equals("NVARCHAR2") || oracleName.equals("CHAR") || oracleName.equals("NCHAR") ? " CHAR" : "") + ")";
        }
        if (type.precision() != null) {
            if (type.scale() != null) {
                return oracleName + "(" + type.precision() + "," + type.scale() + ")";
            }
            return oracleName + "(" + type.precision() + ")";
        }
        return oracleName;
    }

    @Override
    public String quote(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        return identifier.normalized();
    }
}
