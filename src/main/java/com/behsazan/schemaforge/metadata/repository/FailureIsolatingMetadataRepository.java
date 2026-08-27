package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped metadata repository guard for aggregate generation workflows.
 *
 * <p>Only database connectivity/authentication failures are isolated. After the first such failure,
 * the guarded repository becomes unavailable for the remainder of the request and subsequent
 * metadata lookups return empty results. Non-connectivity programming, SQL, mapping, and data
 * contract failures are deliberately rethrown.</p>
 */
public final class FailureIsolatingMetadataRepository implements MetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailureIsolatingMetadataRepository.class);

    private final DatabasePlatform platform;
    private final MetadataRepository delegate;
    private final AtomicBoolean connectionUnavailable = new AtomicBoolean(false);

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
        try {
            return delegate.findTable(schemaName, tableName);
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("findTable", exception)) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (!available()) {
            return false;
        }
        try {
            return delegate.schemaExists(schemaName);
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("schemaExists", exception)) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (!available()) {
            return List.of();
        }
        try {
            return delegate.findTableSchemas(tableName);
        } catch (RuntimeException exception) {
            if (isolateConnectionFailure("findTableSchemas", exception)) {
                return List.of();
            }
            throw exception;
        }
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
}
