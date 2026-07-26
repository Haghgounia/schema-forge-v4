package com.behsazan.schemaforge.generation.issue;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertColumnContains;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertInlineIssues;
import static com.behsazan.schemaforge.testsupport.SqlAssertionHelper.assertValidationHeaderBeforeDdl;

class DdlIssueRenderingTest {

    @Test
    void shouldConsolidateTopFindingsAndRenderMultipleInlineIssuesAfterComma() {
        Column first = new Column(Identifier.of("PROVINC_NAME"),
                DataType.varchar("VARCHAR2", 50, LengthSemantics.CHAR), false,
                null, Description.empty(), false, 1);
        Column second = new Column(Identifier.of("IS_ACTIVE"),
                DataType.numeric("NUMBER", 1, 0), false,
                null, Description.empty(), false, 2);
        Table table = Table.builder("BIM", "PROVINCES")
                .addColumn(first)
                .addColumn(second)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .metadata("recovery.warnings",
                        "DUPLICATE_COLUMN|name=PROVINC_NAME|firstRow=10|duplicateRow=11|definition=PROVINC_NAME VARCHAR2(50)")
                .addTable(table)
                .build();
        ValidationReport report = new ValidationReport(false, List.of(
                new ValidationIssue("ERROR", "INVALID_DEFAULT_VALUE",
                        "tables.PROVINCES.columns.PROVINC_NAME", "Invalid default"),
                new ValidationIssue("WARNING", "SPELLING_WARNING",
                        "tables.PROVINCES.columns.PROVINC_NAME", "Possible spelling error"),
                new ValidationIssue("WARNING", "METADATA_DATATYPE_MISMATCH",
                        "tables.PROVINCES.columns.PROVINC_NAME", "Expected NUMBER")
        ));

        String sql = new DdlGenerator(new OracleDialect()).generate(schema, report);

        assertValidationHeaderBeforeDdl(sql, "SchemaForge Offline Oracle DDL");
        assertColumnContains(sql, "PROVINC_NAME", "VARCHAR2(50 CHAR)", "NOT NULL", ",");
        assertInlineIssues(sql, "PROVINC_NAME", "E", "DEFAULT");
        assertInlineIssues(sql, "PROVINC_NAME", "W", "DUP", "TYPE", "SPELL");
        assertColumnContains(sql, "IS_ACTIVE", "NUMBER(1,0)", "NOT NULL");
    }
}
