package com.behsazan.schemaforge.metadata.repository;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOracleMetadataRepositoryTest {

    @Test
    void mapsTablePhysicalCatalogValuesToExistingOraclePhysicalKeys() {
        var info = new JdbcOracleMetadataRepository.TableInfo(
                "CUSTOMERS", "Customer master", "TS_APP",
                10, 40, 4, "NO", "ENABLED", "ADVANCED", "8", false);

        Map<String, String> options = JdbcOracleMetadataRepository.oracleTablePhysicalOptions(info);

        assertEquals("TS_APP", options.get("TABLESPACE"));
        assertEquals("10", options.get("ORACLE_PCTFREE"));
        assertEquals("40", options.get("ORACLE_PCTUSED"));
        assertEquals("4", options.get("ORACLE_INITRANS"));
        assertEquals("NOLOGGING", options.get("ORACLE_TABLE_LOGGING"));
        assertEquals("ROW STORE COMPRESS ADVANCED", options.get("ORACLE_TABLE_COMPRESSION"));
        assertEquals("PARALLEL 8", options.get("ORACLE_TABLE_PARALLEL"));
    }

    @Test
    void catalogQueryReadsOnlyPersistentTablePhysicalStateAndNotSegmentCreationHistory() {
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("TABLESPACE_NAME"));
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("PCT_FREE"));
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("INI_TRANS"));
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("LOGGING"));
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("COMPRESSION"));
        assertTrue(JdbcOracleMetadataRepository.TABLE_SQL.contains("DEGREE"));
        assertTrue(!JdbcOracleMetadataRepository.TABLE_SQL.contains("SEGMENT_CREATED"));
    }
    @Test
    void mapsPersistentIndexPhysicalCatalogValuesToExistingOracleIndexKeys() {
        Map<String, String> options = JdbcOracleMetadataRepository.oracleIndexPhysicalOptions(
                "TS_IDX", 12, 6, "NO", "ENABLED", 2, "4");

        assertEquals("TS_IDX", options.get("INDEX_TABLESPACE"));
        assertEquals("12", options.get("ORACLE_INDEX_PCTFREE"));
        assertEquals("6", options.get("ORACLE_INDEX_INITRANS"));
        assertEquals("NOLOGGING", options.get("ORACLE_INDEX_LOGGING"));
        assertEquals("COMPRESS 2", options.get("ORACLE_INDEX_COMPRESSION"));
        assertEquals("PARALLEL 4", options.get("ORACLE_INDEX_PARALLEL"));
    }

}
