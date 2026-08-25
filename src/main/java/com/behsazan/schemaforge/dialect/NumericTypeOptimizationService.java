package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Selects a lossless native integer type for exact NUMBER/NUMERIC/DECIMAL definitions. */
public final class NumericTypeOptimizationService {

    public Optional<String> optimize(DataType type, NumericIntegerProfile profile) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(profile, "profile must not be null");

        String name = type.name().normalized().toUpperCase(Locale.ROOT);
        if (!name.equals("NUMBER") && !name.equals("NUMERIC") && !name.equals("DECIMAL") && !name.equals("DEC")) {
            return Optional.empty();
        }
        if (type.precision() == null || (type.scale() != null && type.scale() != 0)) {
            return Optional.empty();
        }

        int precision = type.precision();
        if (precision <= profile.smallIntMaxPrecision()) {
            return Optional.of(profile.smallIntType());
        }
        if (precision <= profile.integerMaxPrecision()) {
            return Optional.of(profile.integerType());
        }
        if (precision <= profile.bigIntMaxPrecision()) {
            return Optional.of(profile.bigIntType());
        }
        return Optional.empty();
    }

    /** Vendor-specific lossless integer capacities expressed as maximum decimal precision. */
    public record NumericIntegerProfile(
            String smallIntType,
            int smallIntMaxPrecision,
            String integerType,
            int integerMaxPrecision,
            String bigIntType,
            int bigIntMaxPrecision) {

        public NumericIntegerProfile {
            Objects.requireNonNull(smallIntType, "smallIntType must not be null");
            Objects.requireNonNull(integerType, "integerType must not be null");
            Objects.requireNonNull(bigIntType, "bigIntType must not be null");
            if (smallIntMaxPrecision < 1
                    || integerMaxPrecision < smallIntMaxPrecision
                    || bigIntMaxPrecision < integerMaxPrecision) {
                throw new IllegalArgumentException("Numeric integer precision limits are invalid");
            }
        }
    }
}
