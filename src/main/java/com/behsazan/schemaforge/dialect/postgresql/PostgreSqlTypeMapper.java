package com.behsazan.schemaforge.dialect.postgresql;

import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/** Maps canonical and Oracle-oriented data types to PostgreSQL data types. */
public final class PostgreSqlTypeMapper {

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);
        String targetName = switch (sourceName) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> "VARCHAR";
            case "CHAR", "NCHAR", "CHARACTER" -> "CHAR";
            case "NUMBER", "NUMERIC", "DECIMAL" -> "NUMERIC";
            case "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER" -> "INTEGER";
            case "BIGINT" -> "BIGINT";
            case "SMALLINT" -> "SMALLINT";
            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> "DOUBLE PRECISION";
            case "BINARY_FLOAT", "REAL" -> "REAL";
            case "CLOB", "NCLOB", "LONG" -> "TEXT";
            case "BLOB", "RAW", "LONG RAW", "LONG_RAW" -> "BYTEA";
            case "DATE" -> "TIMESTAMP";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
                    "TIMESTAMP_WITH_TIME_ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" -> "TIMESTAMP WITH TIME ZONE";
            case "XMLTYPE" -> "XML";
            case "JSON" -> "JSONB";
            case "BOOLEAN" -> "BOOLEAN";
            case "ROWID", "UROWID" -> "VARCHAR";
            default -> sourceName;
        };

        if (type.length() != null && supportsLength(targetName)) {
            return targetName + "(" + type.length() + ")";
        }
        if (type.precision() != null && supportsPrecision(targetName)) {
            if (type.scale() != null) {
                return targetName + "(" + type.precision() + "," + type.scale() + ")";
            }
            return targetName + "(" + type.precision() + ")";
        }
        return targetName;
    }

    private boolean supportsLength(String name) {
        return name.equals("VARCHAR") || name.equals("CHAR") || name.equals("CHARACTER");
    }

    private boolean supportsPrecision(String name) {
        return name.equals("NUMERIC") || name.equals("DECIMAL");
    }
}
