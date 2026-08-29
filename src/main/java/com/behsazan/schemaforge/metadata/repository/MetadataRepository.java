package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only metadata access contract. Implementations do not cache results. */
public interface MetadataRepository {
    Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames);

    /** Reads one live table for document-to-database comparison. Results are not cached. */
    default Optional<Table> findTable(String schemaName, String tableName) { return Optional.empty(); }

    /**
     * Optional optimized bulk read used by large EA/document requests. The default preserves
     * existing semantics by delegating to {@link #findTable(String, String)} one table at a time.
     */
    default Map<String, Table> findTables(String schemaName, Set<String> tableNames) {
        Map<String, Table> result = new LinkedHashMap<>();
        if (tableNames == null) return result;
        for (String tableName : tableNames) {
            findTable(schemaName, tableName).ifPresent(table -> result.put(tableName, table));
        }
        return result;
    }

    /** True only when {@link #findTables(String, Set)} is materially cheaper than repeated reads. */
    default boolean bulkTableReadOptimized() { return false; }

    default boolean schemaExists(String schemaName) { return false; }

    /**
     * Whether {@link #schemaExists(String)} can distinguish a verified missing schema from an
     * implementation that simply has no schema catalog. Runtime JDBC repositories are
     * authoritative; profile-only/offline repositories may opt out.
     */
    default boolean schemaExistenceAuthoritative() { return true; }

    /** Returns all non-system schemas containing the requested table. */
    default List<String> findTableSchemas(String tableName) { return List.of(); }

    /** Optional optimized bulk location lookup. Keys are normalized/implementation-defined table names. */
    default Map<String, List<String>> findTableSchemas(Set<String> tableNames) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (tableNames == null) return result;
        for (String tableName : tableNames) {
            result.put(tableName, findTableSchemas(tableName));
        }
        return result;
    }

    /** True only when bulk table-location lookup is materially cheaper than repeated reads. */
    default boolean bulkTableSchemaReadOptimized() { return false; }

    default boolean available() { return true; }

    static MetadataRepository empty() {
        return new MetadataRepository() {
            @Override public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) { return Map.of(); }
            @Override public boolean available() { return false; }
        };
    }
}
