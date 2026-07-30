package com.behsazan.schemaforge.generation.procedure.sqlserver;

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

class SqlServerCrudProcedureGeneratorTest {

    @Test
    void generatesFiveProceduresFromSqlServerMetadataWithSequenceKeyAndAuditColumns() {
        String sql = new SqlServerCrudProcedureGenerator().generate(
                provinces(),
                new SqlServerCrudGenerationOptions(500, List.of("U_DEVELOPER", "U_DESIGNER")));

        assertTrue(sql.contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_CREATE]"));
        assertTrue(sql.contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_UPDATE]"));
        assertTrue(sql.contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_DELETE]"));
        assertTrue(sql.contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_GET_BY_ID]"));
        assertTrue(sql.contains("CREATE OR ALTER PROCEDURE [BIM].[PROVINCES_SEARCH]"));
        assertTrue(sql.contains("@O_PROVINCE_ID DECIMAL(2,0) OUTPUT"));
        assertTrue(sql.contains("OUTPUT INSERTED.[PROVINCE_ID]"));
        assertTrue(sql.contains("INTO @GENERATED_KEYS ([PROVINCE_ID])"));
        assertTrue(sql.contains("@P_IS_ACTIVE DECIMAL(1,0) = 1"));
        assertTrue(sql.contains("SYSDATETIME()"));
        assertTrue(sql.contains("THROW 50001"));
        assertTrue(sql.contains("THROW 50002"));
        assertTrue(sql.contains("THROW 50003"));
        assertTrue(sql.contains("THROW 50004"));
        assertTrue(sql.contains("OFFSET @P_OFFSET ROWS"));
        assertTrue(sql.contains("FETCH NEXT @P_LIMIT ROWS ONLY"));
        assertTrue(sql.contains("GRANT EXECUTE ON OBJECT::[BIM].[PROVINCES_CREATE] TO [U_DEVELOPER];"));
        assertTrue(sql.contains("GRANT EXECUTE ON OBJECT::[BIM].[PROVINCES_SEARCH] TO [U_DESIGNER];"));
        assertFalse(sql.contains("BEGIN TRANSACTION"));
        assertFalse(sql.contains("COMMIT"));
        assertFalse(sql.contains("ROLLBACK"));
    }

    @Test
    void supportsCompositeManuallyAssignedPrimaryKey() {
        Table table = Table.builder("BIM", "ACCOUNT_LIMITS")
                .addColumn(column("ACCOUNT_ID", DataType.numeric("DECIMAL", 18, 0), false, null, false, 1))
                .addColumn(column("LIMIT_TYPE", DataType.varchar("VARCHAR", 20), false, null, false, 2))
                .addColumn(column("LIMIT_AMOUNT", DataType.numeric("DECIMAL", 18, 2), false, null, false, 3))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_ACCOUNT_LIMITS"),
                        List.of(Identifier.of("ACCOUNT_ID"), Identifier.of("LIMIT_TYPE"))))
                .build();

        String sql = new SqlServerCrudProcedureGenerator().generate(table);

        assertTrue(sql.contains("@P_ACCOUNT_ID DECIMAL(18,0)"));
        assertTrue(sql.contains("@P_LIMIT_TYPE VARCHAR(20)"));
        assertTrue(sql.contains("T.[ACCOUNT_ID] = @P_ACCOUNT_ID"));
        assertTrue(sql.contains("T.[LIMIT_TYPE] = @P_LIMIT_TYPE"));
        assertFalse(sql.contains("@O_ACCOUNT_ID"));
        assertFalse(sql.contains("@GENERATED_KEYS"));
    }

    @Test
    void rejectsTableWithoutPrimaryKey() {
        Table table = Table.builder("BIM", "NO_KEY_TABLE")
                .addColumn(column("VALUE_CODE", DataType.varchar("VARCHAR", 20), false, null, false, 1))
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new SqlServerCrudProcedureGenerator().generate(table));

        assertTrue(error.getMessage().contains("requires a primary key"));
    }

    @Test
    void derivesSearchFiltersFromPrimaryUniqueAndStatusColumns() {
        String sql = new SqlServerCrudProcedureGenerator().generate(provinces());

        assertTrue(sql.contains("@P_PROVINCE_ID DECIMAL(2,0) = NULL"));
        assertTrue(sql.contains("@P_PROVINCE_CODE DECIMAL(10,0) = NULL"));
        assertTrue(sql.contains("@P_IS_ACTIVE DECIMAL(1,0) = NULL"));
        assertTrue(sql.contains("ORDER BY T.[PROVINCE_ID]"));
    }

    private static Table provinces() {
        return Table.builder("BIM", "PROVINCES")
                .addColumn(column("PROVINCE_ID", DataType.numeric("DECIMAL", 2, 0), false,
                        "(NEXT VALUE FOR [BIM].[SEQ_PROVINCES])", false, 1))
                .addColumn(column("PROVINCE_CODE", DataType.numeric("DECIMAL", 10, 0), false,
                        null, false, 2))
                .addColumn(column("PROVINCE_NAME", DataType.varchar("VARCHAR", 50), false,
                        null, false, 3))
                .addColumn(column("PROVINCE_ENGLISH_NAME", DataType.varchar("VARCHAR", 50), true,
                        null, false, 4))
                .addColumn(column("IS_ACTIVE", DataType.numeric("DECIMAL", 1, 0), false,
                        "((1))", false, 5))
                .addColumn(column("CREATED_BY", DataType.varchar("VARCHAR", 50), false,
                        null, false, 6))
                .addColumn(column("CREATED_DATE", DataType.numeric("DATETIME2", 6, null), false,
                        null, false, 7))
                .addColumn(column("LAST_MODIFIED_BY", DataType.varchar("VARCHAR", 50), false,
                        null, false, 8))
                .addColumn(column("LAST_MODIFIED_DATE", DataType.numeric("DATETIME2", 6, null), false,
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
