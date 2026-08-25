package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies strategy-aware equivalence between exact numeric and native integer types. */
class NumericTypeEquivalenceServiceTest {
    private final NumericTypeEquivalenceService equivalence = new NumericTypeEquivalenceService();

    @Test
    void shouldRecognizePostgreSqlOptimizedIntegerMappings() {
        assertTrue(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(2,0)", "SMALLINT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "PostgreSQL", "INTEGER", "NUMERIC(8)", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(10,0)", "BIGINT", NumericMappingStrategy.OPTIMIZED));
    }

    @Test
    void shouldRecognizeDb2ZosOptimizedIntegerMappings() {
        assertTrue(equivalence.equivalent(
                "Db2Zos", "DECIMAL(4,0)", "SMALLINT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "DB2_ZOS", "DECIMAL(9)", "INT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "DB2-ZOS", "BIGINT", "DECIMAL(18,0)", NumericMappingStrategy.OPTIMIZED));
    }


    @Test
    void shouldRecognizeSqlServerOptimizedIntegerMappings() {
        assertTrue(equivalence.equivalent(
                "SQLServer", "DECIMAL(4,0)", "SMALLINT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "MSSQL", "NUMERIC(9)", "INT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "Microsoft SQL Server", "BIGINT", "DECIMAL(18,0)", NumericMappingStrategy.OPTIMIZED));
        assertFalse(equivalence.equivalent(
                "SQLServer", "DECIMAL(10,0)", "INT", NumericMappingStrategy.OPTIMIZED));
    }

    @Test
    void shouldRecognizeMySqlOptimizedIntegerMappings() {
        assertTrue(equivalence.equivalent(
                "MySQL", "DECIMAL(4,0)", "SMALLINT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "MySQL", "DECIMAL(9)", "INT", NumericMappingStrategy.OPTIMIZED));
        assertTrue(equivalence.equivalent(
                "MySQL", "BIGINT", "DECIMAL(18,0)", NumericMappingStrategy.OPTIMIZED));
        assertFalse(equivalence.equivalent(
                "MySQL", "DECIMAL(19,0)", "BIGINT", NumericMappingStrategy.OPTIMIZED));
    }

    @Test
    void shouldKeepUnsafeOrUnrelatedTypesDifferent() {
        assertFalse(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(2,0)", "SMALLINT", NumericMappingStrategy.SAFE));
        assertFalse(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(5,2)", "INTEGER", NumericMappingStrategy.OPTIMIZED));
        assertFalse(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(19,0)", "BIGINT", NumericMappingStrategy.OPTIMIZED));
        assertFalse(equivalence.equivalent(
                "PostgreSQL", "VARCHAR(10)", "BIGINT", NumericMappingStrategy.OPTIMIZED));
        assertFalse(equivalence.equivalent(
                "PostgreSQL", "NUMERIC(2,0)", "INTEGER", NumericMappingStrategy.OPTIMIZED));
    }
}
