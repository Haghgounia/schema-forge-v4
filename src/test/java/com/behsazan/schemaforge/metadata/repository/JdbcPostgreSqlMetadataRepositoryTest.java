package com.behsazan.schemaforge.metadata.repository;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPostgreSqlMetadataRepositoryTest {

    @Test
    void mapsSupportedTableReloptionsAndIgnoresOperationalAutovacuumOptions() {
        Map<String, String> options = JdbcPostgreSqlMetadataRepository.postgreSqlTablePhysicalOptions(
                "fastspace",
                "fillfactor=80,parallel_workers=4,toast_tuple_target=4096,autovacuum_enabled=false");

        assertEquals("fastspace", options.get("TABLESPACE"));
        assertEquals("80", options.get("POSTGRESQL_TABLE_FILLFACTOR"));
        assertEquals("4", options.get("POSTGRESQL_TABLE_PARALLEL_WORKERS"));
        assertEquals("4096", options.get("POSTGRESQL_TOAST_TUPLE_TARGET"));
        assertFalse(options.keySet().stream().anyMatch(key -> key.contains("AUTOVACUUM")));
    }

    @Test
    void tableQueryReadsEffectiveTablespaceAndReloptionsFromPgCatalog() {
        assertTrue(JdbcPostgreSqlMetadataRepository.TABLE_SQL.contains("pg_tablespace"));
        assertTrue(JdbcPostgreSqlMetadataRepository.TABLE_SQL.contains("reltablespace"));
        assertTrue(JdbcPostgreSqlMetadataRepository.TABLE_SQL.contains("reloptions"));
        assertTrue(JdbcPostgreSqlMetadataRepository.TABLE_SQL.contains("pg_database"));
    }
    @Test
    void mapsPersistentIndexAccessMethodTablespaceAndSupportedReloptions() {
        Map<String, String> options = JdbcPostgreSqlMetadataRepository.postgreSqlIndexPhysicalOptions(
                "gin", "idx_space",
                "fillfactor=75,fastupdate=off,gin_pending_list_limit=8192,autovacuum_enabled=false");

        assertEquals("GIN", options.get("POSTGRESQL_INDEX_METHOD"));
        assertEquals("idx_space", options.get("INDEX_TABLESPACE"));
        assertEquals("75", options.get("POSTGRESQL_INDEX_FILLFACTOR"));
        assertEquals("OFF", options.get("POSTGRESQL_GIN_FASTUPDATE"));
        assertEquals("8192", options.get("POSTGRESQL_GIN_PENDING_LIST_LIMIT"));
        assertFalse(options.keySet().stream().anyMatch(key -> key.contains("AUTOVACUUM")));
    }

    @Test
    void mapsPostgreSqlColumnStorageAndCompressionCatalogCodes() {
        Map<String, String> options = JdbcPostgreSqlMetadataRepository.postgreSqlColumnPhysicalOptions(
                "x", "l", "x");

        assertEquals("EXTENDED", options.get("POSTGRESQL_STORAGE"));
        assertEquals("LZ4", options.get("POSTGRESQL_COMPRESSION"));
        assertEquals("EXTENDED", options.get("POSTGRESQL_STORAGE_TYPE_DEFAULT"));
    }

    @Test
    void omitsIgnoredCompressionForPlainOrExternalStorage() {
        Map<String, String> plain = JdbcPostgreSqlMetadataRepository.postgreSqlColumnPhysicalOptions(
                "p", "\0", "p");
        Map<String, String> external = JdbcPostgreSqlMetadataRepository.postgreSqlColumnPhysicalOptions(
                "e", "p", "x");

        assertEquals("PLAIN", plain.get("POSTGRESQL_STORAGE"));
        assertEquals("PLAIN", plain.get("POSTGRESQL_STORAGE_TYPE_DEFAULT"));
        assertFalse(plain.containsKey("POSTGRESQL_COMPRESSION"));
        assertEquals("EXTERNAL", external.get("POSTGRESQL_STORAGE"));
        assertFalse(external.containsKey("POSTGRESQL_COMPRESSION"));
    }

    @Test
    void columnQueryReadsStorageCompressionAndTypeDefaultFromPgCatalog() {
        assertTrue(JdbcPostgreSqlMetadataRepository.COLUMNS_SQL.contains("a.attstorage"));
        assertTrue(JdbcPostgreSqlMetadataRepository.COLUMNS_SQL.contains("a.attcompression"));
        assertTrue(JdbcPostgreSqlMetadataRepository.COLUMNS_SQL.contains("t.typstorage"));
        assertTrue(JdbcPostgreSqlMetadataRepository.COLUMNS_SQL.contains("JOIN pg_type"));
    }

}
