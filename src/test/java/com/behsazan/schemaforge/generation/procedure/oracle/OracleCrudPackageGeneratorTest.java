package com.behsazan.schemaforge.generation.procedure.oracle;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Regression tests for Oracle CRUD package rendering from the canonical table model.
 *
 * <p>The scenarios cover sequence-backed and composite primary keys, audit/default handling,
 * generated search filters, configurable grants and rejection of tables without a primary key.
 * The assertions also protect the transaction-neutral contract of generated packages.</p>
 */
class OracleCrudPackageGeneratorTest {

    @Test
    void generatesPackageFromOracleMetadataWithSequenceBackedKeyAndAuditColumns() {
        Table table = provinces();

        String sql = new OracleCrudPackageGenerator().generate(
                table,
                new OracleCrudGenerationOptions(500, List.of("U_DEVELOPER", "U_DESIGNER")));

        assertTrue(sql.contains("CREATE OR REPLACE PACKAGE BIM.PKG_PROVINCES"));
        assertTrue(sql.contains("CREATE OR REPLACE PACKAGE BODY BIM.PKG_PROVINCES"));
        assertTrue(sql.contains("AUTHID DEFINER"));
        assertTrue(sql.contains("PROCEDURE CREATE_ROW"));
        assertTrue(sql.contains("PROCEDURE UPDATE_ROW"));
        assertTrue(sql.contains("PROCEDURE DELETE_ROW"));
        assertTrue(sql.contains("PROCEDURE GET_BY_ID"));
        assertTrue(sql.contains("PROCEDURE SEARCH"));
        assertTrue(sql.contains("RETURNING PROVINCE_ID"));
        assertTrue(sql.contains("INTO O_PROVINCE_ID"));
        assertTrue(sql.contains("CREATED_DATE"));
        assertTrue(sql.contains("SYSTIMESTAMP"));
        assertTrue(sql.contains("P_PROVINCE_ENGLISH_NAME IN BIM.PROVINCES.PROVINCE_ENGLISH_NAME%TYPE DEFAULT NULL"));
        assertTrue(sql.contains("P_IS_ACTIVE IN BIM.PROVINCES.IS_ACTIVE%TYPE DEFAULT 1"));
        assertTrue(sql.contains("P_LIMIT must be between 1 and 500"));
        assertTrue(sql.contains("GRANT EXECUTE ON BIM.PKG_PROVINCES TO U_DEVELOPER;"));
        assertTrue(sql.contains("GRANT EXECUTE ON BIM.PKG_PROVINCES TO U_DESIGNER;"));
        assertFalse(sql.contains("COMMIT;"));
        assertFalse(sql.contains("ROLLBACK;"));
        assertFalse(sql.contains("WHEN OTHERS"));
    }

    @Test
    void supportsCompositeManuallyAssignedPrimaryKey() {
        Table table = Table.builder("BIM", "ACCOUNT_LIMITS")
                .addColumn(column("ACCOUNT_ID", DataType.numeric("NUMBER", 18, 0), false, null, false, 1))
                .addColumn(column("LIMIT_TYPE", DataType.varchar("VARCHAR2", 20), false, null, false, 2))
                .addColumn(column("LIMIT_AMOUNT", DataType.numeric("NUMBER", 18, 2), false, null, false, 3))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_ACCOUNT_LIMITS"),
                        List.of(Identifier.of("ACCOUNT_ID"), Identifier.of("LIMIT_TYPE"))))
                .build();

        String sql = new OracleCrudPackageGenerator().generate(table);

        assertTrue(sql.contains("P_ACCOUNT_ID IN BIM.ACCOUNT_LIMITS.ACCOUNT_ID%TYPE"));
        assertTrue(sql.contains("P_LIMIT_TYPE IN BIM.ACCOUNT_LIMITS.LIMIT_TYPE%TYPE"));
        assertTrue(sql.contains("WHERE ACCOUNT_ID = P_ACCOUNT_ID"));
        assertTrue(sql.contains("AND LIMIT_TYPE = P_LIMIT_TYPE"));
        assertFalse(sql.contains("RETURNING ACCOUNT_ID"));
    }

    @Test
    void rejectsTableWithoutPrimaryKey() {
        Table table = Table.builder("BIM", "NO_KEY_TABLE")
                .addColumn(column("VALUE_CODE", DataType.varchar("VARCHAR2", 20), false, null, false, 1))
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new OracleCrudPackageGenerator().generate(table));

        assertTrue(error.getMessage().contains("requires a primary key"));
    }

    @Test
    void derivesSearchFiltersFromPrimaryUniqueAndStatusColumns() {
        String sql = new OracleCrudPackageGenerator().generate(provinces());

        assertTrue(sql.contains("P_PROVINCE_ID IN BIM.PROVINCES.PROVINCE_ID%TYPE DEFAULT NULL"));
        assertTrue(sql.contains("P_PROVINCE_CODE IN BIM.PROVINCES.PROVINCE_CODE%TYPE DEFAULT NULL"));
        assertTrue(sql.contains("P_IS_ACTIVE IN BIM.PROVINCES.IS_ACTIVE%TYPE DEFAULT NULL"));
        assertTrue(sql.contains("ORDER BY T.PROVINCE_ID"));
    }

    private static Table provinces() {
        return Table.builder("BIM", "PROVINCES")
                .addColumn(column("PROVINCE_ID", DataType.numeric("NUMBER", 2, 0), false,
                        "BIM.SEQ_PROVINCES.NEXTVAL", false, 1))
                .addColumn(column("PROVINCE_CODE", DataType.numeric("NUMBER", 10, 0), false,
                        null, false, 2))
                .addColumn(column("PROVINC_NAME", DataType.varchar("VARCHAR2", 50), false,
                        null, false, 3))
                .addColumn(column("PROVINCE_ENGLISH_NAME", DataType.varchar("VARCHAR2", 50), true,
                        null, false, 4))
                .addColumn(column("IS_ACTIVE", DataType.numeric("NUMBER", 1, 0), false,
                        "1", false, 5))
                .addColumn(column("CREATED_BY", DataType.varchar("VARCHAR2", 50), false,
                        null, false, 6))
                .addColumn(column("CREATED_DATE", DataType.simple("TIMESTAMP"), false,
                        null, false, 7))
                .addColumn(column("LAST_MODIFIED_BY", DataType.varchar("VARCHAR2", 50), false,
                        null, false, 8))
                .addColumn(column("LAST_MODIFIED_DATE", DataType.simple("TIMESTAMP"), false,
                        null, false, 9))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_PROVINCES"),
                        List.of(Identifier.of("PROVINCE_ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_PROVINCES_U1"),
                        List.of(Identifier.of("PROVINCE_CODE"))))
                .build();
    }

    private static Column column(String name, DataType type, boolean nullable,
                                 String defaultExpression, boolean identity, int position) {
        return new Column(
                Identifier.of(name),
                type,
                nullable,
                new DefaultValue(defaultExpression),
                Description.empty(),
                identity,
                position,
                null);
    }
}
