package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureIsolatingMetadataRepositoryTest {

    @Test
    void connectionFailureDisablesRepositoryForRemainderOfRequest() {
        AtomicInteger calls = new AtomicInteger();
        MetadataRepository delegate = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                calls.incrementAndGet();
                throw new CannotGetJdbcConnectionException(
                        "failed", new SQLException("Access denied for user", "28000"));
            }
        };
        MetadataRepository guarded = FailureIsolatingMetadataRepository.wrap(DatabasePlatform.MYSQL, delegate);

        assertTrue(guarded.available());
        assertTrue(guarded.loadColumnProfiles(Set.of("ID")).isEmpty());
        assertFalse(guarded.available());
        assertTrue(guarded.loadColumnProfiles(Set.of("NAME")).isEmpty());
        assertEquals(1, calls.get(), "connection failure must trip the request-scoped circuit only once");
    }

    @Test
    void nonConnectionFailureIsNeverHidden() {
        MetadataRepository delegate = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                throw new IllegalStateException("metadata mapping defect");
            }
        };
        MetadataRepository guarded = FailureIsolatingMetadataRepository.wrap(DatabasePlatform.ORACLE, delegate);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> guarded.loadColumnProfiles(Set.of("ID")));
        assertEquals("metadata mapping defect", failure.getMessage());
        assertTrue(guarded.available());
    }

    @Test
    void requestCacheAvoidsRepeatedSchemaAndTableLookupsAndShortCircuitsMissingSchema() {
        AtomicInteger schemaCalls = new AtomicInteger();
        AtomicInteger tableCalls = new AtomicInteger();
        AtomicInteger tableSchemaCalls = new AtomicInteger();
        MetadataRepository delegate = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public boolean schemaExists(String schemaName) {
                schemaCalls.incrementAndGet();
                return false;
            }

            @Override
            public Optional<com.behsazan.schemaforge.domain.model.Table> findTable(
                    String schemaName, String tableName) {
                tableCalls.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public List<String> findTableSchemas(String tableName) {
                tableSchemaCalls.incrementAndGet();
                return List.of();
            }
        };
        MetadataRepository guarded = FailureIsolatingMetadataRepository.wrap(DatabasePlatform.DB2_LUW, delegate);

        assertFalse(guarded.schemaExists("FEE"));
        assertFalse(guarded.schemaExists("fee"));
        assertTrue(guarded.findTable("FEE", "T1").isEmpty());
        assertTrue(guarded.findTable("fee", "t1").isEmpty());
        assertTrue(guarded.findTableSchemas("T1").isEmpty());
        assertTrue(guarded.findTableSchemas("t1").isEmpty());

        assertEquals(1, schemaCalls.get(), "schema existence must be cached case-insensitively");
        assertEquals(0, tableCalls.get(), "known-missing schema must short-circuit table lookup");
        assertEquals(1, tableSchemaCalls.get(), "table schema lookup must be cached per request");
    }

    @Test
    void sqlStateAuthenticationAndConnectionClassesAreRecognized() {
        assertTrue(MetadataConnectionFailureClassifier.isConnectionFailure(
                new RuntimeException(new SQLException("authentication failed", "28000"))));
        assertTrue(MetadataConnectionFailureClassifier.isConnectionFailure(
                new RuntimeException(new SQLException("connection lost", "08006"))));
        assertFalse(MetadataConnectionFailureClassifier.isConnectionFailure(
                new RuntimeException(new SQLException("syntax error", "42000"))));
    }
}
