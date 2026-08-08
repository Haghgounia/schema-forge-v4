package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.validation.postgresql.PostgreSqlDdlSanityChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the PostgreSQL pre-write static DDL safety checks. */
class PostgreSqlDdlSanityCheckerTest {
    private final PostgreSqlDdlSanityChecker checker = new PostgreSqlDdlSanityChecker();

    @Test
    void acceptsSchemaForgePostgreSqlShape() {
        String sql = """
                \\encoding UTF8
                \\set ON_ERROR_STOP on
                CREATE SCHEMA IF NOT EXISTS tstshma AUTHORIZATION CURRENT_USER;
                CREATE TABLE tstshma.customer
                (
                  id NUMERIC(18,0) NOT NULL,
                  description VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT pk_customer PRIMARY KEY (id)
                );
                COMMENT ON TABLE tstshma.customer IS 'customer';
                CREATE INDEX ix_customer_description ON tstshma.customer(description);
                """;

        assertTrue(checker.inspect(sql).isEmpty());
    }

    @Test
    void rejectsCrossDialectLeakageAndInvalidPrecision() {
        String sql = """
                CREATE TABLE tstshma.customer
                (
                  id NUMBER(18),
                  happened_at TIMESTAMP(9),
                  description VARCHAR2(100)
                ) ENABLE;
                """;

        var issues = checker.inspect(sql);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("ORACLE_NUMBER")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("ORACLE_VARCHAR2")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("ORACLE_ENABLE")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("POSTGRESQL_TEMPORAL_PRECISION")));
    }
    @Test
    void ignoresCrossDialectWordsInsideStringLiterals() {
        String sql = """
                CREATE TABLE tstshma.note
                (
                  id NUMERIC(18,0),
                  text_value VARCHAR(200) DEFAULT 'NUMBER(18) ENABLE IDENTITY(1,1)'
                );
                """;

        assertTrue(checker.inspect(sql).isEmpty());
    }

}
