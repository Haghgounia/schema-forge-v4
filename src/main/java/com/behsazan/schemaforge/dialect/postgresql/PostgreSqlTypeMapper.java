package com.behsazan.schemaforge.dialect.postgresql;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/** Maps canonical and Oracle-oriented data types to PostgreSQL data types. */
public final class PostgreSqlTypeMapper {
    public static final int MAX_TEMPORAL_PRECISION = 6;
    private final NumericMappingStrategy strategy;
    private final NumericTypeOptimizationService optimizer;

    public PostgreSqlTypeMapper() {
        this(NumericMappingStrategy.SAFE);
    }

    public PostgreSqlTypeMapper(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.optimizer = new NumericTypeOptimizationService();
    }

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);
        if (strategy == NumericMappingStrategy.OPTIMIZED) {
            var optimized = optimizer.optimize(type, NumericIntegerProfiles.POSTGRESQL);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }

        String targetName = switch (sourceName) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> "VARCHAR";
            case "CHAR", "NCHAR", "CHARACTER" -> "CHAR";
            case "NUMBER", "NUMERIC", "DECIMAL" -> "NUMERIC";
            case "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER" -> "INTEGER";
            case "BIGINT" -> "BIGINT";
            case "SMALLINT" -> "SMALLINT";
            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> "DOUBLE PRECISION";
            case "BINARY_FLOAT", "REAL" -> "REAL";
            case "CLOB", "NCLOB", "LONG", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT" -> "TEXT";
            case "BLOB", "RAW", "LONG RAW", "LONG_RAW", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> "BYTEA";
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
        if (type.precision() != null && supportsTemporalPrecision(targetName)) {
            int precision = Math.min(type.precision(), MAX_TEMPORAL_PRECISION);
            if (targetName.equals("TIMESTAMP WITH TIME ZONE")) {
                return "TIMESTAMP(" + precision + ") WITH TIME ZONE";
            }
            return targetName + "(" + precision + ")";
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

    private boolean supportsTemporalPrecision(String name) {
        return name.equals("TIMESTAMP") || name.equals("TIMESTAMP WITH TIME ZONE");
    }
}
