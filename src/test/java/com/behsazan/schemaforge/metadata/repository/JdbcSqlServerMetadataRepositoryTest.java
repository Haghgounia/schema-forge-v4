package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies SQL Server catalog-to-canonical-model conversion. */
class JdbcSqlServerMetadataRepositoryTest {

    @Test
    void mapsExactNumericAndNativeIntegerTypes() {
        DataType decimal = JdbcSqlServerMetadataRepository.mapDataType(
                row("AMOUNT", "decimal", "sys", "decimal", 17, 12, 2));
        DataType integer = JdbcSqlServerMetadataRepository.mapDataType(
                row("COUNTER", "int", "sys", "int", 4, 10, 0));

        assertEquals("DECIMAL", decimal.name().normalized());
        assertEquals(12, decimal.precision());
        assertEquals(2, decimal.scale());
        assertEquals("INT", integer.name().normalized());
    }

    @Test
    void convertsUnicodeByteLengthToCharacterLengthAndPreservesMaxTypes() {
        DataType nvarchar = JdbcSqlServerMetadataRepository.mapDataType(
                row("NAME", "nvarchar", "sys", "nvarchar", 100, 0, 0));
        DataType varcharMax = JdbcSqlServerMetadataRepository.mapDataType(
                row("BODY", "varchar", "sys", "varchar", -1, 0, 0));

        assertEquals("NVARCHAR", nvarchar.name().normalized());
        assertEquals(50, nvarchar.length());
        assertEquals("VARCHAR_MAX", varcharMax.name().normalized());
    }

    @Test
    void distinguishesSqlServerDateAndRowversionFromOracleMeanings() {
        DataType date = JdbcSqlServerMetadataRepository.mapDataType(
                row("BUSINESS_DATE", "date", "sys", "date", 3, 10, 0));
        DataType rowversion = JdbcSqlServerMetadataRepository.mapDataType(
                row("VERSION", "timestamp", "sys", "timestamp", 8, 0, 0));

        assertEquals("DATE_SQLSERVER", date.name().normalized());
        assertEquals("SQLSERVER_TIMESTAMP", rowversion.name().normalized());
    }

    @Test
    void mapsIdentityAndComputedColumnsWithoutIllegalDefaults() {
        var identityRow = row("ID", "int", "sys", "int", 4, 10, 0,
                false, "((1))", null, true);
        var computedRow = row("TOTAL", "decimal", "sys", "decimal", 17, 18, 2,
                true, null, "([QUANTITY]*[PRICE])", false);

        Column identity = JdbcSqlServerMetadataRepository.mapColumn(identityRow);
        Column computed = JdbcSqlServerMetadataRepository.mapColumn(computedRow);

        assertTrue(identity.identity());
        assertEquals("((1))", identity.defaultValue().expression());
        assertFalse(computed.defaultValue().isPresent());
        assertTrue(computed.generated());
        assertEquals("([QUANTITY]*[PRICE])", computed.generatedExpression());
    }

    @Test
    void assemblesPrimaryAndUniqueConstraintsInOrdinalOrder() {
        Table.Builder builder = baseTable();

        JdbcSqlServerMetadataRepository.mapKeys(builder, java.util.List.of(
                new JdbcSqlServerMetadataRepository.KeyConstraintRow(
                        "PK_SAMPLE", "PK", "ID", 1, "CLUSTERED", "PRIMARY"),
                new JdbcSqlServerMetadataRepository.KeyConstraintRow(
                        "UK_SAMPLE", "UQ", "CODE", 1, "NONCLUSTERED", "INDEX_FG")));

        Table table = builder.build();
        assertEquals("PK_SAMPLE", table.primaryKey().orElseThrow().name().value());
        assertEquals("CLUSTERED", table.primaryKey().orElseThrow().physicalOptions()
                .get("SQLSERVER_INDEX_ORGANIZATION"));
        assertEquals("PRIMARY", table.primaryKey().orElseThrow().physicalOptions().get("INDEX_TABLESPACE"));
        assertEquals("UK_SAMPLE", table.uniqueKeys().getFirst().name().value());
        assertEquals("NONCLUSTERED", table.uniqueKeys().getFirst().physicalOptions()
                .get("SQLSERVER_INDEX_ORGANIZATION"));
        assertEquals("INDEX_FG", table.uniqueKeys().getFirst().physicalOptions().get("INDEX_TABLESPACE"));
    }

    @Test
    void assemblesForeignKeyWithDeleteAndUpdateActions() {
        Table.Builder builder = baseTable();

        JdbcSqlServerMetadataRepository.mapForeignKeys(builder, java.util.List.of(
                new JdbcSqlServerMetadataRepository.ForeignKeyRow(
                        "FK_SAMPLE_PARENT", 1, "PARENT_ID", "dbo", "PARENTS", "ID",
                        "CASCADE", "SET_NULL")));

        var foreignKey = builder.build().foreignKeys().getFirst();
        assertEquals(ReferentialAction.CASCADE, foreignKey.onDelete());
        assertEquals(ReferentialAction.SET_NULL, foreignKey.onUpdate());
        assertEquals("dbo.PARENTS", foreignKey.referencedTable().toString());
    }

    @Test
    void assemblesFilteredIndexWithIncludeColumnsAndDirection() {
        Table.Builder builder = baseTable();

        JdbcSqlServerMetadataRepository.mapIndexes(builder, java.util.List.of(
                new JdbcSqlServerMetadataRepository.IndexRow(
                        2, "IX_SAMPLE_CODE", false, "NONCLUSTERED", 1, 1,
                        false, true, "CODE", "[CODE] IS NOT NULL", "PRIMARY"),
                new JdbcSqlServerMetadataRepository.IndexRow(
                        2, "IX_SAMPLE_CODE", false, "NONCLUSTERED", 2, 0,
                        true, false, "PARENT_ID", "[CODE] IS NOT NULL", "PRIMARY")));

        var index = builder.build().indexes().getFirst();
        assertEquals(SortDirection.DESC, index.columns().getFirst().direction());
        assertEquals("PARENT_ID", index.includeColumns().getFirst().value());
        assertEquals("[CODE] IS NOT NULL", index.predicate());
        assertEquals("NONCLUSTERED", index.physicalOptions().get("SQLSERVER_INDEX_ORGANIZATION"));
        assertEquals("PRIMARY", index.physicalOptions().get("INDEX_TABLESPACE"));
    }

    @Test
    void mapsUniformTableCompressionAndMarksMixedPartitionCompressionForReview() {
        var uniform = JdbcSqlServerMetadataRepository.sqlServerTablePhysicalOptions(
                "PRIMARY",
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.TablePartitionPhysicalRow(1, "PAGE"),
                        new JdbcSqlServerMetadataRepository.TablePartitionPhysicalRow(2, "PAGE")),
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.XmlPartitionPhysicalRow(1, "OFF"),
                        new JdbcSqlServerMetadataRepository.XmlPartitionPhysicalRow(2, "OFF")));
        var mixed = JdbcSqlServerMetadataRepository.sqlServerTablePhysicalOptions(
                "PRIMARY",
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.TablePartitionPhysicalRow(1, "PAGE"),
                        new JdbcSqlServerMetadataRepository.TablePartitionPhysicalRow(2, "ROW")),
                java.util.List.of());

        assertEquals("PRIMARY", uniform.get("TABLESPACE"));
        assertEquals("PAGE", uniform.get("SQLSERVER_TABLE_DATA_COMPRESSION"));
        assertEquals("OFF", uniform.get("SQLSERVER_TABLE_XML_COMPRESSION"));
        assertTrue(mixed.get("SQLSERVER_TABLE_DATA_COMPRESSION").startsWith("REVIEW:MIXED"));
    }

    @Test
    void catalogQueriesUseDocumentedSqlServerCatalogViews() {
        assertTrue(JdbcSqlServerMetadataRepository.TABLE_SQL.contains("sys.tables"));
        assertTrue(JdbcSqlServerMetadataRepository.COLUMNS_SQL.contains("sys.columns"));
        assertTrue(JdbcSqlServerMetadataRepository.COLUMNS_SQL.contains("sys.computed_columns"));
        assertTrue(JdbcSqlServerMetadataRepository.COLUMNS_SQL.contains("sys.identity_columns"));
        assertTrue(JdbcSqlServerMetadataRepository.KEY_CONSTRAINTS_SQL.contains("sys.key_constraints"));
        assertTrue(JdbcSqlServerMetadataRepository.KEY_CONSTRAINTS_SQL.contains("I.type_desc"));
        assertTrue(JdbcSqlServerMetadataRepository.KEY_CONSTRAINTS_SQL.contains("sys.data_spaces"));
        assertTrue(JdbcSqlServerMetadataRepository.FOREIGN_KEYS_SQL.contains("sys.foreign_key_columns"));
        assertTrue(JdbcSqlServerMetadataRepository.CHECKS_SQL.contains("sys.check_constraints"));
        assertTrue(JdbcSqlServerMetadataRepository.INDEXES_SQL.contains("sys.index_columns"));
        assertTrue(JdbcSqlServerMetadataRepository.INDEXES_SQL.contains("is_included_column"));
        assertTrue(JdbcSqlServerMetadataRepository.INDEXES_SQL.contains("I.is_primary_key = 0"));
        assertTrue(JdbcSqlServerMetadataRepository.INDEXES_SQL.contains("I.is_unique_constraint = 0"));
        assertTrue(JdbcSqlServerMetadataRepository.TABLE_SQL.contains("sys.extended_properties"));
        assertTrue(JdbcSqlServerMetadataRepository.TABLE_PHYSICAL_SQL.contains("sys.partitions"));
        assertTrue(JdbcSqlServerMetadataRepository.TABLE_PHYSICAL_SQL.contains("data_compression_desc"));
        assertTrue(JdbcSqlServerMetadataRepository.TABLE_XML_COMPRESSION_SUPPORT_SQL.contains("sys.all_columns"));
    }

    @Test
    void mapsPersistentSqlServerIndexCatalogFlagsToExistingPhysicalKeys() {
        var options = JdbcSqlServerMetadataRepository.sqlServerIndexPhysicalOptions(
                "NONCLUSTERED", "INDEX_FG", 80, true, false,
                false, true, true, java.util.Map.of());

        assertEquals("NONCLUSTERED", options.get("SQLSERVER_INDEX_ORGANIZATION"));
        assertEquals("INDEX_FG", options.get("INDEX_TABLESPACE"));
        assertEquals("80", options.get("SQLSERVER_INDEX_FILLFACTOR"));
        assertEquals("ON", options.get("SQLSERVER_INDEX_PAD_INDEX"));
        assertEquals("OFF", options.get("SQLSERVER_INDEX_IGNORE_DUP_KEY"));
        assertEquals("ON", options.get("SQLSERVER_INDEX_STATISTICS_NORECOMPUTE"));
        assertEquals("OFF", options.get("SQLSERVER_INDEX_ALLOW_ROW_LOCKS"));
        assertEquals("ON", options.get("SQLSERVER_INDEX_ALLOW_PAGE_LOCKS"));
    }

    @Test
    void aggregatesSqlServerIndexCompressionAndMarksMixedPartitionStateForReview() {
        var options = JdbcSqlServerMetadataRepository.sqlServerIndexExtraPhysicalOptions(
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.IndexPartitionPhysicalRow(2, 1, "PAGE"),
                        new JdbcSqlServerMetadataRepository.IndexPartitionPhysicalRow(2, 2, "ROW"),
                        new JdbcSqlServerMetadataRepository.IndexPartitionPhysicalRow(3, 1, "PAGE")),
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.IndexXmlPartitionPhysicalRow(3, 1, "ON")),
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.IndexBooleanPhysicalRow(3, true)),
                java.util.List.of(
                        new JdbcSqlServerMetadataRepository.IndexBooleanPhysicalRow(3, false)));

        assertTrue(options.get(2).get("SQLSERVER_INDEX_DATA_COMPRESSION").startsWith("REVIEW:MIXED"));
        assertEquals("PAGE", options.get(3).get("SQLSERVER_INDEX_DATA_COMPRESSION"));
        assertEquals("ON", options.get(3).get("SQLSERVER_INDEX_XML_COMPRESSION"));
        assertEquals("ON", options.get(3).get("SQLSERVER_INDEX_STATISTICS_INCREMENTAL"));
        assertEquals("OFF", options.get(3).get("SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY"));
    }

    private static Table.Builder baseTable() {
        return Table.builder("dbo", "SAMPLE")
                .addColumn(Column.required("ID", DataType.simple("INT")))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR", 20)))
                .addColumn(Column.nullable("PARENT_ID", DataType.simple("INT")));
    }

    private static JdbcSqlServerMetadataRepository.SqlServerColumnRow row(
            String name, String userType, String userTypeSchema, String systemType,
            int maxLength, int precision, int scale) {
        return row(name, userType, userTypeSchema, systemType, maxLength, precision, scale,
                true, null, null, false);
    }

    private static JdbcSqlServerMetadataRepository.SqlServerColumnRow row(
            String name, String userType, String userTypeSchema, String systemType,
            int maxLength, int precision, int scale, boolean nullable,
            String defaultDefinition, String computedDefinition, boolean identity) {
        return new JdbcSqlServerMetadataRepository.SqlServerColumnRow(
                1, name, userType, userTypeSchema, systemType, maxLength, precision, scale,
                nullable, defaultDefinition, null, identity, computedDefinition, false);
    }
}
