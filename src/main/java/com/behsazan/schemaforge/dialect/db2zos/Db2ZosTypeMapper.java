package com.behsazan.schemaforge.dialect.db2zos;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/** Maps canonical and Oracle-oriented data types to Db2 for z/OS data types. */
public final class Db2ZosTypeMapper {
    static final int MAX_DECIMAL_PRECISION = 31;

    private final NumericMappingStrategy strategy;
    private final NumericTypeOptimizationService optimizer;

    public Db2ZosTypeMapper() {
        this(NumericMappingStrategy.SAFE);
    }

    public Db2ZosTypeMapper(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.optimizer = new NumericTypeOptimizationService();
    }

    /**
     * Maps a canonical type to a Db2 for z/OS built-in type.
     *
     * @throws IllegalArgumentException when an exact source number cannot be
     *                                  represented losslessly by Db2 DECIMAL
     */
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
            case "LONG RAW", "LONG_RAW", "BLOB" -> "BLOB";
            case "LONG", "CLOB" -> "CLOB";
            case "NCLOB" -> "DBCLOB";

            case "DATE" -> "TIMESTAMP(0)";
            case "DB2_DATE" -> "DATE";
            case "TIMESTAMP" -> timestamp(type, false);
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
                    "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" -> timestamp(type, true);

            case "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> "DOUBLE";
            case "BINARY_FLOAT", "REAL" -> "REAL";
            case "DECFLOAT" -> decimalFloat(type);

            case "XMLTYPE", "XML" -> "XML";
            case "JSON" -> "CLOB";
            case "BOOLEAN" -> "SMALLINT";
            case "ROWID", "UROWID" -> "VARCHAR(40)";
            case "DB2_ROWID" -> "ROWID";
            default -> renderUnknown(type, sourceName);
        };
    }

    private String mapExactNumber(DataType type) {
        Integer precision = type.precision();
        if (precision == null) {
            throw new IllegalArgumentException(
                    "Db2 z/OS requires an explicit precision for lossless NUMBER mapping");
        }
        if (precision > MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException(
                    "Db2 z/OS DECIMAL precision exceeds 31: " + renderSource(type));
        }

        int scale = type.scale() == null ? 0 : type.scale();
        if (strategy == NumericMappingStrategy.OPTIMIZED) {
            var optimized = optimizer.optimize(typeWithExplicitScale(type, scale), NumericIntegerProfiles.DB2_ZOS);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    private String withRequiredLength(String targetName, DataType type) {
        if (type.length() == null) {
            throw new IllegalArgumentException(targetName + " requires an explicit length for Db2 z/OS");
        }
        return targetName + "(" + type.length() + ")";
    }

    private String withOptionalLength(String targetName, DataType type) {
        return type.length() == null ? targetName : targetName + "(" + type.length() + ")";
    }

    private String timestamp(DataType type, boolean withTimeZone) {
        String precision = type.precision() == null ? "" : "(" + type.precision() + ")";
        return "TIMESTAMP" + precision + (withTimeZone ? " WITH TIME ZONE" : "");
    }

    private String decimalFloat(DataType type) {
        if (type.precision() == null) {
            return "DECFLOAT";
        }
        if (type.precision() != 16 && type.precision() != 34) {
            throw new IllegalArgumentException("Db2 z/OS DECFLOAT precision must be 16 or 34");
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
            if (type.scale() != null) {
                value.append(',').append(type.scale());
            }
            value.append(')');
        }
        return value.toString();
    }
}
