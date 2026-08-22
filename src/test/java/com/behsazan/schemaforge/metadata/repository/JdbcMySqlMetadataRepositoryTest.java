package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class JdbcMySqlMetadataRepositoryTest {

    @Test
    void mapsNativeColumnTypeForMigrationComparison() {
        JdbcMySqlMetadataRepository.MySqlColumnRow row = new JdbcMySqlMetadataRepository.MySqlColumnRow(
                2, "AMOUNT", "decimal", "decimal(18,2) unsigned",
                null, 18, 2, null, false, "0.00", null, null, "Amount");

        Column column = JdbcMySqlMetadataRepository.mapColumn(row);

        assertEquals("DECIMAL", column.dataType().name().normalized());
        assertEquals(18, column.dataType().precision());
        assertEquals(2, column.dataType().scale());
        assertEquals("decimal(18,2) unsigned", column.physicalOptions().get("MYSQL_NATIVE_COLUMN_TYPE"));
        assertEquals("0.00", column.defaultValue().expression());
        assertFalse(column.nullable());
    }

    @Test
    void mapsMySqlOwnedConstraintsForeignKeysAndStandaloneIndexes() {
        Table.Builder builder = Table.builder("APP", "CHILD")
                .addColumn(Column.required("ID", DataType.simple("BIGINT")))
                .addColumn(Column.nullable("PARENT_ID", DataType.simple("BIGINT")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 30)));

        List<JdbcMySqlMetadataRepository.KeyConstraintRow> keys = List.of(
                new JdbcMySqlMetadataRepository.KeyConstraintRow("PRIMARY", "PRIMARY KEY", "ID", 1),
                new JdbcMySqlMetadataRepository.KeyConstraintRow("UK_CHILD_CODE", "UNIQUE", "CODE", 1));
        JdbcMySqlMetadataRepository.mapKeyConstraints(builder, keys);
        JdbcMySqlMetadataRepository.mapForeignKeys(builder, List.of(
                new JdbcMySqlMetadataRepository.ForeignKeyRow(
                        "FK_CHILD_PARENT", "PARENT_ID", 1, "APP", "PARENT", "ID", "CASCADE", "NO ACTION")));
        JdbcMySqlMetadataRepository.mapIndexes(builder, List.of(
                new JdbcMySqlMetadataRepository.IndexRow("PRIMARY", true, 1, "ID", null, "A", null, "BTREE"),
                new JdbcMySqlMetadataRepository.IndexRow("UK_CHILD_CODE", true, 1, "CODE", null, "A", null, "BTREE"),
                new JdbcMySqlMetadataRepository.IndexRow("IX_CHILD_PARENT", false, 1, "PARENT_ID", null, "D", null, "BTREE")),
                keys);

        Table table = builder.build();

        assertTrue(table.primaryKey().isPresent());
        assertEquals("PRIMARY", table.primaryKey().orElseThrow().name().normalized());
        assertEquals(1, table.uniqueKeys().size());
        assertEquals(1, table.foreignKeys().size());
        assertEquals("CASCADE", table.foreignKeys().getFirst().onDelete().name());
        assertEquals(1, table.indexes().size());
        assertEquals("IX_CHILD_PARENT", table.indexes().getFirst().name().normalized());
        assertEquals("DESC", table.indexes().getFirst().columns().getFirst().direction().name());
    }

    @Test
    void mapsAutoIncrementAndGeneratedColumnsWithoutInventingDefaults() {
        JdbcMySqlMetadataRepository.MySqlColumnRow identityRow = new JdbcMySqlMetadataRepository.MySqlColumnRow(
                1, "ID", "bigint", "bigint", null, null, null, null,
                false, null, "auto_increment", null, null);
        Column identity = JdbcMySqlMetadataRepository.mapColumn(identityRow);
        assertTrue(identity.identity());

        JdbcMySqlMetadataRepository.MySqlColumnRow generatedRow = new JdbcMySqlMetadataRepository.MySqlColumnRow(
                3, "TOTAL", "decimal", "decimal(18,2)", null, 18, 2, null,
                true, null, "STORED GENERATED", "(`QTY` * `PRICE`)", null);
        Column generated = JdbcMySqlMetadataRepository.mapColumn(generatedRow);
        assertTrue(generated.generated());
        assertFalse(generated.defaultValue().isPresent());
    }
}
