package com.behsazan.schemaforge.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Data Type Canonicalizer.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class DataTypeCanonicalizerTest {
    private final DataTypeCanonicalizer canonicalizer = new DataTypeCanonicalizer();

    @Test
    void shouldCompareOracleTypesSemantically() {
        assertTrue(canonicalizer.equivalent("Oracle", "NUMBER(8)", "NUMBER(8,0)"));
        assertTrue(canonicalizer.equivalent("Oracle", "TIMESTAMP", "TIMESTAMP(6)"));
        assertTrue(canonicalizer.equivalent("Oracle", "VARCHAR2(50 CHAR)", "VARCHAR2(50)"));
        assertFalse(canonicalizer.equivalent("Oracle", "NUMBER(8,2)", "NUMBER(8,0)"));
    }

    @Test
    void shouldSupportAllPlannedDatabaseEngines() {
        assertTrue(canonicalizer.equivalent("DB2", "DECIMAL(8)", "DECIMAL(8,0)"));
        assertTrue(canonicalizer.equivalent("MySQL", "NUMERIC(8)", "DECIMAL(8,0)"));
        assertTrue(canonicalizer.equivalent("PostgreSQL", "NUMERIC(8)", "DECIMAL(8,0)"));
        assertTrue(canonicalizer.equivalent("SQL Server", "NUMERIC(8)", "DECIMAL(8,0)"));
    }

    @Test
    void shouldApplyDatabaseSpecificTemporalDefaults() {
        assertTrue(canonicalizer.equivalent("DB2", "TIMESTAMP", "TIMESTAMP(6)"));
        assertTrue(canonicalizer.equivalent("MySQL", "TIMESTAMP", "TIMESTAMP(0)"));
        assertTrue(canonicalizer.equivalent("SQL Server", "DATETIME2", "DATETIME2(7)"));
        assertFalse(canonicalizer.equivalent("SQL Server", "TIMESTAMP", "TIMESTAMP(6)"));
    }
}
