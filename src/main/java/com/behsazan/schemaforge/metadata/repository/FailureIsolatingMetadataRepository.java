package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped metadata repository guard for aggregate generation workflows.
 *
 * <p>Only database connectivity/authentication failures are isolated. After the first such failure,
 * the guarded repository becomes unavailable for the remainder of the request and subsequent
 * metadata lookups return empty results. Non-connectivity programming, SQL, mapping, and data
 * contract failures are deliberately rethrown.</p>
 *
 * <p>The guard also keeps a small request-local cache for schema/table lookups. This is important
 * for EA/document generation because comparison and migration phases reuse the same repository.
 * A schema already proven missing is therefore not probed table-by-table again.</p>
 */
public final class FailureIsolatingMetadataRepository implements MetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailureIsolatingMetadataRepository.class);

    private final DatabasePlatform platform;
    private final MetadataRepository delegate;
    private final AtomicBoolean connectionUnavailable = new AtomicBoolean(false);
    private final ConcurrentMap<String, Boolean> schemaExistence = new ConcurrentHashMap<>();
    private final ConcurrentMap<TableKey, Optional<Table>> tables = new ConcurrentHashMap<>();
    private final ConcurrentMap<ExactTableKey, Boolean> missingTables = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> tableSchemas = new ConcurrentHashMap<>();

    private FailureIsolatingMetadataRepository(DatabasePlatform platform, MetadataRepository delegate) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    public static MetadataRepository wrap(DatabasePlatform platform, MetadataRepository repository) {
        Objects.requireNonNull(repository, "repository must not be null");
        if (repository instanceof FailureIsolatingMetadataRepository) {
            return repository;
        }
        return new FailureIsolatingMetadataRepository(platform, repository);
    }

    @Override
    public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
        if (!available()) {
            return Map.of();
        }
        try {
            return delegate.loadColumnProfiles(columnNames);
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("loadColumnProfiles", exception)) {
                return Map.of();
            }
            throw exception;
        }
    }

    @Override
    public Optional<Table> findTable(String schemaName, String tableName) {
        if (!available()) {
            return Optional.empty();
        }
        TableKey key = TableKey.of(schemaName, tableName);
        if (key == null) {
            return Optional.empty();
        }

        Boolean knownSchema = schemaExistence.get(key.schemaName());
        if (Boolean.FALSE.equals(knownSchema)) {
            return Optional.empty();
        }

        Optional<Table> cached = tables.get(key);
        if (cached != null && cached.isPresent()) {
            return cached;
        }
        ExactTableKey exactKey = ExactTableKey.of(schemaName, tableName);
        if (exactKey != null && missingTables.containsKey(exactKey)) {
            return Optional.empty();
        }
        try {
            Optional<Table> result = delegate.findTable(schemaName, tableName);
            if (result.isPresent()) {
                tables.put(key, result);
                missingTables.keySet().removeIf(candidate -> candidate.normalized().equals(key));
            } else if (exactKey != null) {
                missingTables.putIfAbsent(exactKey, Boolean.TRUE);
            }
            return result;
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("findTable", exception)) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public Map<String, Table> findTables(String schemaName, Set<String> tableNames) {
        if (!available() || tableNames == null || tableNames.isEmpty()) {
            return Map.of();
        }
        String normalizedSchema = normalize(schemaName);
        if (normalizedSchema == null || Boolean.FALSE.equals(schemaExistence.get(normalizedSchema))) {
            return Map.of();
        }

        Map<String, String> requested = new java.util.LinkedHashMap<>();
        for (String tableName : tableNames) {
            String normalizedTable = normalize(tableName);
            if (normalizedTable != null) requested.put(normalizedTable, tableName);
        }
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            TableKey normalizedKey = new TableKey(normalizedSchema, entry.getKey());
            Optional<Table> cached = tables.get(normalizedKey);
            ExactTableKey exactKey = ExactTableKey.of(schemaName, entry.getValue());
            boolean knownPresent = cached != null && cached.isPresent();
            boolean knownMissing = exactKey != null && missingTables.containsKey(exactKey);
            if (!knownPresent && !knownMissing) {
                missing.add(entry.getValue());
            }
        }

        if (!missing.isEmpty()) {
            try {
                Map<String, Table> loaded = delegate.findTables(schemaName, missing);
                Map<String, Table> normalizedLoaded = new java.util.LinkedHashMap<>();
                loaded.forEach((name, table) -> {
                    String normalizedName = normalize(name);
                    if (normalizedName != null && table != null) normalizedLoaded.put(normalizedName, table);
                });
                for (String requestedName : missing) {
                    String normalizedName = normalize(requestedName);
                    Table table = normalizedLoaded.get(normalizedName);
                    TableKey normalizedKey = new TableKey(normalizedSchema, normalizedName);
                    if (table != null) {
                        tables.put(normalizedKey, Optional.of(table));
                        missingTables.keySet().removeIf(candidate -> candidate.normalized().equals(normalizedKey));
                    } else {
                        ExactTableKey exactKey = ExactTableKey.of(schemaName, requestedName);
                        if (exactKey != null) missingTables.putIfAbsent(exactKey, Boolean.TRUE);
                    }
                }
            } catch (RuntimeException exception) {
                if (isolateConnectionFailure("findTables", exception)) {
                    return Map.of();
                }
                throw exception;
            }
        }

        Map<String, Table> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            Optional<Table> table = tables.get(new TableKey(normalizedSchema, entry.getKey()));
            if (table != null && table.isPresent()) result.put(entry.getValue(), table.get());
        }
        return Map.copyOf(result);
    }

    @Override
    public boolean bulkTableReadOptimized() {
        return delegate.bulkTableReadOptimized();
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (!available()) {
            return false;
        }
        String key = normalize(schemaName);
        if (key == null) {
            return false;
        }
        Boolean cached = schemaExistence.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            boolean result = delegate.schemaExists(schemaName);
            schemaExistence.putIfAbsent(key, result);
            return result;
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("schemaExists", exception)) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public boolean schemaExistenceAuthoritative() {
        return delegate.schemaExistenceAuthoritative();
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (!available()) {
            return List.of();
        }
        String key = normalize(tableName);
        if (key == null) {
            return List.of();
        }
        List<String> cached = tableSchemas.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> result = List.copyOf(delegate.findTableSchemas(tableName));
            tableSchemas.putIfAbsent(key, result);
            return result;
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("findTableSchemas", exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Override
    public Map<String, List<String>> findTableSchemas(Set<String> tableNames) {
        if (!available() || tableNames == null || tableNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> requested = new java.util.LinkedHashMap<>();
        for (String tableName : tableNames) {
            String normalizedTable = normalize(tableName);
            if (normalizedTable != null) requested.put(normalizedTable, tableName);
        }
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            if (!tableSchemas.containsKey(entry.getKey())) missing.add(entry.getValue());
        }
        if (!missing.isEmpty()) {
            try {
                Map<String, List<String>> loaded = delegate.findTableSchemas(missing);
                Map<String, List<String>> normalizedLoaded = new java.util.LinkedHashMap<>();
                loaded.forEach((name, schemas) -> {
                    String normalizedName = normalize(name);
                    if (normalizedName != null) normalizedLoaded.put(normalizedName, List.copyOf(schemas));
                });
                for (String requestedName : missing) {
                    String normalizedName = normalize(requestedName);
                    tableSchemas.putIfAbsent(normalizedName,
                            normalizedLoaded.getOrDefault(normalizedName, List.of()));
                }
            } catch (RuntimeException exception) {
                if (isolateConnectionFailure("findTableSchemasBulk", exception)) {
                    return Map.of();
                }
                throw exception;
            }
        }
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            result.put(entry.getValue(), tableSchemas.getOrDefault(entry.getKey(), List.of()));
        }
        return Map.copyOf(result);
    }

    @Override
    public boolean bulkTableSchemaReadOptimized() {
        return delegate.bulkTableSchemaReadOptimized();
    }

    @Override
    public boolean available() {
        if (connectionUnavailable.get()) {
            return false;
        }
        try {
            return delegate.available();
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("available", exception)) {
                return false;
            }
            throw exception;
        }
    }

    private boolean isolateConnectionFailure(String operation, RuntimeException exception) {
        if (!MetadataConnectionFailureClassifier.isConnectionFailure(exception)) {
            return false;
        }
        if (connectionUnavailable.compareAndSet(false, true)) {
            Throwable root = rootCause(exception);
            LOGGER.warn("[{}] Metadata connection unavailable during {}; optional metadata-dependent "
                            + "artifacts will be skipped for the remainder of this generation request. cause={}: {}",
                    platform.name(), operation, root.getClass().getSimpleName(), safeMessage(root));
        }
        return true;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record TableKey(String schemaName, String tableName) {
        static TableKey of(String schemaName, String tableName) {
            String schema = normalize(schemaName);
            String table = normalize(tableName);
            return schema == null || table == null ? null : new TableKey(schema, table);
        }
    }

    /**
     * Exact-case negative cache. A miss for APP.T must not suppress a legitimate retry against
     * a catalog-returned schema spelling such as app.T. Positive hits remain case-insensitive.
     */
    private record ExactTableKey(String schemaName, String tableName, TableKey normalized) {
        static ExactTableKey of(String schemaName, String tableName) {
            if (schemaName == null || schemaName.isBlank() || tableName == null || tableName.isBlank()) {
                return null;
            }
            TableKey normalized = TableKey.of(schemaName, tableName);
            if (normalized == null) return null;
            return new ExactTableKey(schemaName.trim(), tableName.trim(), normalized);
        }
    }
}
