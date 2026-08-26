package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R7.10 P2 contract tests for the Db2 LUW SYSCAT metadata repository. */
class JdbcDb2LuwMetadataRepositoryTest {

    @Test
    void mapsCoreLuwCatalogTypesLosslessly() {
        DataType decimal = JdbcDb2LuwMetadataRepository.mapDataType(row("AMOUNT", "SYSIBM", "DECIMAL", 12, 2));
        DataType varchar = JdbcDb2LuwMetadataRepository.mapDataType(row("NAME", "SYSIBM", "VARCHAR", 80, 0));
        DataType timestamp = JdbcDb2LuwMetadataRepository.mapDataType(row("CREATED_AT", "SYSIBM", "TIMESTAMP", 10, 6));
        DataType timestampZero = JdbcDb2LuwMetadataRepository.mapDataType(row("SOURCE_DATE", "SYSIBM", "TIMESTAMP", 10, 0));
        DataType date = JdbcDb2LuwMetadataRepository.mapDataType(row("BUSINESS_DATE", "SYSIBM", "DATE", 4, 0));
        DataType decfloat = JdbcDb2LuwMetadataRepository.mapDataType(row("RATE", "SYSIBM", "DECFLOAT", 16, 0));

        assertEquals("DECIMAL", decimal.name().normalized());
        assertEquals(12, decimal.precision());
        assertEquals(2, decimal.scale());
        assertEquals("VARCHAR", varchar.name().normalized());
        assertEquals(80, varchar.length());
        assertEquals("TIMESTAMP", timestamp.name().normalized());
        assertEquals(6, timestamp.precision());
        assertEquals("DB2_LUW_TIMESTAMP0", timestampZero.name().normalized());
        assertEquals("DB2_DATE", date.name().normalized());
        assertEquals("DECFLOAT", decfloat.name().normalized());
        assertEquals(34, decfloat.precision());
    }

    @Test
    void mapsIdentityGeneratedExpressionAndRegularDefaultSeparately() {
        Column identity = JdbcDb2LuwMetadataRepository.mapColumn(new JdbcDb2LuwMetadataRepository.Db2LuwColumnRow(
                1, "ID", "SYSIBM", "INTEGER", 4, 0, null,
                false, null, null, true, "D", null));
        Column generated = JdbcDb2LuwMetadataRepository.mapColumn(new JdbcDb2LuwMetadataRepository.Db2LuwColumnRow(
                2, "TOTAL", "SYSIBM", "DECIMAL", 12, 2, null,
                true, null, null, false, "A", "AS (QTY * PRICE)"));
        Column defaulted = JdbcDb2LuwMetadataRepository.mapColumn(new JdbcDb2LuwMetadataRepository.Db2LuwColumnRow(
                3, "STATUS", "SYSIBM", "VARCHAR", 20, 0, 20,
                false, "'ACTIVE'", "status", false, null, null));

        assertTrue(identity.identity());
        assertFalse(identity.defaultValue().isPresent());
        assertEquals("QTY * PRICE", generated.generatedExpression());
        assertFalse(generated.defaultValue().isPresent());
        assertEquals("'ACTIVE'", defaulted.defaultValue().expression());
        assertEquals("status", defaulted.description().value());
    }

    @Test
    void assemblesKeysForeignKeysAndIndexesInCatalogOrder() {
        Table.Builder builder = baseTable();
        var keys = java.util.List.of(
                new JdbcDb2LuwMetadataRepository.KeyConstraintRow("PK_SAMPLE", "P", "ID", 1),
                new JdbcDb2LuwMetadataRepository.KeyConstraintRow("UK_SAMPLE", "U", "CODE", 1));
        JdbcDb2LuwMetadataRepository.mapKeys(builder, keys);
        JdbcDb2LuwMetadataRepository.mapForeignKeys(builder, java.util.List.of(
                new JdbcDb2LuwMetadataRepository.ForeignKeyRow(
                        "FK_SAMPLE_PARENT", 1, "PARENT_ID", "APP", "PARENTS", "ID", "C", "R")));
        JdbcDb2LuwMetadataRepository.mapIndexes(builder, java.util.List.of(
                // Backing PK index: must not appear as a duplicate logical index.
                new JdbcDb2LuwMetadataRepository.IndexRow("APP", "SQL_PK_SAMPLE", "P", 1, "A", "ID", "N", null),
                // User index with descending key and INCLUDE column.
                new JdbcDb2LuwMetadataRepository.IndexRow("APP", "IX_SAMPLE", "D", 1, "D", "CODE", "N", null),
                new JdbcDb2LuwMetadataRepository.IndexRow("APP", "IX_SAMPLE", "D", 2, "I", "PARENT_ID", "N", null)), keys);

        Table table = builder.build();
        assertEquals("PK_SAMPLE", table.primaryKey().orElseThrow().name().value());
        assertEquals("UK_SAMPLE", table.uniqueKeys().getFirst().name().value());
        assertEquals(1, table.foreignKeys().size());
        assertEquals(ReferentialAction.CASCADE, table.foreignKeys().getFirst().onDelete());
        assertEquals(ReferentialAction.RESTRICT, table.foreignKeys().getFirst().onUpdate());
        assertEquals(1, table.indexes().size());
        assertEquals("IX_SAMPLE", table.indexes().getFirst().name().value());
        assertEquals(SortDirection.DESC, table.indexes().getFirst().columns().getFirst().direction());
        assertEquals("PARENT_ID", table.indexes().getFirst().includeColumns().getFirst().value());
    }

    @Test
    void catalogQueriesUseDocumentedLuwSyscatViewsAndUncommittedRead() {
        assertTrue(JdbcDb2LuwMetadataRepository.TABLE_SQL.contains("SYSCAT.TABLES"));
        assertTrue(JdbcDb2LuwMetadataRepository.COLUMNS_SQL.contains("SYSCAT.COLUMNS"));
        assertTrue(JdbcDb2LuwMetadataRepository.KEY_CONSTRAINTS_SQL.contains("SYSCAT.TABCONST"));
        assertTrue(JdbcDb2LuwMetadataRepository.KEY_CONSTRAINTS_SQL.contains("SYSCAT.KEYCOLUSE"));
        assertTrue(JdbcDb2LuwMetadataRepository.FOREIGN_KEYS_SQL.contains("SYSCAT.REFERENCES"));
        assertTrue(JdbcDb2LuwMetadataRepository.CHECKS_SQL.contains("SYSCAT.CHECKS"));
        assertTrue(JdbcDb2LuwMetadataRepository.INDEXES_SQL.contains("SYSCAT.INDEXES"));
        assertTrue(JdbcDb2LuwMetadataRepository.INDEXES_SQL.contains("SYSCAT.INDEXCOLUSE"));
        assertTrue(JdbcDb2LuwMetadataRepository.TABLE_SQL.contains("WITH UR"));
        assertTrue(JdbcDb2LuwMetadataRepository.COLUMNS_SQL.contains("COALESCE(HIDDEN, ' ') = ' '"));
    }

    private static Table.Builder baseTable() {
        return Table.builder("APP", "SAMPLE")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR", 20)))
                .addColumn(Column.nullable("PARENT_ID", DataType.simple("INTEGER")));
    }

    private static JdbcDb2LuwMetadataRepository.Db2LuwColumnRow row(
            String name, String typeSchema, String typeName, Integer length, Integer scale) {
        return new JdbcDb2LuwMetadataRepository.Db2LuwColumnRow(
                1, name, typeSchema, typeName, length, scale, null,
                true, null, null, false, null, null);
    }
}
