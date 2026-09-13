package com.behsazan.schemaforge.validation.datatype;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbDatatypeCompatibilityAnalyzerTest {
    private final DatatypeCompatibilityAnalyzer analyzer = new DatatypeCompatibilityAnalyzer();

    @Test
    void enforcesMariaDbLosslessDatatypeLimits() {
        Table table = Table.builder("TSTSHMA", "LEGACY_TYPES")
                .addColumn(Column.required("NO_PRECISION", DataType.simple("NUMBER")))
                .addColumn(Column.required("TOO_WIDE", DataType.numeric("NUMBER", 77, 0)))
                .addColumn(Column.required("FIXED_TOO_LONG", DataType.varchar("CHAR", 300)))
                .addColumn(Column.required("ROW_LOCATOR", DataType.simple("ROWID")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MariaDbDialect());

        assertTrue(result.blocking());
        assertEquals(4, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("NUMERIC_PRECISION_UNSPECIFIED")
                        && issue.severity().equals("WARNING")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MARIADB_DECIMAL_PRECISION_UNSUPPORTED")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MARIADB_FIXED_CHAR_LENGTH_UNSUPPORTED")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MARIADB_ROWID_UNSUPPORTED")));
    }

    @Test
    void acceptsCoveredMariaDbFoundationTypes() {
        Table table = Table.builder("TSTSHMA", "COVERED_TYPES")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 40)))
                .addColumn(Column.required("CREATED_AT", DataType.simple("DATE")))
                .addColumn(Column.nullable("PAYLOAD", DataType.simple("LONGTEXT")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MariaDbDialect());

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
    }

    @Test
    void reportsTimezoneAdaptationConsistentlyWithMariaDbDialect() {
        Table table = Table.builder("TSTSHMA", "EVENTS")
                .addColumn(Column.required("OCCURRED_AT", DataType.simple("TIMESTAMP_WITH_TIME_ZONE")))
                .addColumn(Column.required("LOCAL_OCCURRED_AT", DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MariaDbDialect());

        assertEquals(2, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MARIADB_TIMEZONE_TIMESTAMP_TEXT_ADAPTATION")
                        && issue.severity().equals("WARNING")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MARIADB_LOCAL_TIMEZONE_TIMESTAMP_UNSUPPORTED")
                        && issue.severity().equals("ERROR")));
        assertFalse(result.issues().stream().allMatch(issue -> issue.severity().equals("WARNING")));
    }
    @Test
    void reportsMariaDbNativeTypeOutsideCurrentLosslessCoverage() {
        Table table = Table.builder("TSTSHMA", "EXTERNAL_TABLE")
                .addColumn(Column.nullable("LEGACY_CODE", DataType.simple("MEDIUMINT")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MariaDbDialect());

        assertTrue(result.blocking());
        assertEquals(1, result.issues().size());
        assertEquals("MARIADB_DATATYPE_UNSUPPORTED", result.issues().getFirst().code());
    }

}
