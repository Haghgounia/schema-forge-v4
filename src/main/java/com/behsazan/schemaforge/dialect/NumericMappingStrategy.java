package com.behsazan.schemaforge.dialect;

import java.util.Locale;

/** Controls whether exact numeric types are preserved or narrowed to native integer types. */
public enum NumericMappingStrategy {
    SAFE,
    OPTIMIZED;

    public static NumericMappingStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return SAFE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported numeric mapping strategy: " + value + ". Supported values: SAFE, OPTIMIZED",
                    ex);
        }
    }
}
