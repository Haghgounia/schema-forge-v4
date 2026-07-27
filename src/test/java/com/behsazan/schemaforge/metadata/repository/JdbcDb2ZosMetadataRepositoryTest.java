package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Db2 for z/OS catalog datatype and default-value conversion.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.2
 */
class JdbcDb2ZosMetadataRepositoryTest {

    @Test
    void mapsExactNumericAndCharacterCatalogTypes() {
        DataType decimal = JdbcDb2ZosMetadataRepository.mapDataType(row("AMOUNT", "DECIMAL", 12, 0, 2));
        DataType varchar = JdbcDb2ZosMetadataRepository.mapDataType(row("NAME", "VARCHAR", 80, 0, 0));
        DataType vargraphic = JdbcDb2ZosMetadataRepository.mapDataType(row("LOCAL_NAME", "VARG", 40, 0, 0));

        assertEquals("DECIMAL", decimal.name().normalized());
        assertEquals(12, decimal.precision());
        assertEquals(2, decimal.scale());
        assertEquals("VARCHAR", varchar.name().normalized());
        assertEquals(80, varchar.length());
        assertEquals("VARGRAPHIC", vargraphic.name().normalized());
        assertEquals(40, vargraphic.length());
    }

    @Test
    void mapsTemporalAndDecimalFloatCatalogTypes() {
        DataType defaultTimestamp = JdbcDb2ZosMetadataRepository.mapDataType(
                row("CREATED_AT", "TIMESTMP", 10, 0, 0));
        DataType timestampTwelve = JdbcDb2ZosMetadataRepository.mapDataType(
                row("UPDATED_AT", "TIMESTMP", 13, 0, 12));
        DataType decimalFloat16 = JdbcDb2ZosMetadataRepository.mapDataType(
                row("RATE", "DECFLOAT", 8, 0, 0));
        DataType decimalFloat34 = JdbcDb2ZosMetadataRepository.mapDataType(
                row("TOTAL", "DECFLOAT", 16, 0, 0));

        DataType nativeDate = JdbcDb2ZosMetadataRepository.mapDataType(
                row("BUSINESS_DATE", "DATE", 4, 0, 0));

        assertEquals("TIMESTAMP", defaultTimestamp.name().normalized());
        assertNull(defaultTimestamp.precision());
        assertEquals(12, timestampTwelve.precision());
        assertEquals(16, decimalFloat16.precision());
        assertEquals(34, decimalFloat34.precision());
        assertEquals("DB2_DATE", nativeDate.name().normalized());
    }

    @Test
    void mapsIdentityColumnWithoutTreatingItAsARegularDefault() {
        Column identity = JdbcDb2ZosMetadataRepository.mapColumn(row(
                "ID", "INTEGER", 4, 0, 0, false, "I", "1"));

        assertTrue(identity.identity());
        assertFalse(identity.defaultValue().isPresent());
        assertFalse(identity.nullable());
    }

    @Test
    void mapsLiteralNumericAndSpecialRegisterDefaults() {
        assertEquals("'ACTIVE'", JdbcDb2ZosMetadataRepository.mapDefault(
                row("STATUS", "VARCHAR", 20, 0, 0, false, "1", "ACTIVE")));
        assertEquals("42", JdbcDb2ZosMetadataRepository.mapDefault(
                row("RETRY_COUNT", "INTEGER", 4, 0, 0, false, "4", "42")));
        assertEquals("CURRENT DATE", JdbcDb2ZosMetadataRepository.mapDefault(
                row("BUSINESS_DATE", "DATE", 4, 0, 0, false, "a", "CURRENT DATE")));
        assertEquals("APP.CONTEXT_USER", JdbcDb2ZosMetadataRepository.mapDefault(
                row("CREATED_BY", "VARCHAR", 50, 0, 0, false, "b", "APP.CONTEXT_USER")));
    }

    @Test
    void mapsImplicitDefaultsByCatalogDatatype() {
        assertEquals("0", JdbcDb2ZosMetadataRepository.mapDefault(
                row("COUNTER", "BIGINT", 8, 0, 0, false, "Y", null)));
        assertEquals("''", JdbcDb2ZosMetadataRepository.mapDefault(
                row("CODE", "VARCHAR", 12, 0, 0, false, "B", null)));
        assertEquals("CURRENT TIMESTAMP", JdbcDb2ZosMetadataRepository.mapDefault(
                row("CREATED_AT", "TIMESTMP", 10, 0, 0, false, "Y", null)));
        assertEquals("NULL", JdbcDb2ZosMetadataRepository.mapDefault(
                row("OPTIONAL_VALUE", "INTEGER", 4, 0, 0, true, "Y", null)));
    }


    @Test
    void assemblesPrimaryAndUniqueConstraintsInCatalogOrder() {
        Table.Builder builder = baseTable();

        JdbcDb2ZosMetadataRepository.mapKeys(builder, java.util.List.of(
                new JdbcDb2ZosMetadataRepository.KeyConstraintRow("PK_SAMPLE", "P", "ID", 1),
                new JdbcDb2ZosMetadataRepository.KeyConstraintRow("UK_SAMPLE", "U", "CODE", 1)));

        Table table = builder.build();
        assertEquals("PK_SAMPLE", table.primaryKey().orElseThrow().name().value());
        assertEquals("ID", table.primaryKey().orElseThrow().columns().getFirst().value());
        assertEquals("UK_SAMPLE", table.uniqueKeys().getFirst().name().value());
        assertEquals("CODE", table.uniqueKeys().getFirst().columns().getFirst().value());
    }

    @Test
    void assemblesForeignKeyAndDeleteRule() {
        Table.Builder builder = baseTable();

        JdbcDb2ZosMetadataRepository.mapForeignKeys(builder, java.util.List.of(
                new JdbcDb2ZosMetadataRepository.ForeignKeyRow(
                        "FK_SAMPLE_PARENT", 1, "PARENT_ID", "APP", "PARENTS", "ID", "C")));

        Table table = builder.build();
        var foreignKey = table.foreignKeys().getFirst();
        assertEquals("FK_SAMPLE_PARENT", foreignKey.name().value());
        assertEquals("APP.PARENTS", foreignKey.referencedTable().toString());
        assertEquals(ReferentialAction.CASCADE, foreignKey.onDelete());
        assertEquals(ReferentialAction.NO_ACTION, foreignKey.onUpdate());
        assertTrue(foreignKey.schemaExplicit());
    }

    @Test
    void assemblesUniqueIndexWithDirectionAndIncludeColumn() {
        Table.Builder builder = baseTable();

        JdbcDb2ZosMetadataRepository.mapIndexes(builder, java.util.List.of(
                new JdbcDb2ZosMetadataRepository.IndexRow("UX_SAMPLE", "APP", "U", "CODE", 1, "D"),
                new JdbcDb2ZosMetadataRepository.IndexRow("UX_SAMPLE", "APP", "U", "ID", 2, "A"),
                new JdbcDb2ZosMetadataRepository.IndexRow("UX_SAMPLE", "APP", "U", "PARENT_ID", 3, null)));

        Table table = builder.build();
        var index = table.indexes().getFirst();
        assertNotNull(index.name());
        assertEquals("UX_SAMPLE", index.name().value());
        assertEquals(SortDirection.DESC, index.columns().getFirst().direction());
        assertEquals(SortDirection.ASC, index.columns().get(1).direction());
        assertEquals("PARENT_ID", index.includeColumns().getFirst().value());
    }

    @Test
    void catalogQueriesUseTheDb2SystemCatalogAndUncommittedRead() {
        assertTrue(JdbcDb2ZosMetadataRepository.TABLE_SQL.contains("SYSIBM.SYSTABLES"));
        assertTrue(JdbcDb2ZosMetadataRepository.COLUMNS_SQL.contains("SYSIBM.SYSCOLUMNS"));
        assertTrue(JdbcDb2ZosMetadataRepository.KEY_CONSTRAINTS_SQL.contains("SYSIBM.SYSTABCONST"));
        assertTrue(JdbcDb2ZosMetadataRepository.FOREIGN_KEYS_SQL.contains("SYSIBM.SYSRELS"));
        assertTrue(JdbcDb2ZosMetadataRepository.FOREIGN_KEYS_SQL.contains("SYSIBM.SYSFOREIGNKEYS"));
        assertTrue(JdbcDb2ZosMetadataRepository.CHECKS_SQL.contains("SYSIBM.SYSCHECKS"));
        assertTrue(JdbcDb2ZosMetadataRepository.INDEXES_SQL.contains("SYSIBM.SYSINDEXES"));
        assertTrue(JdbcDb2ZosMetadataRepository.COLUMNS_SQL.contains("HIDDEN = 'N'"));
        assertTrue(JdbcDb2ZosMetadataRepository.KEY_CONSTRAINTS_SQL.contains("K.ORDERING IN"));
        assertTrue(JdbcDb2ZosMetadataRepository.TABLE_SQL.contains("WITH UR"));
    }


    private static Table.Builder baseTable() {
        return Table.builder("APP", "SAMPLE")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR", 20)))
                .addColumn(Column.nullable("PARENT_ID", DataType.simple("INTEGER")));
    }

    private static JdbcDb2ZosMetadataRepository.Db2ColumnRow row(
            String name, String type, Integer length, Integer longLength, Integer scale) {
        return row(name, type, length, longLength, scale, true, "N", null);
    }


    private static JdbcDb2ZosMetadataRepository.Db2ColumnRow row(
            String name, String type, Integer length, Integer longLength, Integer scale,
            boolean nullable, String defaultIndicator, String defaultValue) {
        return new JdbcDb2ZosMetadataRepository.Db2ColumnRow(
                1, name, type, length, longLength, scale, nullable, null,
                defaultIndicator, defaultValue, null, "SYSIBM", type);
    }
}
