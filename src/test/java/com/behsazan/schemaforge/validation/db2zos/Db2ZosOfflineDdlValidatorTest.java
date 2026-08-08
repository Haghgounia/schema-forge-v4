package com.behsazan.schemaforge.validation.db2zos;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests offline DB2 for z/OS DDL validation against generated and deliberately invalid scripts.
 *
 * <p>The suite covers accepted explicit constraint indexes, Oracle-token rejection, DB2 decimal
 * precision limits and the requirement for enforcing indexes on primary keys.</p>
 */
class Db2ZosOfflineDdlValidatorTest {

    private final Db2ZosOfflineDdlValidator validator = new Db2ZosOfflineDdlValidator();

    @Test
    void acceptsGeneratedDb2DdlWithExplicitConstraintIndexes() {
        Column id = new Column(Identifier.of("ITEM_ID"), DataType.numeric("NUMBER", 9, 0),
                false, null, Description.empty(), false, 1);
        Column code = new Column(Identifier.of("ITEM_CODE"), DataType.varchar("VARCHAR2", 20),
                false, null, Description.empty(), false, 2);
        Table table = Table.builder("BIM", "ITEMS")
                .addColumn(id)
                .addColumn(code)
                .primaryKey(new PrimaryKey(Identifier.of("PK_ITEMS"), List.of(Identifier.of("ITEM_ID"))))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_ITEMS_CODE"), List.of(Identifier.of("ITEM_CODE"))))
                .build();
        String sql = new DdlGenerator(new Db2ZosDialect()).generate(
                DatabaseSchema.builder("BIM").addTable(table).build());

        Db2ZosOfflineValidationResult result = validator.validate(sql);

        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(4, result.statementCount());
    }

    @Test
    void rejectsOracleTokensAndOutOfRangeDecimalPrecision() {
        String sql = "CREATE TABLE BIM.BAD_TABLE (ID NUMBER(32) NOT NULL) TABLESPACE TS_BIM;";

        Db2ZosOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ORACLE_NUMBER")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("ORACLE_TABLESPACE")));
    }

    @Test
    void rejectsMissingExplicitPrimaryKeyIndex() {
        String sql = "CREATE TABLE BIM.ITEMS (ITEM_ID INTEGER NOT NULL, "
                + "CONSTRAINT PK_ITEMS PRIMARY KEY (ITEM_ID));";

        Db2ZosOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code().equals("REQUIRED_ENFORCING_INDEX_MISSING")));
    }

    @Test
    void rejectsDb2DecimalPrecisionAboveThirtyOne() {
        String sql = "CREATE TABLE BIM.ITEMS (AMOUNT DECIMAL(32,0));";

        Db2ZosOfflineValidationResult result = validator.validate(sql);

        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(issue -> issue.code().equals("DECIMAL_PRECISION_OUT_OF_RANGE")));
    }
}
