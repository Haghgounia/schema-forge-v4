package com.behsazan.schemaforge.dialect.sqlserver;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/** Maps canonical and Oracle-oriented data types to Microsoft SQL Server data types. */
public final class SqlServerTypeMapper {
    static final int MAX_DECIMAL_PRECISION = 38;
    static final int MAX_TEMPORAL_PRECISION = 7;

    private final NumericMappingStrategy strategy;
    private final NumericTypeOptimizationService optimizer;

    public SqlServerTypeMapper() {
        this(NumericMappingStrategy.SAFE);
    }

    public SqlServerTypeMapper(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.optimizer = new NumericTypeOptimizationService();
    }

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);

        return switch (sourceName) {
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> mapExactNumber(type);
            case "TINYINT" -> "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER" -> "INT";
            case "BIGINT" -> "BIGINT";

            case "VARCHAR", "VARCHAR2" -> variableCharacter("VARCHAR", type, 8000);
            case "NVARCHAR", "NVARCHAR2" -> variableCharacter("NVARCHAR", type, 4000);
            case "CHAR", "CHARACTER" -> fixedCharacter("CHAR", type, 8000);
            case "NCHAR" -> fixedCharacter("NCHAR", type, 4000);

            case "RAW" -> variableBinary(type);
            case "LONG RAW", "LONG_RAW", "BLOB", "VARBINARY_MAX", "IMAGE" -> "VARBINARY(MAX)";
            case "LONG", "CLOB", "VARCHAR_MAX", "TEXT" -> "VARCHAR(MAX)";
            case "NCLOB", "NVARCHAR_MAX", "NTEXT" -> "NVARCHAR(MAX)";

            case "DATE" -> "DATETIME2(0)";
            case "TIMESTAMP" -> temporal("DATETIME2", type, 6);
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
                    "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" ->
                    temporal("DATETIMEOFFSET", type, 6);
            case "DATETIME2" -> temporal("DATETIME2", type, 7);
            case "DATETIME2_0" -> "DATETIME2(0)";
            case "DATETIMEOFFSET" -> temporal("DATETIMEOFFSET", type, 7);
            case "DATETIMEOFFSET_0" -> "DATETIMEOFFSET(0)";
            case "TIME" -> temporal("TIME", type, 7);
            case "TIME_0" -> "TIME(0)";
            case "DATETIME", "SMALLDATETIME", "DATE_SQLSERVER" -> sourceName.equals("DATE_SQLSERVER")
                    ? "DATE" : sourceName;

            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> "FLOAT(53)";
            case "BINARY_FLOAT", "REAL" -> "REAL";

            case "XMLTYPE", "XML" -> "XML";
            case "JSON" -> "NVARCHAR(MAX)";
            case "BOOLEAN", "BIT" -> "BIT";
            case "ROWID" -> "VARCHAR(18)";
            case "UROWID" -> "VARCHAR(4000)";
            case "UNIQUEIDENTIFIER" -> "UNIQUEIDENTIFIER";
            case "ROWVERSION", "SQLSERVER_TIMESTAMP" -> "ROWVERSION";
            default -> renderUnknown(type, sourceName);
        };
    }

    private String mapExactNumber(DataType type) {
        Integer precision = type.precision();
        int scale = type.scale() == null ? 0 : type.scale();

        if (precision == null) {
            return "DECIMAL(38,0)";
        }
        if (precision < 1) {
            throw new IllegalArgumentException(
                    "SQL Server DECIMAL precision must be positive: " + renderSource(type));
        }
        int effectivePrecision = Math.min(precision, MAX_DECIMAL_PRECISION);
        if (scale < 0 || scale > effectivePrecision) {
            throw new IllegalArgumentException(
                    "SQL Server DECIMAL scale must be between 0 and effective precision "
                            + effectivePrecision + ": " + renderSource(type));
        }
        if (strategy == NumericMappingStrategy.OPTIMIZED) {
            DataType explicitScale = DataType.numeric(type.name().value(), effectivePrecision, scale);
            var optimized = optimizer.optimize(explicitScale, NumericIntegerProfiles.SQL_SERVER);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }
        return "DECIMAL(" + effectivePrecision + "," + scale + ")";
    }

    private String variableCharacter(String target, DataType type, int maximumLength) {
        if (type.length() == null) {
            throw new IllegalArgumentException(target + " requires an explicit length for SQL Server");
        }
        return type.length() > maximumLength
                ? target + "(MAX)"
                : target + "(" + type.length() + ")";
    }

    private String fixedCharacter(String target, DataType type, int maximumLength) {
        if (type.length() == null) {
            return target;
        }
        if (type.length() > maximumLength) {
            throw new IllegalArgumentException(
                    target + " length exceeds SQL Server limit " + maximumLength + ": " + type.length());
        }
        return target + "(" + type.length() + ")";
    }

    private String variableBinary(DataType type) {
        if (type.length() == null) {
            throw new IllegalArgumentException("VARBINARY requires an explicit length for SQL Server");
        }
        return type.length() > 8000 ? "VARBINARY(MAX)" : "VARBINARY(" + type.length() + ")";
    }

    private String temporal(String target, DataType type, int defaultPrecision) {
        int precision = type.precision() == null ? defaultPrecision : type.precision();
        if (precision < 0) {
            throw new IllegalArgumentException(
                    "SQL Server " + target + " precision must not be negative: " + precision);
        }
        return target + "(" + Math.min(precision, MAX_TEMPORAL_PRECISION) + ")";
    }

    private String renderUnknown(DataType type, String sourceName) {
        if (type.length() != null) {
            return sourceName + "(" + type.length() + ")";
        }
        if (type.precision() != null) {
            return sourceName + "(" + type.precision()
                    + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return sourceName;
    }

    private String renderSource(DataType type) {
        StringBuilder value = new StringBuilder(type.name().value());
        if (type.precision() != null) {
            value.append('(').append(type.precision());
            if (type.scale() != null) value.append(',').append(type.scale());
            value.append(')');
        }
        return value.toString();
    }
}
