package com.behsazan.schemaforge.validation.datatype;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDatatypeCompatibilityAnalyzerTest {
    private final DatatypeCompatibilityAnalyzer analyzer = new DatatypeCompatibilityAnalyzer();

    @Test
    void reportsEveryBlockingColumnInsteadOfStoppingAtFirstGenerationFailure() {
        Table table = Table.builder("TSTSHMA", "LEGACY_NUMBERS")
                .addColumn(Column.required("NO_PRECISION", DataType.simple("NUMBER")))
                .addColumn(Column.required("TOO_WIDE", DataType.numeric("NUMBER", 77, 0)))
                .addColumn(Column.required("ROW_LOCATOR", DataType.simple("ROWID")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MySqlDialect());

        assertTrue(result.blocking());
        assertEquals(3, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED")
                        && issue.path().equals("tables.LEGACY_NUMBERS.columns.NO_PRECISION")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MYSQL_DECIMAL_PRECISION_UNSUPPORTED")
                        && issue.path().equals("tables.LEGACY_NUMBERS.columns.TOO_WIDE")));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MYSQL_ROWID_UNSUPPORTED")
                        && issue.path().equals("tables.LEGACY_NUMBERS.columns.ROW_LOCATOR")));
    }

    @Test
    void acceptsCoveredMySqlFoundationTypes() {
        Table table = Table.builder("TSTSHMA", "COVERED_TYPES")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 18, 0)))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR2", 40)))
                .addColumn(Column.required("CREATED_AT", DataType.simple("DATE")))
                .build();

        DatatypeCompatibilityAssessment result = analyzer.analyze(
                DatabaseSchema.builder("TSTSHMA").addTable(table).build(), new MySqlDialect());

        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
    }
}
