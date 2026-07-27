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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies table-level validation hints on the CREATE TABLE line.
 *
 * <p>Column findings remain attached to column definitions, while schema, location,
 * spelling and pluralization findings are rendered beside the table declaration.</p>
 *
 * @since 4.1
 */
class TableInlineIssueRenderingTest {

    @Test
    void shouldRenderTableHintsOnCreateTableLine() {
        Column id = new Column(Identifier.of("ID"),
                DataType.numeric("NUMBER", 19, 0), false,
                null, Description.empty(), false, 1);
        Table table = Table.builder("BIM", "CUSTOMER")
                .addColumn(id)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .addTable(table)
                .build();
        String tablePath = "tables.CUSTOMER";
        ValidationReport report = new ValidationReport(true, List.of(
                new ValidationIssue("WARNING", "SCHEMA_NOT_FOUND", tablePath, "Schema does not exist"),
                new ValidationIssue("WARNING", "TABLE_IN_DIFFERENT_SCHEMA", tablePath, "Table exists elsewhere"),
                new ValidationIssue("WARNING", "SPELLING_WARNING", tablePath, "Possible spelling error"),
                new ValidationIssue("WARNING", "TABLE_NAME_NOT_PLURAL", tablePath, "Table name is singular")
        ));

        String sql = new DdlGenerator(new OracleDialect()).generate(schema, report);

        assertTrue(sql.contains("CREATE TABLE BIM.CUSTOMER -- W:SCHEMA|SPELL|TBL-SCHEMA|TABLE-PLURAL"), sql);
    }

    @Test
    void shouldNotMoveColumnHintsToCreateTableLine() {
        Column id = new Column(Identifier.of("CUSTMER_ID"),
                DataType.numeric("NUMBER", 19, 0), false,
                null, Description.empty(), false, 1);
        Table table = Table.builder("BIM", "CUSTOMERS")
                .addColumn(id)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("BIM")
                .addTable(table)
                .build();
        ValidationReport report = new ValidationReport(true, List.of(
                new ValidationIssue("WARNING", "SPELLING_WARNING",
                        "tables.CUSTOMERS.columns.CUSTMER_ID", "Possible spelling error")
        ));

        String sql = new DdlGenerator(new OracleDialect()).generate(schema, report);

        assertTrue(sql.contains("CREATE TABLE BIM.CUSTOMERS" + System.lineSeparator() + "("), sql);
        assertTrue(sql.contains("CUSTMER_ID NUMBER(19,0) NOT NULL -- W:SPELL"), sql);
    }
}
