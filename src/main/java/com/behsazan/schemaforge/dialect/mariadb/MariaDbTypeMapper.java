package com.behsazan.schemaforge.dialect.mariadb;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/**
 * Evidence-safe logical datatype mapper for the MariaDB foundation phase.
 *
 * <p>The mapper is deliberately independent from the MySQL mapper even where the
 * first mappings are identical. MariaDB is a distinct SchemaForge target and its
 * datatype limits/semantics may diverge from MySQL over time.</p>
 */
public final class  MariaDbTypeMapper {
    public static final int MAX_FIXED_CHAR_LENGTH = 255;
    public static final int MAX_DECIMAL_PRECISION = 65;
    public static final int MAX_DECIMAL_SCALE = 38;
    public static final int MAX_TEMPORAL_PRECISION = 6;

    private final NumericMappingStrategy strategy;
    private final NumericTypeOptimizationService optimizer;

    public MariaDbTypeMapper() {
        this(NumericMappingStrategy.SAFE);
    }

    public MariaDbTypeMapper(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.optimizer = new NumericTypeOptimizationService();
    }

    public String map(DataType type) {
        Objects.requireNonNull(type, "type must not be null");
        String source = type.name().normalized().toUpperCase(Locale.ROOT);

        return switch (source) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> variableCharacter(type);
            case "CHAR", "NCHAR", "CHARACTER" -> fixedCharacter(type);
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> exactNumeric(type);
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
            // Canonical DATE may originate from Oracle DATE and can therefore contain time-of-day.
            case "DATE" -> "DATETIME";
            case "TIMESTAMP", "DATETIME" -> temporal("DATETIME", type);
            case "TIME" -> temporal("TIME", type);
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "JSON" -> "JSON";
            case "XMLTYPE", "XML" -> "LONGTEXT";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
                    "TIMESTAMP_WITH_TIME_ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE" ->
                    throw unsupported(type, "timezone-aware timestamp requires an explicit MariaDB portability policy");
            case "ROWID", "UROWID" ->
                    throw unsupported(type, "Oracle ROWID semantics have no lossless MariaDB logical mapping");
            default -> throw unsupported(type, "canonical datatype is not in MariaDB foundation coverage");
        };
    }

    private String variableCharacter(DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, "VARCHAR requires explicit length in SchemaForge MariaDB foundation");
        }
        return "VARCHAR(" + type.length() + ")";
    }

    private String fixedCharacter(DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, "CHAR requires explicit length in SchemaForge MariaDB foundation");
        }
        if (type.length() > MAX_FIXED_CHAR_LENGTH) {
            throw unsupported(type, "MariaDB CHAR/NCHAR length must be between 1 and "
                    + MAX_FIXED_CHAR_LENGTH
                    + "; SchemaForge will not guess a VARCHAR/TEXT replacement because fixed-width "
                    + "padding semantics would change");
        }
        return "CHAR(" + type.length() + ")";
    }

    private String binary(DataType type) {
        if (type.length() == null || type.length() <= 0) {
            throw unsupported(type, "VARBINARY requires explicit length in SchemaForge MariaDB foundation");
        }
        return "VARBINARY(" + type.length() + ")";
    }

    private String exactNumeric(DataType type) {
        if (strategy == NumericMappingStrategy.OPTIMIZED) {
            var optimized = optimizer.optimize(type, NumericIntegerProfiles.MARIADB);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }
        return decimal(type);
    }

    private String decimal(DataType type) {
        if (type.precision() == null) {
            return "DECIMAL(" + MAX_DECIMAL_PRECISION + ",0)";
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
        return new IllegalArgumentException("Unsupported MariaDB logical mapping for " + type + ": " + reason);
    }
}
