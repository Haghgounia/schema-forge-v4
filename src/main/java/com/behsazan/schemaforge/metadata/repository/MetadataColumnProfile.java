package com.behsazan.schemaforge.metadata.repository;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Aggregated metadata for one column name. Table names are intentionally omitted. */
public record MetadataColumnProfile(String columnName, long totalFrequency,
                                    List<MetadataTypeFrequency> typeFrequencies) {
    public MetadataColumnProfile {
        Objects.requireNonNull(columnName, "columnName must not be null");
        columnName = normalizeName(columnName);
        typeFrequencies = typeFrequencies == null ? List.of() : typeFrequencies.stream()
                .sorted(Comparator.comparingLong(MetadataTypeFrequency::frequency).reversed()
                        .thenComparing(MetadataTypeFrequency::typeSignature))
                .toList();
        long calculated = typeFrequencies.stream().mapToLong(MetadataTypeFrequency::frequency).sum();
        totalFrequency = totalFrequency > 0 ? totalFrequency : calculated;
        if (totalFrequency < calculated) {
            throw new IllegalArgumentException("totalFrequency cannot be smaller than type frequency sum");
        }
    }

    public boolean containsType(String typeSignature) {
        String expected = MetadataTypeFrequency.normalize(typeSignature);
        return typeFrequencies.stream().anyMatch(item -> item.typeSignature().equals(expected));
    }

    public static String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
