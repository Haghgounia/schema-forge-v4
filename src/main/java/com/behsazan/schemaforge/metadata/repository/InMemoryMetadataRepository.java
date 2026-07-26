package com.behsazan.schemaforge.metadata.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Test/offline repository. Runtime JDBC repositories do not use this class. */
public final class InMemoryMetadataRepository implements MetadataRepository {
    private final Map<String, MetadataColumnProfile> profiles;

    public InMemoryMetadataRepository(Collection<MetadataColumnProfile> profiles) {
        Map<String, MetadataColumnProfile> values = new LinkedHashMap<>();
        if (profiles != null) {
            for (MetadataColumnProfile profile : profiles) {
                values.put(MetadataColumnProfile.normalizeName(profile.columnName()), profile);
            }
        }
        this.profiles = Map.copyOf(values);
    }

    @Override
    public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) return Map.of();
        Map<String, MetadataColumnProfile> result = new LinkedHashMap<>();
        for (String name : columnNames) {
            String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
            MetadataColumnProfile profile = profiles.get(normalized);
            if (profile != null) result.put(normalized, profile);
        }
        return Map.copyOf(result);
    }
}
