package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only metadata access contract. Implementations do not cache results. */
public interface MetadataRepository {
    Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames);

    /** Reads one live table for document-to-database comparison. Results are not cached. */
    default Optional<Table> findTable(String schemaName, String tableName) { return Optional.empty(); }

    default boolean schemaExists(String schemaName) { return false; }

    /**
     * Whether {@link #schemaExists(String)} can distinguish a verified missing schema from an
     * implementation that simply has no schema catalog. Runtime JDBC repositories are
     * authoritative; profile-only/offline repositories may opt out.
     */
    default boolean schemaExistenceAuthoritative() { return true; }

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
