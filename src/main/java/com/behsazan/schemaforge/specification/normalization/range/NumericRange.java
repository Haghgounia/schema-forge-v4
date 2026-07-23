package com.behsazan.schemaforge.specification.normalization.range;

import java.util.List;
import java.util.Objects;

/** Canonical numeric interpretation of a free-text DOCX Range cell. */
public sealed interface NumericRange permits NumericRange.Interval, NumericRange.Enumeration {

    /** Inclusive numeric interval. */
    record Interval(long minimum, long maximum) implements NumericRange {
        public Interval {
            if (minimum > maximum) {
                throw new IllegalArgumentException("minimum must not be greater than maximum");
            }
        }
    }

    /** Explicit set of allowed numeric values. */
    record Enumeration(List<Long> values) implements NumericRange {
        public Enumeration {
            Objects.requireNonNull(values, "values must not be null");
            values = values.stream().distinct().sorted().toList();
            if (values.size() < 2) {
                throw new IllegalArgumentException("enumeration must contain at least two values");
            }
        }
    }
}
