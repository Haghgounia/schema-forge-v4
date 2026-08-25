package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/**
 * Evidence-safe logical datatype mapper for the first MySQL foundation phase.
 * Unsupported or lossy mappings are rejected rather than guessed.
 */
public final class MySqlTypeMapper {
    public static final int MAX_DECIMAL_PRECISION = 65;
    public static final int MAX_DECIMAL_SCALE = 30;
    public static final int MAX_TEMPORAL_PRECISION = 6;

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String source = type.name().normalized().toUpperCase(Locale.ROOT);

        return switch (source) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" ->
                    variableCharacter("VARCHAR", type);
            case "CHAR", "NCHAR", "CHARACTER" -> fixedCharacter(type);
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> decimal(type);
            case "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER" -> "INT";
            case "BIGINT" -> "BIGINT";
            case "SMALLINT" -> "SMALLINT";
            case "TINYINT" -> "TINYINT";
            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION" -> "DOUBLE";
            case "BINARY_FLOAT", "FLOAT", "REAL" -> "FLOAT";
            case "CLOB", "NCLOB", "LONG", "TEXT" -> "LONGTEXT";
            case "TINYTEXT", "MEDIUMTEXT", "LONGTEXT" -> source;
            case "BLOB", "LONG RAW", "LONG_RAW" -> "LONGBLOB";
            case "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> source;
            case "RAW", "VARBINARY" -> binary(type);
            case "DATE" -> "DATETIME";
            case "TIMESTAMP", "DATETIME" -> temporal("DATETIME", type);
            case "TIME" -> temporal("TIME", type);
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "JSON" -> "JSON";
            case "XMLTYPE", "XML" -> "LONGTEXT";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
                    "TIMESTAMP_WITH_TIME_ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" ->
                    throw unsupported(type, "timezone-aware timestamp has no lossless MySQL logical mapping");
            case "ROWID", "UROWID" ->
                    throw unsupported(type, "Oracle ROWID semantics have no lossless MySQL logical mapping");
            default -> throw unsupported(type, "canonical datatype is not in MySQL foundation coverage");
        };
    }

    private String variableCharacter(String target, DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, target + " requires explicit length in SchemaForge MySQL foundation");
        }
        return target + "(" + type.length() + ")";
    }

    private String fixedCharacter(DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, "CHAR requires explicit length in SchemaForge MySQL foundation");
        }
        return "CHAR(" + type.length() + ")";
    }

    private String binary(DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, "VARBINARY requires explicit length in SchemaForge MySQL foundation");
        }
        return "VARBINARY(" + type.length() + ")";
    }

    private String decimal(DataType type) {
        if (type.precision() == null) {
            throw unsupported(type, "exact numeric precision is required for a lossless MySQL DECIMAL mapping");
        }
        int precision = type.precision();
        int scale = type.scale() == null ? 0 : type.scale();
        if (precision < 1 || precision > MAX_DECIMAL_PRECISION) {
            throw unsupported(type, "DECIMAL precision must be between 1 and " + MAX_DECIMAL_PRECISION);
        }
        if (scale < 0 || scale > MAX_DECIMAL_SCALE || scale > precision) {
            throw unsupported(type, "DECIMAL scale must be between 0 and "
                    + Math.min(MAX_DECIMAL_SCALE, precision));
        }
        return scale == 0 ? "DECIMAL(" + precision + ")"
                : "DECIMAL(" + precision + "," + scale + ")";
    }

    private String temporal(String target, DataType type) {
        if (type.precision() == null) return target;
        int precision = type.precision();
        if (precision < 0 || precision > MAX_TEMPORAL_PRECISION) {
            throw unsupported(type, target + " fractional seconds precision must be between 0 and "
                    + MAX_TEMPORAL_PRECISION);
        }
        return target + "(" + precision + ")";
    }

    private IllegalArgumentException unsupported(DataType type, String reason) {
        return new IllegalArgumentException("Unsupported MySQL logical mapping for " + type + ": " + reason);
    }
}
