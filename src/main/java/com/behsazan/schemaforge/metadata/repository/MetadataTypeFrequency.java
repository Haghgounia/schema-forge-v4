package com.behsazan.schemaforge.metadata.repository;

import java.util.Locale;
import java.util.Objects;

/** One normalized SQL type signature and the number of columns using it. */
public record MetadataTypeFrequency(String typeSignature, long frequency) {
    public MetadataTypeFrequency {
        Objects.requireNonNull(typeSignature, "typeSignature must not be null");
        typeSignature = normalize(typeSignature);
        if (typeSignature.isBlank()) {
            throw new IllegalArgumentException("typeSignature must not be blank");
        }
        if (frequency < 1) {
            throw new IllegalArgumentException("frequency must be positive");
        }
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
