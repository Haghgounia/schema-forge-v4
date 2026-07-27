package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Dialect Capability.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class DialectCapabilityTest {

    @Test
    void oracleAndPostgreSqlShouldDeclareTheirSupportedCapabilities() {

        OracleDialect oracle = new OracleDialect();
        PostgreSqlDialect postgreSql = new PostgreSqlDialect();
        Db2ZosDialect db2Zos = new Db2ZosDialect();

        // PostgreSQL
        for (DialectFeature feature : DialectFeature.values()) {
            assertTrue(
                    postgreSql.supportedFeatures().contains(feature),
                    "PostgreSQL capability missing: " + feature
            );
        }

        // Oracle
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.SEQUENCE));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.IDENTITY_COLUMN));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.GENERATED_COLUMN));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.TABLE_COMMENT));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.COLUMN_COMMENT));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.GRANT));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.EXPRESSION_INDEX));
        assertTrue(oracle.supportedFeatures().contains(DialectFeature.DEFERRABLE_CONSTRAINT));

        // Oracle
        assertFalse(oracle.supportedFeatures().contains(DialectFeature.INDEX_INCLUDE));
        assertFalse(oracle.supportedFeatures().contains(DialectFeature.PARTIAL_INDEX));

        // Db2 for z/OS core scope
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.SEQUENCE));
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.IDENTITY_COLUMN));
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.GENERATED_COLUMN));
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.TABLE_COMMENT));
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.COLUMN_COMMENT));
        assertTrue(db2Zos.supportedFeatures().contains(DialectFeature.GRANT));
        assertFalse(db2Zos.supportedFeatures().contains(DialectFeature.INDEX_INCLUDE));
        assertFalse(db2Zos.supportedFeatures().contains(DialectFeature.PARTIAL_INDEX));
        assertFalse(db2Zos.supportedFeatures().contains(DialectFeature.DEFERRABLE_CONSTRAINT));
    }
}
