package com.behsazan.schemaforge.dialect.db2luw;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/** Maps canonical and Oracle-oriented data types to Db2 LUW built-in types. */
public final class Db2LuwTypeMapper {
    public static final int MAX_DECIMAL_PRECISION = 31;
    public static final int MAX_TIMESTAMP_PRECISION = 12;

    private final NumericMappingStrategy strategy;
    private final NumericTypeOptimizationService optimizer;

    public Db2LuwTypeMapper() {
        this(NumericMappingStrategy.SAFE);
    }

    public Db2LuwTypeMapper(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.optimizer = new NumericTypeOptimizationService();
    }

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);

        return switch (sourceName) {
            case "NUMBER", "NUMERIC", "DECIMAL" -> mapExactNumber(type);
            case "SMALLINT" -> "SMALLINT";
            case "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER" -> "INTEGER";
            case "BIGINT" -> "BIGINT";

            case "VARCHAR", "VARCHAR2" -> withRequiredLength("VARCHAR", type);
            case "NVARCHAR", "NVARCHAR2" -> withRequiredLength("VARGRAPHIC", type);
            case "CHAR", "CHARACTER" -> withOptionalLength("CHAR", type);
            case "NCHAR" -> withOptionalLength("GRAPHIC", type);

            case "RAW" -> withRequiredLength("VARBINARY", type);
            case "LONG RAW", "LONG_RAW", "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> "BLOB";
            case "LONG", "CLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT" -> "CLOB";
            case "NCLOB" -> "DBCLOB";

            // Oracle DATE contains date and time; TIMESTAMP(0) preserves that semantic class.
            case "DATE" -> "TIMESTAMP(0)";
            case "DB2_DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "TIMESTAMP" -> timestamp(type);
            case "DB2_LUW_TIMESTAMP0" -> "TIMESTAMP(0)";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
                    "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" ->
                    throw new IllegalArgumentException(
                            "Db2 LUW does not support a lossless TIMESTAMP WITH TIME ZONE column mapping");

            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> "DOUBLE";
            case "BINARY_FLOAT", "REAL" -> "REAL";
            case "DECFLOAT" -> decimalFloat(type);

            case "XMLTYPE", "XML" -> "XML";
            case "JSON" -> "CLOB";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "ROWID", "UROWID" -> "VARCHAR(40)";
            default -> renderUnknown(type, sourceName);
        };
    }

    private String mapExactNumber(DataType type) {
        Integer precision = type.precision();
        if (precision == null) {
            throw new IllegalArgumentException(
                    "Db2 LUW requires an explicit precision for lossless NUMBER mapping");
        }
        if (precision > MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException(
                    "Db2 LUW DECIMAL precision exceeds 31: " + renderSource(type));
        }
        int scale = type.scale() == null ? 0 : type.scale();
        if (scale < 0 || scale > precision) {
            throw new IllegalArgumentException(
                    "Db2 LUW DECIMAL scale must be between 0 and precision: " + renderSource(type));
        }

        if (strategy == NumericMappingStrategy.OPTIMIZED) {
            var optimized = optimizer.optimize(typeWithExplicitScale(type, scale), NumericIntegerProfiles.DB2_LUW);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private String withRequiredLength(String targetName, DataType type) {
        if (type.length() == null) {
            throw new IllegalArgumentException(targetName + " requires an explicit length for Db2 LUW");
        }
        return targetName + "(" + type.length() + ")";
    }

    private String withOptionalLength(String targetName, DataType type) {
        return type.length() == null ? targetName : targetName + "(" + type.length() + ")";
    }

    private String timestamp(DataType type) {
        if (type.precision() != null && type.precision() > MAX_TIMESTAMP_PRECISION) {
            throw new IllegalArgumentException(
                    "Db2 LUW TIMESTAMP precision exceeds " + MAX_TIMESTAMP_PRECISION
                            + ": " + renderSource(type));
        }
        return type.precision() == null ? "TIMESTAMP" : "TIMESTAMP(" + type.precision() + ")";
    }

    private String decimalFloat(DataType type) {
        if (type.precision() == null) {
            return "DECFLOAT";
        }
        if (type.precision() != 16 && type.precision() != 34) {
            throw new IllegalArgumentException("Db2 LUW DECFLOAT precision must be 16 or 34");
        }
        return "DECFLOAT(" + type.precision() + ")";
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

    private DataType typeWithExplicitScale(DataType type, int scale) {
        if (type.scale() != null) {
            return type;
        }
        return DataType.numeric(type.name().normalized(), type.precision(), scale);
    }

    private String renderSource(DataType type) {
        StringBuilder value = new StringBuilder(type.name().normalized());
        if (type.precision() != null) {
            value.append('(').append(type.precision());
            if (type.scale() != null) value.append(',').append(type.scale());
            value.append(')');
        }
        return value.toString();
    }
}
