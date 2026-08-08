package com.behsazan.schemaforge.validation.sqlserver;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests offline SQL Server DDL validation against generated and deliberately invalid scripts.
 *
 * <p>The suite protects rejection of Oracle-only tokens, decimal precision overflow, oversized
 * bounded character types and unsupported temporal precision.</p>
 */
class SqlServerOfflineDdlValidatorTest {
    private final SqlServerOfflineDdlValidator validator = new SqlServerOfflineDdlValidator();

    @Test
    void acceptsGeneratedSqlServerDdl() {
        Column id = new Column(Identifier.of("ITEM_ID"), DataType.numeric("NUMBER", 9, 0),
                false, null, Description.empty(), true, 1);
        Column code = new Column(Identifier.of("ITEM_CODE"), DataType.varchar("VARCHAR2", 20),
                false, null, Description.empty(), false, 2);
        Table table = Table.builder("BIM", "ITEMS")
                .addColumn(id)
                .addColumn(code)
                .primaryKey(new PrimaryKey(Identifier.of("PK_ITEMS"), List.of(Identifier.of("ITEM_ID"))))
                .build();
        String sql = new DdlGenerator(new SqlServerDialect()).generate(
                DatabaseSchema.builder("BIM").addTable(table).build());

        SqlServerOfflineValidationResult result = validator.validate(sql);

        assertTrue(result.valid(), () -> result.issues().toString());
    }

    @Test
    void rejectsOracleTokensAndDecimalPrecisionAboveThirtyEight() {
        String sql = "CREATE TABLE [dbo].[BAD] (ID NUMBER(39) NOT NULL) TABLESPACE TS_APP;";

        SqlServerOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ORACLE_NUMBER")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ORACLE_TABLESPACE")));
    }

    @Test
    void rejectsOversizedBoundedVarcharAndTemporalPrecision() {
        String sql = "CREATE TABLE [dbo].[BAD] (A VARCHAR(9000), B DATETIME2(8));";

        SqlServerOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("TYPE_LENGTH_OUT_OF_RANGE")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("TEMPORAL_PRECISION_OUT_OF_RANGE")));
    }
    @Test
    void rejectsNegativeScaleAndScaleAbovePrecision() {
        String sql = "CREATE TABLE [dbo].[BAD] (A DECIMAL(18,-2), B DECIMAL(5,6));";

        SqlServerOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().filter(issue -> issue.code().equals("DECIMAL_SCALE_OUT_OF_RANGE")).count() == 2);
    }

}
