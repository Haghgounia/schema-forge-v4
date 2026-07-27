package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.NumericIntegerProfiles;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.NumericTypeOptimizationService;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares exact numeric and native integer SQL types using the active numeric-mapping strategy.
 *
 * <p>SAFE keeps the historical exact-signature comparison. OPTIMIZED additionally treats the
 * lossless integer selected by the vendor profile as equivalent to the source exact numeric type.</p>
 */
public final class NumericTypeEquivalenceService {
    private static final Pattern EXACT_NUMERIC =
            Pattern.compile("^(NUMBER|NUMERIC|DECIMAL)\\((\\d+),(\\d+)\\)$");

    private final DataTypeCanonicalizer canonicalizer;
    private final NumericTypeOptimizationService optimizer;

    public NumericTypeEquivalenceService() {
        this.canonicalizer = new DataTypeCanonicalizer();
        this.optimizer = new NumericTypeOptimizationService();
    }

    public boolean equivalent(
            String database,
            String firstType,
            String secondType,
            NumericMappingStrategy strategy) {

        Objects.requireNonNull(strategy, "strategy must not be null");
        String first = canonicalizer.canonicalize(database, firstType);
        String second = canonicalizer.canonicalize(database, secondType);
        if (first.equals(second)) {
            return true;
        }
        if (strategy != NumericMappingStrategy.OPTIMIZED) {
            return false;
        }

        Optional<NumericTypeOptimizationService.NumericIntegerProfile> profile = profile(database);
        if (profile.isEmpty()) {
            return false;
        }

        return exactNumericMatchesInteger(first, second, profile.get())
                || exactNumericMatchesInteger(second, first, profile.get());
    }

    private boolean exactNumericMatchesInteger(
            String exactNumeric,
            String integerType,
            NumericTypeOptimizationService.NumericIntegerProfile profile) {

        Matcher matcher = EXACT_NUMERIC.matcher(exactNumeric);
        if (!matcher.matches()) {
            return false;
        }

        int precision = Integer.parseInt(matcher.group(2));
        int scale = Integer.parseInt(matcher.group(3));
        if (scale != 0) {
            return false;
        }

        DataType sourceType = DataType.numeric(matcher.group(1), precision, scale);
        Optional<String> optimized = optimizer.optimize(sourceType, profile);
        return optimized
                .map(this::normalizeIntegerType)
                .filter(expected -> expected.equals(normalizeIntegerType(integerType)))
                .isPresent();
    }

    private Optional<NumericTypeOptimizationService.NumericIntegerProfile> profile(String database) {
        return switch (normalizeDatabase(database)) {
            case "POSTGRESQL" -> Optional.of(NumericIntegerProfiles.POSTGRESQL);
            case "DB2" -> Optional.of(NumericIntegerProfiles.DB2_ZOS);
            case "SQLSERVER" -> Optional.of(NumericIntegerProfiles.SQL_SERVER);
            default -> Optional.empty();
        };
    }

    private String normalizeIntegerType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "INT2" -> "SMALLINT";
            case "INT", "INT4" -> "INTEGER";
            case "INT8" -> "BIGINT";
            default -> normalized;
        };
    }

    private String normalizeDatabase(String database) {
        if (database == null) {
            return "";
        }
        String normalized = database.trim().toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL" -> "POSTGRESQL";
            case "IBMDB2", "DB2", "DB2ZOS", "IBMDB2ZOS" -> "DB2";
            case "MSSQL", "SQLSERVER", "MICROSOFTSQLSERVER" -> "SQLSERVER";
            default -> normalized;
        };
    }
}
