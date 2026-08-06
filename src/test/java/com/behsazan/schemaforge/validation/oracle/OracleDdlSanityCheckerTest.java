package com.behsazan.schemaforge.validation.oracle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleDdlSanityCheckerTest {
    private final OracleDdlSanityChecker checker = new OracleDdlSanityChecker();

    @Test
    void acceptsNormalizedLegacyOracleDdl() {
        String sql = """
                CREATE TABLE TSTSHMA.JTMSCUSTOMERS
                (
                  SHAHABSTATUS NUMBER(1) DEFAULT 0,
                  CUSTSTATUS NUMBER(1) DEFAULT 1,
                  REFIDSH NUMBER(16) DEFAULT 0,
                  CREATED_AT TIMESTAMP(9) DEFAULT CURRENT_TIMESTAMP
                );
                """;
        assertDoesNotThrow(() -> checker.requireValid(sql, "JTMSCUSTOMERS"));
    }

    @Test
    void acceptsQuotedPersianLiteralAndGeneratedInlineIssueComment() {
        String sql = """
                CREATE TABLE TSTSHMA.DEFAULT_LITERAL_TEST
                (
                  STATUS_TEXT VARCHAR2(20 CHAR) DEFAULT N'فعال', -- W:SPELL
                  STATUS_CODE NUMBER(1) DEFAULT 1
                );
                """;
        assertDoesNotThrow(() -> checker.requireValid(sql, "DEFAULT_LITERAL_TEST"));
    }

    @Test
    void rejectsTheReportedDefaultLeaksAndPrecisionErrors() {
        String sql = """
                CREATE TABLE TSTSHMA.JTMSCUSTOMERS
                (
                  SHAHABSTATUS NUMBER(1) DEFAULT 0 1- دائم 2- موقت,
                  CUSTSTATUS NUMBER(1) DEFAULT 1 1- فعال 0- غیرفعال,
                  REFIDSH NUMBER(16) DEFAULT 0 CTShahabInquiry,
                  INVALID_BARE NUMBER(8) DEFAULT CreateDate,
                  INVALID_NUMBER NUMBER(70),
                  INVALID_TIME TIMESTAMP(26)
                );
                """;
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> checker.requireValid(sql, "JTMSCUSTOMERS"));
        assertTrue(exception.getMessage().contains("ORACLE_DEFAULT_TRAILING_TEXT"));
        assertTrue(exception.getMessage().contains("ORACLE_DEFAULT_UNKNOWN_IDENTIFIER"));
        assertTrue(exception.getMessage().contains("ORACLE_NUMBER_PRECISION"));
        assertTrue(exception.getMessage().contains("ORACLE_TIMESTAMP_PRECISION"));
    }

    @Test
    void rejectsReservedIdentifiersAndTypeIncompatibleDefaults() {
        String sql = """
                CREATE TABLE TSTSHMA.USER
                (
                  ROWID NUMBER(3),
                  TIMEX TIMESTAMP(9) DEFAULT 0,
                  TIMEZ NUMBER(8) DEFAULT CURRENT_TIMESTAMP,
                  RESULTTYPE NUMBER(2) DEFAULT 999,
                  FARAGIRNO VARCHAR2(25 CHAR) DEFAULT - ' ',
                  DESCRIPTION VARCHAR2(8 CHAR) DEFAULT '111111111',
                  PAYLOAD VARCHAR2(7000 CHAR)
                );
                """;
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> checker.requireValid(sql, "LEGACY_ROOT_CAUSES"));
        assertTrue(exception.getMessage().contains("ORACLE_RESERVED_TABLE_NAME"));
        assertTrue(exception.getMessage().contains("ORACLE_RESERVED_COLUMN_NAME"));
        assertTrue(exception.getMessage().contains("ORACLE_TEMPORAL_NUMERIC_DEFAULT"));
        assertTrue(exception.getMessage().contains("ORACLE_NUMBER_TEMPORAL_DEFAULT"));
        assertTrue(exception.getMessage().contains("ORACLE_DEFAULT_EXCEEDS_NUMBER"));
        assertTrue(exception.getMessage().contains("ORACLE_DEFAULT_SIGNED_STRING"));
        assertTrue(exception.getMessage().contains("ORACLE_DEFAULT_EXCEEDS_LENGTH"));
        assertTrue(exception.getMessage().contains("ORACLE_VARCHAR2_LENGTH"));
    }

}
