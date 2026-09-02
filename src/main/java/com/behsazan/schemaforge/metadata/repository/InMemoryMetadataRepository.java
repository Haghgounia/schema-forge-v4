package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test/offline repository. Runtime JDBC repositories do not use this class. */
public final class InMemoryMetadataRepository implements MetadataRepository {
    private final Map<String, MetadataColumnProfile> profiles;
    private final Map<String, Table> tables;

    public InMemoryMetadataRepository(Collection<MetadataColumnProfile> profiles) {
        this(profiles, java.util.List.of());
    }

    public InMemoryMetadataRepository(Collection<MetadataColumnProfile> profiles, Collection<Table> tables) {
        Map<String, MetadataColumnProfile> values = new LinkedHashMap<>();
        if (profiles != null) {
            for (MetadataColumnProfile profile : profiles) {
                values.put(MetadataColumnProfile.normalizeName(profile.columnName()), profile);
            }
        }
        this.profiles = Map.copyOf(values);

        Map<String, Table> tableValues = new LinkedHashMap<>();
        if (tables != null) {
            for (Table table : tables) {
                tableValues.put(tableKey(
                        table.qualifiedName().schemaName().map(identifier -> identifier.value()).orElse(""),
                        table.qualifiedName().name().value()), table);
            }
        }
        this.tables = Map.copyOf(tableValues);
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

    @Override
    public Optional<Table> findTable(String schemaName, String tableName) {
        return Optional.ofNullable(tables.get(tableKey(schemaName, tableName)));
    }

    @Override
    public java.util.List<String> findTableNames(String schemaName) {
        String prefix = (schemaName == null ? "" : schemaName.trim().toUpperCase(Locale.ROOT)) + ".";
        return tables.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> entry.getValue().qualifiedName().name().value())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Override
    public boolean schemaExistenceAuthoritative() {
        return false;
    }

    private static String tableKey(String schemaName, String tableName) {
        String schema = schemaName == null ? "" : schemaName.trim().toUpperCase(Locale.ROOT);
        String table = tableName == null ? "" : tableName.trim().toUpperCase(Locale.ROOT);
        return schema + "." + table;
    }
}
