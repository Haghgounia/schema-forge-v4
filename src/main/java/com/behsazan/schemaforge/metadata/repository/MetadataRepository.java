package com.behsazan.schemaforge.metadata.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only metadata access contract. Implementations do not cache results. */
public interface MetadataRepository {
    Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames);

    default boolean schemaExists(String schemaName) { return false; }

    /** Returns all non-system schemas containing the requested table. */
    default List<String> findTableSchemas(String tableName) { return List.of(); }

    default boolean available() { return true; }

    static MetadataRepository empty() {
        return new MetadataRepository() {
            @Override public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) { return Map.of(); }
            @Override public boolean available() { return false; }
        };
    }
}
