package com.behsazan.schemaforge.dialect.db2zos;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;

/**
 * Maps exact numeric source types to lossless Db2 for z/OS numeric types.
 *
 * <p>This is the first Db2 z/OS dialect component. It is intentionally not yet
 * registered as a selectable database platform; platform integration follows
 * after the Db2 identifier, expression and DDL rendering rules are added.</p>
 */
public final class Db2ZosTypeMapper {
    static final int MAX_DECIMAL_PRECISION = 31;

    private static final NumericTypeOptimizationService.NumericIntegerProfile INTEGER_PROFILE =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INTEGER", 9, "BIGINT", 18);

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
     * Maps NUMBER, NUMERIC and DECIMAL definitions to Db2 for z/OS.
     * Existing integer types are normalized to their Db2 names.
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
            default -> sourceName;
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
            var optimized = optimizer.optimize(typeWithExplicitScale(type, scale), INTEGER_PROFILE);
            if (optimized.isPresent()) {
                return optimized.get();
            }
        }
        return "DECIMAL(" + precision + "," + scale + ")";
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
