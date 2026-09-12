package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMariaDbMetadataRepositoryTest {

    @Test
    void mapsMariaDbNativeColumnTypeAndPreservesQuotedCatalogDefault() {
        JdbcMariaDbMetadataRepository.MariaDbColumnRow row =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        2, "STATUS_CODE", "varchar", "varchar(30)",
                        30, null, null, null, false, "'ACTIVE'", null, null, "Status");

        Column column = JdbcMariaDbMetadataRepository.mapColumn(row);

        assertEquals("VARCHAR", column.dataType().name().normalized());
        assertEquals(30, column.dataType().length());
        assertEquals("varchar(30)", column.physicalOptions().get("MARIADB_NATIVE_COLUMN_TYPE"));
        assertEquals("'ACTIVE'", column.defaultValue().expression());
        assertFalse(column.nullable());
    }

    @Test
    void preservesMariaDbDefaultExpressionsWithoutDoubleQuoting() {
        JdbcMariaDbMetadataRepository.MariaDbColumnRow expressionRow =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        1, "CREATED_AT", "datetime", "datetime(6)",
                        null, null, null, 6, false, "current_timestamp(6)", null, null, null);
        JdbcMariaDbMetadataRepository.MariaDbColumnRow nullRow =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        2, "OPTIONAL_TEXT", "varchar", "varchar(20)",
                        20, null, null, null, true, "NULL", null, null, null);

        assertEquals("current_timestamp(6)", JdbcMariaDbMetadataRepository.defaultExpression(expressionRow));
        assertEquals("NULL", JdbcMariaDbMetadataRepository.defaultExpression(nullRow));
    }

    @Test
    void mapsMariaDbOwnedConstraintsForeignKeysAndStandaloneIndexes() {
        Table.Builder builder = Table.builder("APP", "CHILD")
                .addColumn(Column.required("ID", DataType.simple("BIGINT")))
                .addColumn(Column.nullable("PARENT_ID", DataType.simple("BIGINT")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 30)));

        List<JdbcMariaDbMetadataRepository.KeyConstraintRow> keys = List.of(
                new JdbcMariaDbMetadataRepository.KeyConstraintRow("PRIMARY", "PRIMARY KEY", "ID", 1),
                new JdbcMariaDbMetadataRepository.KeyConstraintRow("UK_CHILD_CODE", "UNIQUE", "CODE", 1));
        JdbcMariaDbMetadataRepository.mapKeyConstraints(builder, keys);
        JdbcMariaDbMetadataRepository.mapForeignKeys(builder, List.of(
                new JdbcMariaDbMetadataRepository.ForeignKeyRow(
                        "FK_CHILD_PARENT", "PARENT_ID", 1, "APP", "PARENT", "ID", "CASCADE", "NO ACTION")));
        JdbcMariaDbMetadataRepository.mapIndexes(builder, List.of(
                new JdbcMariaDbMetadataRepository.IndexRow("PRIMARY", true, 1, "ID", "A", null, "BTREE"),
                new JdbcMariaDbMetadataRepository.IndexRow("UK_CHILD_CODE", true, 1, "CODE", "A", null, "BTREE"),
                new JdbcMariaDbMetadataRepository.IndexRow("IX_CHILD_PARENT", false, 1, "PARENT_ID", "A", null, "BTREE")),
                keys);

        Table table = builder.build();

        assertTrue(table.primaryKey().isPresent());
        assertEquals("PRIMARY", table.primaryKey().orElseThrow().name().normalized());
        assertEquals(1, table.uniqueKeys().size());
        assertEquals(1, table.foreignKeys().size());
        assertEquals("CASCADE", table.foreignKeys().getFirst().onDelete().name());
        assertEquals(1, table.indexes().size());
        assertEquals("IX_CHILD_PARENT", table.indexes().getFirst().name().normalized());
        assertEquals("BTREE", table.indexes().getFirst().physicalOptions().get("MARIADB_INDEX_TYPE"));
    }

    @Test
    void mapsAutoIncrementAndGeneratedColumnsWithoutInventingDefaults() {
        JdbcMariaDbMetadataRepository.MariaDbColumnRow identityRow =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        1, "ID", "bigint", "bigint(20) unsigned", null, 19, 0, null,
                        false, null, "auto_increment", null, null);
        Column identity = JdbcMariaDbMetadataRepository.mapColumn(identityRow);
        assertTrue(identity.identity());
        assertEquals("bigint(20) unsigned", identity.physicalOptions().get("MARIADB_NATIVE_COLUMN_TYPE"));

        JdbcMariaDbMetadataRepository.MariaDbColumnRow generatedRow =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        3, "TOTAL", "decimal", "decimal(18,2)", null, 18, 2, null,
                        true, null, "STORED GENERATED", "`QTY` * `PRICE`", null);
        Column generated = JdbcMariaDbMetadataRepository.mapColumn(generatedRow);
        assertTrue(generated.generated());
        assertFalse(generated.defaultValue().isPresent());
    }

    @Test
    void preservesUnsignedNativeTypeInProfilesAndRecognizesJsonAliasEvidence() {
        assertEquals("BIGINT(20) UNSIGNED", JdbcMariaDbMetadataRepository.profileSignature(
                "bigint", "bigint(20) unsigned", null, 20, 0));

        JdbcMariaDbMetadataRepository.MariaDbColumnRow payload =
                new JdbcMariaDbMetadataRepository.MariaDbColumnRow(
                        4, "PAYLOAD", "longtext", "longtext",
                        null,
                        null, null, null, true, null, null, null, null);
        JdbcMariaDbMetadataRepository.CheckRow jsonCheck =
                new JdbcMariaDbMetadataRepository.CheckRow("PAYLOAD", "json_valid(`PAYLOAD`)");

        Set<String> aliases = JdbcMariaDbMetadataRepository.detectJsonAliasColumns(
                List.of(payload), List.of(jsonCheck));
        assertEquals(Set.of("PAYLOAD"), aliases);
        assertEquals("JSON", JdbcMariaDbMetadataRepository.mapColumn(payload, true)
                .dataType().name().normalized());
    }

    @Test
    void mariadbCatalogQueriesUseMariaDbAvailableInformationSchemaColumns() {
        String indexes = JdbcMariaDbMetadataRepository.INDEXES_SQL.toLowerCase(Locale.ROOT);
        String checks = JdbcMariaDbMetadataRepository.CHECKS_SQL.toLowerCase(Locale.ROOT);

        assertTrue(indexes.contains("information_schema.statistics"));
        assertFalse(indexes.contains("expression"),
                "MariaDB INFORMATION_SCHEMA.STATISTICS has no MySQL expression column contract");
        assertTrue(checks.contains("cc.table_name = tc.table_name"),
                "MariaDB CHECK_CONSTRAINTS exposes TABLE_NAME; include it so duplicate constraint names stay table-scoped");
    }
}
