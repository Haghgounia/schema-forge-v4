package com.behsazan.schemaforge.metadata.validation;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalMetadataComparatorTest {

    @Test
    void comparesExpectedAndActualOracleTablePhysicalOptionsWithoutPromotingActualToDesign() {
        Table expected = baseTable("APP", "CUSTOMERS")
                .physicalOption("TABLESPACE", "TS_APP")
                .physicalOption("ORACLE_PCTFREE", "10")
                .physicalOption("ORACLE_TABLE_LOGGING", "NOLOGGING")
                .physicalOption("ORACLE_TABLE_SEGMENT_CREATION", "DEFERRED")
                .build();
        Table actual = baseTable("APP", "CUSTOMERS")
                .physicalOption("TABLESPACE", "TS_APP")
                .physicalOption("ORACLE_PCTFREE", "20")
                .physicalOption("ORACLE_TABLE_LOGGING", "NOLOGGING")
                .physicalOption("ORACLE_TABLE_COMPRESSION", "NOCOMPRESS")
                .build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareTable(expected, actual, "ORACLE");

        assertEquals(PhysicalComparisonStatus.MATCH, status(rows, "TABLESPACE"));
        assertEquals(PhysicalComparisonStatus.MISMATCH, status(rows, "PCTFREE"));
        assertEquals(PhysicalComparisonStatus.MATCH, status(rows, "LOGGING"));
        assertEquals(PhysicalComparisonStatus.NOT_SPECIFIED, status(rows, "COMPRESSION"));
        assertEquals(PhysicalComparisonStatus.NOT_AVAILABLE, status(rows, "SEGMENT_CREATION"));
    }

    @Test
    void marksMixedActualPartitionStateForReview() {
        Table expected = baseTable("dbo", "CUSTOMERS")
                .physicalOption("SQLSERVER_TABLE_DATA_COMPRESSION", "PAGE")
                .build();
        Table actual = baseTable("dbo", "CUSTOMERS")
                .physicalOption("SQLSERVER_TABLE_DATA_COMPRESSION", "REVIEW:MIXED [NONE, PAGE]")
                .build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareTable(expected, actual, "SQLSERVER");

        assertEquals(PhysicalComparisonStatus.REVIEW, status(rows, "DATA_COMPRESSION"));
        assertEquals("MIXED [NONE, PAGE]", row(rows, "DATA_COMPRESSION").actualValue());
    }

    @Test
    void comparesOrdinarySqlServerIndexPhysicalStateByIndexName() {
        Index expectedIndex = index("IX_CUSTOMER_CODE", Map.of(
                "SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED",
                "INDEX_TABLESPACE", "IDX_FG",
                "SQLSERVER_INDEX_FILLFACTOR", "80"));
        Index actualIndex = index("IX_CUSTOMER_CODE", Map.of(
                "SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED",
                "INDEX_TABLESPACE", "IDX_FG",
                "SQLSERVER_INDEX_FILLFACTOR", "70",
                "SQLSERVER_INDEX_PAD_INDEX", "ON"));

        Table expected = baseTable("dbo", "CUSTOMERS").addIndex(expectedIndex).build();
        Table actual = baseTable("dbo", "CUSTOMERS").addIndex(actualIndex).build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareIndexes(expected, actual, "SQLSERVER");

        assertEquals(PhysicalComparisonStatus.MATCH, status(rows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "ORGANIZATION"));
        assertEquals(PhysicalComparisonStatus.MATCH, status(rows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "FILEGROUP_OR_DATA_SPACE"));
        assertEquals(PhysicalComparisonStatus.MISMATCH, status(rows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "FILLFACTOR"));
        assertEquals(PhysicalComparisonStatus.NOT_SPECIFIED, status(rows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "PAD_INDEX"));

        Table expectedDefaultFill = baseTable("dbo", "CUSTOMERS")
                .addIndex(index("IX_DEFAULT_FILL", Map.of("SQLSERVER_INDEX_FILLFACTOR", "100"))).build();
        Table actualDefaultFill = baseTable("dbo", "CUSTOMERS")
                .addIndex(index("IX_DEFAULT_FILL", Map.of("SQLSERVER_INDEX_FILLFACTOR", "0"))).build();
        var defaultFillRows = new PhysicalMetadataComparator()
                .compareIndexes(expectedDefaultFill, actualDefaultFill, "SQLSERVER");
        assertEquals(PhysicalComparisonStatus.MATCH,
                status(defaultFillRows, "INDEX", "IX_CUSTOMERS_ID <-> IX_DEFAULT_FILL", "FILLFACTOR"));
    }

    @Test
    void comparesPrimaryAndUniqueBackingIndexesWithoutMixingLogicalConstraintComparison() {
        PrimaryKey expectedPk = new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("ID")), false, false,
                Map.of("ORACLE_INDEX_PCTFREE", "10"));
        PrimaryKey actualPk = new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("ID")), false, false,
                Map.of("ORACLE_INDEX_PCTFREE", "20"));
        UniqueKey expectedUk = new UniqueKey(Identifier.of("UK_CUSTOMERS_ID"), List.of(Identifier.of("ID")), false, false,
                Map.of("INDEX_TABLESPACE", "TS_IDX"));
        UniqueKey actualUk = new UniqueKey(Identifier.of("UK_DB_DIFFERENT_NAME"), List.of(Identifier.of("ID")), false, false,
                Map.of("INDEX_TABLESPACE", "TS_IDX"));

        Table expected = baseTable("APP", "CUSTOMERS").primaryKey(expectedPk).addUniqueKey(expectedUk).build();
        Table actual = baseTable("APP", "CUSTOMERS").primaryKey(actualPk).addUniqueKey(actualUk).build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareIndexes(expected, actual, "ORACLE");

        assertEquals(PhysicalComparisonStatus.MISMATCH, status(rows, "PRIMARY_KEY", "PK_CUSTOMERS", "PCTFREE"));
        PhysicalComparisonRow uniqueTablespace = rows.stream()
                .filter(row -> row.scope().equals("UNIQUE_KEY") && row.property().equals("TABLESPACE"))
                .findFirst().orElseThrow();
        assertEquals(PhysicalComparisonStatus.MATCH, uniqueTablespace.status());
        assertEquals("UK_CUSTOMERS_ID <-> UK_DB_DIFFERENT_NAME", uniqueTablespace.objectName());
        assertTrue(uniqueTablespace.note().contains("matched structurally"));
    }

    @Test
    void marksMixedIndexPartitionCompressionAsReview() {
        Table expected = baseTable("dbo", "CUSTOMERS")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of("SQLSERVER_INDEX_DATA_COMPRESSION", "PAGE")))
                .build();
        Table actual = baseTable("dbo", "CUSTOMERS")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of("SQLSERVER_INDEX_DATA_COMPRESSION", "REVIEW:MIXED [PAGE, ROW]")))
                .build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareIndexes(expected, actual, "SQLSERVER");

        PhysicalComparisonRow row = rows.stream()
                .filter(item -> item.scope().equals("INDEX") && item.property().equals("DATA_COMPRESSION"))
                .findFirst().orElseThrow();
        assertEquals(PhysicalComparisonStatus.REVIEW, row.status());
        assertEquals("MIXED [PAGE, ROW]", row.actualValue());
    }


    @Test
    void comparesMySqlTableAndIndexPhysicalState() {
        Table expected = baseTable("APP", "CUSTOMERS")
                .physicalOption("MYSQL_ENGINE", "InnoDB")
                .physicalOption("MYSQL_COLLATION", "utf8mb4_0900_ai_ci")
                .physicalOption("MYSQL_ROW_FORMAT", "DYNAMIC")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of("MYSQL_INDEX_TYPE", "BTREE")))
                .build();
        Table actual = baseTable("APP", "CUSTOMERS")
                .physicalOption("MYSQL_ENGINE", "InnoDB")
                .physicalOption("MYSQL_COLLATION", "utf8mb4_0900_ai_ci")
                .physicalOption("MYSQL_ROW_FORMAT", "COMPACT")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of("MYSQL_INDEX_TYPE", "BTREE")))
                .build();

        var tableRows = new PhysicalMetadataComparator().compareTable(expected, actual, "MYSQL");
        assertEquals(PhysicalComparisonStatus.MATCH, status(tableRows, "ENGINE"));
        assertEquals(PhysicalComparisonStatus.MATCH, status(tableRows, "COLLATION"));
        assertEquals(PhysicalComparisonStatus.MISMATCH, status(tableRows, "ROW_FORMAT"));

        var indexRows = new PhysicalMetadataComparator().compareIndexes(expected, actual, "MYSQL");
        assertEquals(PhysicalComparisonStatus.MATCH,
                status(indexRows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "ACCESS_METHOD"));
    }

    @Test
    void comparesDb2LuwTableAndIndexPhysicalStateIndependentlyFromDb2Zos() {
        Table expected = baseTable("APP", "CUSTOMERS")
                .physicalOption("TABLESPACE", "TS_DATA")
                .physicalOption("DB2_LUW_INDEX_TABLESPACE", "TS_INDEX")
                .physicalOption("DB2_LUW_LONG_TABLESPACE", "TS_LONG")
                .physicalOption("DB2_LUW_TABLE_PCTFREE", "5")
                .physicalOption("DB2_LUW_APPEND", "OFF")
                .physicalOption("DB2_LUW_TABLE_ORGANIZATION", "ROW")
                .physicalOption("DB2_LUW_ROW_COMPRESSION", "ADAPTIVE")
                .physicalOption("DB2_LUW_VALUE_COMPRESSION", "YES")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of(
                        "INDEX_TABLESPACE", "TS_INDEX",
                        "DB2_LUW_INDEX_PCTFREE", "15",
                        "DB2_LUW_INDEX_MINPCTUSED", "40",
                        "DB2_LUW_INDEX_REVERSE_SCANS", "ALLOW",
                        "DB2_LUW_INDEX_COMPRESSION", "YES",
                        "DB2_LUW_INDEX_PAGE_SPLIT", "LOW")))
                .build();
        Table actual = baseTable("APP", "CUSTOMERS")
                .physicalOption("TABLESPACE", "TS_DATA")
                .physicalOption("DB2_LUW_INDEX_TABLESPACE", "TS_INDEX")
                .physicalOption("DB2_LUW_LONG_TABLESPACE", "TS_LONG")
                .physicalOption("DB2_LUW_TABLE_PCTFREE", "10")
                .physicalOption("DB2_LUW_APPEND", "OFF")
                .physicalOption("DB2_LUW_TABLE_ORGANIZATION", "ROW")
                .physicalOption("DB2_LUW_ROW_COMPRESSION", "ADAPTIVE")
                .physicalOption("DB2_LUW_VALUE_COMPRESSION", "YES")
                .addIndex(index("IX_CUSTOMER_CODE", Map.of(
                        "INDEX_TABLESPACE", "TS_INDEX",
                        "DB2_LUW_INDEX_PCTFREE", "15",
                        "DB2_LUW_INDEX_MINPCTUSED", "40",
                        "DB2_LUW_INDEX_REVERSE_SCANS", "ALLOW",
                        "DB2_LUW_INDEX_COMPRESSION", "NO",
                        "DB2_LUW_INDEX_PAGE_SPLIT", "LOW")))
                .build();

        var tableRows = new PhysicalMetadataComparator().compareTable(expected, actual, "DB2_LUW");
        assertEquals(PhysicalComparisonStatus.MATCH, status(tableRows, "TABLESPACE"));
        assertEquals(PhysicalComparisonStatus.MATCH, status(tableRows, "INDEX_TABLESPACE"));
        assertEquals(PhysicalComparisonStatus.MISMATCH, status(tableRows, "PCTFREE"));
        assertEquals(PhysicalComparisonStatus.MATCH, status(tableRows, "ROW_COMPRESSION"));

        var indexRows = new PhysicalMetadataComparator().compareIndexes(expected, actual, "db2luw");
        assertEquals(PhysicalComparisonStatus.MATCH,
                status(indexRows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "TABLESPACE"));
        assertEquals(PhysicalComparisonStatus.MATCH,
                status(indexRows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "PCTFREE"));
        assertEquals(PhysicalComparisonStatus.MISMATCH,
                status(indexRows, "INDEX", "IX_CUSTOMERS_ID <-> IX_CUSTOMER_CODE", "COMPRESSION"));
    }

    private static PhysicalComparisonStatus status(List<PhysicalComparisonRow> rows, String property) {
        return row(rows, property).status();
    }

    private static PhysicalComparisonRow row(List<PhysicalComparisonRow> rows, String property) {
        return rows.stream().filter(item -> item.property().equals(property)).findFirst().orElseThrow();
    }

    @Test
    void comparesPostgreSqlColumnStorageCompressionAndUnderstandsStorageDefault() {
        Column expectedColumn = new Column(Identifier.of("PAYLOAD"), DataType.simple("TEXT"), true, null,
                Description.empty(), false, 2, null,
                Map.of("POSTGRESQL_STORAGE", "DEFAULT", "POSTGRESQL_COMPRESSION", "LZ4"));
        Column actualColumn = new Column(Identifier.of("PAYLOAD"), DataType.simple("TEXT"), true, null,
                Description.empty(), false, 2, null,
                Map.of("POSTGRESQL_STORAGE", "EXTENDED",
                        "POSTGRESQL_STORAGE_TYPE_DEFAULT", "EXTENDED",
                        "POSTGRESQL_COMPRESSION", "PGLZ"));

        Table expected = baseTable("public", "documents").addColumn(expectedColumn).build();
        Table actual = baseTable("public", "documents").addColumn(actualColumn).build();

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareColumns(expected, actual, "POSTGRESQL");

        assertEquals(PhysicalComparisonStatus.MATCH, status(rows, "COLUMN", "PAYLOAD", "STORAGE"));
        assertEquals(PhysicalComparisonStatus.MISMATCH, status(rows, "COLUMN", "PAYLOAD", "COMPRESSION"));
        PhysicalComparisonRow storage = rows.stream()
                .filter(row -> row.objectName().equals("PAYLOAD") && row.property().equals("STORAGE"))
                .findFirst().orElseThrow();
        assertEquals("EXTENDED", storage.actualValue());
        assertTrue(storage.note().contains("type default"));
    }

    private static PhysicalComparisonStatus status(
            List<PhysicalComparisonRow> rows, String scope, String objectName, String property) {
        return rows.stream()
                .filter(row -> row.scope().equals(scope))
                .filter(row -> row.objectName().contains(objectName))
                .filter(row -> row.property().equals(property))
                .map(PhysicalComparisonRow::status)
                .findFirst().orElseThrow();
    }

    private static Index index(String name, Map<String, String> physicalOptions) {
        return new Index(Identifier.of(name),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty(), List.of(), null, physicalOptions);
    }

    private static Table.Builder baseTable(String schema, String name) {
        return Table.builder(schema, name)
                .addColumn(Column.required("ID", DataType.simple("INTEGER")));
    }
}
