package com.behsazan.schemaforge.reporting;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Schema Compare Excel Writer.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SchemaCompareExcelWriterTest {

    @Test
    void shouldWriteHistoricalTwentyTwoColumnComparisonLayout() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, null)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100)))
                .addColumn(Column.nullable("NEW_CODE", DataType.varchar("VARCHAR2", 20)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"),
                        List.of(Identifier.of("CUSTOMER_ID"))))
                .build();

        Table database = Table.builder("BIM", "CUSTOMERS")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 80)))
                .addColumn(Column.nullable("LEGACY_CODE", DataType.varchar("VARCHAR2", 30)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"),
                        List.of(Identifier.of("CUSTOMER_ID"))))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of("CUSTOMER_ID", 12L), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("CUSTOMERS");
            assertEquals(22, sheet.getRow(0).getLastCellNum());
            for (int index = 0; index < SchemaCompareExcelWriter.HEADERS.length; index++) {
                assertEquals(SchemaCompareExcelWriter.HEADERS[index],
                        sheet.getRow(0).getCell(index).getStringCellValue());
            }

            var customerId = findRow(sheet, "CUSTOMER_ID", 2, 12);
            assertEquals(12.0, customerId.getCell(0).getNumericCellValue());
            assertEquals("", customerId.getCell(21).getStringCellValue());

            var customerName = findRow(sheet, "CUSTOMER_NAME", 2, 12);
            assertTrue(customerName.getCell(21).getStringCellValue().contains("DATA_TYPE"));

            var newCode = findRow(sheet, "NEW_CODE", 2, 12);
            assertTrue(newCode.getCell(21).getStringCellValue().contains("NOT_EXISTS_IN_TABLE"));

            var legacyCode = findRow(sheet, "LEGACY_CODE", 12, 2);
            assertTrue(legacyCode.getCell(21).getStringCellValue().contains("NOT_EXISTS_IN_DOCUMENT"));
        }
    }

    @Test
    void shouldTreatInListValuesAsOrderIndependent() throws Exception {
        Table document = Table.builder("BIM", "FLAGS")
                .addColumn(Column.required("IS_ACTIVE", DataType.numeric("NUMBER", 1, null)))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_FLAGS_IS_ACTIVE"),
                        "IS_ACTIVE IN (0, 1)"))
                .build();

        Table database = Table.builder("BIM", "FLAGS")
                .addColumn(Column.required("IS_ACTIVE", DataType.numeric("NUMBER", 1, 0)))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_FLAGS_IS_ACTIVE"),
                        "IS_ACTIVE IN (1, 0)"))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("FLAGS"), "IS_ACTIVE", 2, 12);
            assertFalse(row.getCell(21).getStringCellValue().contains("CHECK CONSTRAINT"));
        }
    }

    @Test
    void shouldTreatPostgreSqlAnyArrayAsEquivalentToInList() throws Exception {
        Table document = Table.builder("BIM", "FLAGS")
                .addColumn(Column.required("IS_ACTIVE", DataType.numeric("NUMBER", 1, null)))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_FLAGS_IS_ACTIVE"),
                        "IS_ACTIVE IN (0, 1)"))
                .build();

        Table database = Table.builder("bim", "flags")
                .addColumn(Column.required("is_active", DataType.numeric("NUMERIC", 1, 0)))
                .addCheck(new CheckConstraint(
                        Identifier.of("ck_flags_is_active"),
                        "((is_active = ANY (ARRAY[(1)::numeric, (0)::numeric])))"))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.POSTGRESQL);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("FLAGS"), "IS_ACTIVE", 2, 12);
            assertFalse(row.getCell(21).getStringCellValue().contains("CHECK CONSTRAINT"));
        }
    }




    @Test
    void optimizedComparisonShouldTreatExactNumericAsEquivalentToNativeInteger() throws Exception {
        Table document = Table.builder("BIM", "FLAGS")
                .addColumn(Column.required("FLAG_ID", DataType.numeric("NUMBER", 2, null)))
                .build();

        Table database = Table.builder("BIM", "FLAGS")
                .addColumn(Column.required("FLAG_ID", DataType.simple("SMALLINT")))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), "PostgreSQL", new RawOptimizedDialect());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("FLAGS"), "FLAG_ID", 2, 12);
            assertFalse(row.getCell(21).getStringCellValue().contains("DATA_TYPE"));
        }
    }

    @Test
    void shouldKeepRowsInDocumentOrderAndAppendDatabaseOnlyRowsAtTheEnd() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, null), false, 1))
                .addColumn(column("NEW_CODE", DataType.varchar("VARCHAR2", 20), true, 2))
                .addColumn(column("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100), true, 3))
                .build();

        Table database = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .addColumn(column("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100), true, 2))
                .addColumn(column("LEGACY_FLAG", DataType.numeric("NUMBER", 1, 0), true, 3))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("CUSTOMERS");
            assertEquals("CUSTOMER_ID", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("NEW_CODE", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("CUSTOMER_NAME", sheet.getRow(3).getCell(2).getStringCellValue());
            assertEquals("LEGACY_FLAG", sheet.getRow(4).getCell(12).getStringCellValue());
        }
    }

    @Test
    void shouldApplyHistoricalRowBackgroundColors() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, null), false, 1))
                .addColumn(column("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 100), true, 2))
                .addColumn(column("NEW_CODE", DataType.varchar("VARCHAR2", 20), true, 3))
                .build();

        Table database = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .addColumn(column("CUSTOMER_NAME", DataType.varchar("VARCHAR2", 80), true, 2))
                .addColumn(column("LEGACY_CODE", DataType.varchar("VARCHAR2", 30), true, 3))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("CUSTOMERS");
            assertFill(sheet.getRow(0).getCell(0), IndexedColors.GREY_40_PERCENT);
            assertNoFill(findRow(sheet, "CUSTOMER_ID", 2, 12).getCell(0));
            assertFill(findRow(sheet, "CUSTOMER_NAME", 2, 12).getCell(0), IndexedColors.LIGHT_ORANGE);
            assertFill(findRow(sheet, "NEW_CODE", 2, 12).getCell(0), IndexedColors.BRIGHT_GREEN);
            assertFill(findRow(sheet, "LEGACY_CODE", 12, 2).getCell(0), IndexedColors.RED);
        }
    }


    @Test
    void shouldWriteBordersAndComparePrimaryKeyForeignKeyIndexAndUniqueIndex() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .persianName("مشتریان")
                .description("اطلاعات مشتریان")
                .addColumn(column("F1", DataType.numeric("NUMBER", 10, null), false, 1))
                .addColumn(column("F2", DataType.numeric("NUMBER", 10, null), true, 2))
                .addColumn(column("F3", DataType.varchar("VARCHAR2", 30), true, 3))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS_NEW"),
                        List.of(Identifier.of("F1"), Identifier.of("F2"))))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_CUSTOMERS_F2"),
                        List.of(Identifier.of("F2")),
                        QualifiedName.of("BIM", "REFERENCES_TABLE"),
                        List.of(Identifier.of("REFERENCE_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .addIndex(new Index(
                        Identifier.of("IX_CUSTOMERS_F2_F3"),
                        List.of(
                                new IndexColumn(Identifier.of("F2"), SortDirection.ASC),
                                new IndexColumn(Identifier.of("F3"), SortDirection.DESC)),
                        IndexType.NORMAL,
                        Description.empty()))
                .addIndex(new Index(
                        Identifier.of("UIX_CUSTOMERS_F3"),
                        List.of(new IndexColumn(Identifier.of("F3"), SortDirection.ASC)),
                        IndexType.UNIQUE,
                        Description.empty()))
                .addUniqueKey(new UniqueKey(Identifier.of("UK_CUSTOMERS_F2"),
                        List.of(Identifier.of("F2"))))
                .build();

        Table database = Table.builder("BIM", "CUSTOMERS")
                .description("مشتریان")
                .addColumn(column("F1", DataType.numeric("NUMBER", 10, 0), false, 1))
                .addColumn(column("F2", DataType.numeric("NUMBER", 10, 0), true, 2))
                .addColumn(column("F3", DataType.varchar("VARCHAR2", 30), true, 3))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS_OLD"),
                        List.of(Identifier.of("F1"))))
                .addIndex(new Index(
                        Identifier.of("IX_CUSTOMERS_OLD"),
                        List.of(new IndexColumn(Identifier.of("F1"), SortDirection.ASC)),
                        IndexType.NORMAL,
                        Description.empty()))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertEquals(9, workbook.getNumberOfSheets());
            var metadata = workbook.getSheet("TABLE_METADATA");
            assertEquals("BIM.CUSTOMERS", metadata.getRow(1).getCell(0).getStringCellValue());
            assertEquals("مشتریان", metadata.getRow(1).getCell(1).getStringCellValue());
            assertEquals("اطلاعات مشتریان", metadata.getRow(1).getCell(2).getStringCellValue());
            assertEquals("مشتریان", metadata.getRow(1).getCell(3).getStringCellValue());
            assertEquals("SAME", metadata.getRow(1).getCell(4).getStringCellValue());
            assertObjectStatus(workbook, "PRIMARY_KEY_COMPARE", "PK_CUSTOMERS_NEW", "MODIFY");
            assertObjectStatus(workbook, "FOREIGN_KEYS_COMPARE", "FK_CUSTOMERS_F2", "ADD");
            assertObjectStatus(workbook, "INDEXES_COMPARE", "IX_CUSTOMERS_F2_F3", "ADD");
            assertObjectStatus(workbook, "INDEXES_COMPARE", "IX_CUSTOMERS_OLD", "DROP");
            assertObjectStatus(workbook, "UNIQUE_INDEXES_COMPARE", "UK_CUSTOMERS_F2", "ADD");
            assertObjectStatus(workbook, "UNIQUE_INDEXES_COMPARE", "UIX_CUSTOMERS_F3", "ADD");

            var columns = workbook.getSheet("CUSTOMERS");
            assertEquals(BorderStyle.THIN, columns.getRow(0).getCell(0).getCellStyle().getBorderTop());
            assertEquals(BorderStyle.THIN, columns.getRow(1).getCell(0).getCellStyle().getBorderBottom());

            var indexes = workbook.getSheet("INDEXES_COMPARE");
            assertEquals(BorderStyle.THIN, indexes.getRow(0).getCell(0).getCellStyle().getBorderLeft());
            assertEquals(BorderStyle.THIN, indexes.getRow(1).getCell(0).getCellStyle().getBorderRight());
        }
    }


    @Test
    void shouldFallbackToDescriptionWhenPersianNameIsMissingForCommentComparison() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .description("Customer master")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .build();
        Table database = Table.builder("BIM", "CUSTOMERS")
                .description("Customer master")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var metadata = workbook.getSheet("TABLE_METADATA");
            assertEquals("Customer master", metadata.getRow(1).getCell(2).getStringCellValue());
            assertEquals("Customer master", metadata.getRow(1).getCell(3).getStringCellValue());
            assertEquals("SAME", metadata.getRow(1).getCell(4).getStringCellValue());
        }
    }


    @Test
    void shouldWriteTablePhysicalComparisonSheet() throws Exception {
        Table document = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .physicalOption("TABLESPACE", "TS_APP")
                .physicalOption("ORACLE_PCTFREE", "10")
                .physicalOption("ORACLE_TABLE_LOGGING", "NOLOGGING")
                .build();
        Table database = Table.builder("BIM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.numeric("NUMBER", 10, 0), false, 1))
                .physicalOption("TABLESPACE", "TS_APP")
                .physicalOption("ORACLE_PCTFREE", "20")
                .physicalOption("ORACLE_TABLE_LOGGING", "NOLOGGING")
                .physicalOption("ORACLE_TABLE_COMPRESSION", "NOCOMPRESS")
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("TABLE_PHYSICAL_COMPARE");
            assertTrue(sheet != null);
            assertEquals("MATCH", physicalStatus(sheet, "TABLESPACE"));
            assertEquals("MISMATCH", physicalStatus(sheet, "PCTFREE"));
            assertEquals("MATCH", physicalStatus(sheet, "LOGGING"));
            assertEquals("NOT_SPECIFIED", physicalStatus(sheet, "COMPRESSION"));
        }
    }

    @Test
    void shouldWriteIndexPhysicalComparisonSheetForIndexesAndBackingConstraints() throws Exception {
        Index documentIndex = new Index(Identifier.of("IX_CUSTOMERS_ID"),
                List.of(new IndexColumn(Identifier.of("CUSTOMER_ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty(), List.of(), null,
                Map.of("SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED",
                        "SQLSERVER_INDEX_FILLFACTOR", "80"));
        Index databaseIndex = new Index(Identifier.of("IX_CUSTOMERS_ID"),
                List.of(new IndexColumn(Identifier.of("CUSTOMER_ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty(), List.of(), null,
                Map.of("SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED",
                        "SQLSERVER_INDEX_FILLFACTOR", "70"));

        Table document = Table.builder("dbo", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.simple("INT"), false, 1))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"),
                        List.of(Identifier.of("CUSTOMER_ID")), false, false,
                        Map.of("INDEX_TABLESPACE", "PRIMARY")))
                .addIndex(documentIndex)
                .build();
        Table database = Table.builder("dbo", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.simple("INT"), false, 1))
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"),
                        List.of(Identifier.of("CUSTOMER_ID")), false, false,
                        Map.of("INDEX_TABLESPACE", "PRIMARY")))
                .addIndex(databaseIndex)
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.SQLSERVER);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("INDEX_PHYSICAL_COMPARE");
            assertTrue(sheet != null);
            assertEquals("SCOPE", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("OBJECT", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("PROPERTY", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("MISMATCH", indexPhysicalStatus(sheet, "INDEX", "IX_CUSTOMERS_ID", "FILLFACTOR"));
            assertEquals("MATCH", indexPhysicalStatus(sheet, "PRIMARY_KEY", "PK_CUSTOMERS", "FILEGROUP_OR_DATA_SPACE"));
        }
    }

    @Test
    void shouldWritePostgreSqlColumnPhysicalComparisonSheet() throws Exception {
        Column documentColumn = new Column(Identifier.of("PAYLOAD"), DataType.simple("TEXT"), true,
                new DefaultValue(null), Description.empty(), false, 1, null,
                Map.of("POSTGRESQL_STORAGE", "EXTENDED", "POSTGRESQL_COMPRESSION", "LZ4"));
        Column databaseColumn = new Column(Identifier.of("PAYLOAD"), DataType.simple("TEXT"), true,
                new DefaultValue(null), Description.empty(), false, 1, null,
                Map.of("POSTGRESQL_STORAGE", "EXTENDED",
                        "POSTGRESQL_STORAGE_TYPE_DEFAULT", "EXTENDED",
                        "POSTGRESQL_COMPRESSION", "PGLZ"));
        Table document = Table.builder("public", "documents").addColumn(documentColumn).build();
        Table database = Table.builder("public", "documents").addColumn(databaseColumn).build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.POSTGRESQL);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("COLUMN_PHYSICAL_COMPARE");
            assertTrue(sheet != null);
            assertEquals("COLUMN", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("MATCH", columnPhysicalStatus(sheet, "PAYLOAD", "STORAGE"));
            assertEquals("MISMATCH", columnPhysicalStatus(sheet, "PAYLOAD", "COMPRESSION"));
        }
    }

    @Test
    void shouldKeepMySqlComparisonLogicalOnlyUntilPhysicalContractIsDefined() throws Exception {
        Table document = Table.builder("CRM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.simple("BIGINT"), false, 1))
                .build();
        Table database = Table.builder("CRM", "CUSTOMERS")
                .addColumn(column("CUSTOMER_ID", DataType.simple("BIGINT"), false, 1))
                .physicalOption("MYSQL_ENGINE", "InnoDB")
                .physicalOption("MYSQL_COLLATION", "utf8mb4_0900_ai_ci")
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.MYSQL);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertTrue(workbook.getSheet("CUSTOMERS") != null);
            assertTrue(workbook.getSheet("TABLE_METADATA") != null);
            assertNull(workbook.getSheet("TABLE_PHYSICAL_COMPARE"));
            assertNull(workbook.getSheet("INDEX_PHYSICAL_COMPARE"));
            assertNull(workbook.getSheet("COLUMN_PHYSICAL_COMPARE"));
        }
    }

    private static String columnPhysicalStatus(
            org.apache.poi.ss.usermodel.Sheet sheet, String columnName, String property) {
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            var row = sheet.getRow(rowNumber);
            if (columnName.equals(row.getCell(0).getStringCellValue())
                    && property.equals(row.getCell(1).getStringCellValue())) {
                return row.getCell(4).getStringCellValue();
            }
        }
        throw new AssertionError("Column physical property not found: " + columnName + "/" + property);
    }

    private static String indexPhysicalStatus(
            org.apache.poi.ss.usermodel.Sheet sheet, String scope, String objectName, String property) {
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            var row = sheet.getRow(rowNumber);
            if (scope.equals(row.getCell(0).getStringCellValue())
                    && row.getCell(1).getStringCellValue().contains(objectName)
                    && property.equals(row.getCell(2).getStringCellValue())) {
                return row.getCell(5).getStringCellValue();
            }
        }
        throw new AssertionError("Index physical property not found: " + scope + "/" + objectName + "/" + property);
    }

    private static String physicalStatus(org.apache.poi.ss.usermodel.Sheet sheet, String property) {
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            var row = sheet.getRow(rowNumber);
            if (property.equals(row.getCell(1).getStringCellValue())) {
                return row.getCell(4).getStringCellValue();
            }
        }
        throw new AssertionError("Physical property not found: " + property);
    }

    private static final class RawOptimizedDialect implements Dialect {
        @Override
        public NumericMappingStrategy numericMappingStrategy() {
            return NumericMappingStrategy.OPTIMIZED;
        }

        @Override
        public String sqlType(Column column) {
            DataType type = column.dataType();
            String name = type.name().normalized();
            if (type.precision() == null) {
                return name;
            }
            return name + "(" + type.precision()
                    + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }

        @Override
        public String quote(Identifier identifier) {
            return identifier.value();
        }
    }

    private static org.apache.poi.ss.usermodel.Row findRow(
            org.apache.poi.ss.usermodel.Sheet sheet,
            String value,
            int preferredColumn,
            int fallbackColumn) {
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            var row = sheet.getRow(rowNumber);
            String preferred = row.getCell(preferredColumn).getStringCellValue();
            String fallback = row.getCell(fallbackColumn).getStringCellValue();
            if (value.equals(preferred) || value.equals(fallback)) return row;
        }
        throw new AssertionError("Row not found: " + value);
    }



    private static void assertObjectStatus(
            XSSFWorkbook workbook,
            String sheetName,
            String objectName,
            String expectedStatus) {
        var sheet = workbook.getSheet(sheetName);
        assertTrue(sheet != null, "Missing sheet: " + sheetName);
        for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
            var row = sheet.getRow(rowNumber);
            String documentName = row.getCell(1).getStringCellValue();
            String databaseName = row.getCell(3).getStringCellValue();
            if (objectName.equals(documentName) || objectName.equals(databaseName)) {
                assertEquals(expectedStatus, row.getCell(5).getStringCellValue());
                return;
            }
        }
        throw new AssertionError("Object not found in " + sheetName + ": " + objectName);
    }

    private static void assertNoFill(org.apache.poi.ss.usermodel.Cell cell) {
        org.junit.jupiter.api.Assertions.assertEquals(
                org.apache.poi.ss.usermodel.FillPatternType.NO_FILL,
                cell.getCellStyle().getFillPattern());
    }

    private static Column column(String name, DataType type, boolean nullable, int ordinalPosition) {
        return new Column(
                Identifier.of(name),
                type,
                nullable,
                new DefaultValue(null),
                Description.empty(),
                false,
                ordinalPosition,
                null);
    }

    private static void assertFill(org.apache.poi.ss.usermodel.Cell cell, IndexedColors expected) {
        assertEquals(expected.getIndex(), cell.getCellStyle().getFillForegroundColor());
    }
    @Test
    void shouldTreatOracleLogicalIdentityAsEquivalentToExpectedSequenceNextval() throws Exception {
        Column designId = new Column(
                Identifier.of("CUSTOMER_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue(null), Description.empty(), true, 1);
        Column actualId = new Column(
                Identifier.of("CUSTOMER_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue("BIM.SEQ_CUSTOMERS.NEXTVAL"),
                Description.empty(), false, 1);

        Table document = Table.builder("BIM", "CUSTOMERS")
                .addColumn(designId)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .build();
        Table database = Table.builder("BIM", "CUSTOMERS")
                .addColumn(actualId)
                .primaryKey(new PrimaryKey(Identifier.of("PK_CUSTOMERS"), List.of(Identifier.of("CUSTOMER_ID"))))
                .build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("CUSTOMERS"), "CUSTOMER_ID", 2, 12);
            String diff = row.getCell(21).getStringCellValue();
            assertFalse(diff.contains("DATA_DEFAULT"));
            assertFalse(diff.contains("IDENTITY_MODE"));
        }
    }

    @Test
    void shouldNotTreatArbitraryOracleSequenceAsEquivalentToLogicalIdentity() throws Exception {
        Column designId = new Column(
                Identifier.of("CUSTOMER_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue(null), Description.empty(), true, 1);
        Column actualId = new Column(
                Identifier.of("CUSTOMER_ID"),
                DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue("BIM.OTHER_SEQUENCE.NEXTVAL"),
                Description.empty(), false, 1);

        Table document = Table.builder("BIM", "CUSTOMERS").addColumn(designId).build();
        Table database = Table.builder("BIM", "CUSTOMERS").addColumn(actualId).build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("CUSTOMERS"), "CUSTOMER_ID", 2, 12);
            String diff = row.getCell(21).getStringCellValue();
            assertTrue(diff.contains("DATA_DEFAULT"));
            assertTrue(diff.contains("IDENTITY_MODE"));
        }
    }

    @Test
    void shouldTreatExplicitNullDefaultAsNoDefault() throws Exception {
        Column documentColumn = new Column(Identifier.of("ID"), DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue(null), Description.empty(), false, 1);
        Column databaseColumn = new Column(Identifier.of("ID"), DataType.numeric("NUMBER", 19, 0),
                false, new DefaultValue("NULL"), Description.empty(), false, 1);
        Table document = Table.builder("PDL", "EXTENSION").addColumn(documentColumn).build();
        Table database = Table.builder("PDL", "EXTENSION").addColumn(databaseColumn).build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("EXTENSION"), "ID", 2, 12);
            assertFalse(row.getCell(21).getStringCellValue().contains("DATA_DEFAULT"));
        }
    }

    @Test
    void shouldSuppressNormalIndexAlreadyCoveredByPrimaryKey() throws Exception {
        Column id = Column.required("ID", DataType.numeric("NUMBER", 19, 0));
        PrimaryKey pk = new PrimaryKey(Identifier.of("PK_PRODUCT"), List.of(Identifier.of("ID")));
        Index redundant = new Index(Identifier.of("IX_PRODUCT_ID"),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty());
        Table document = Table.builder("PDL", "PRODUCT")
                .addColumn(id).primaryKey(pk).addIndex(redundant).build();
        Table database = Table.builder("PDL", "PRODUCT")
                .addColumn(id).primaryKey(pk).build();

        byte[] content = new SchemaCompareExcelWriter().write(
                document, database, Map.of(), DatabasePlatform.ORACLE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var row = findRow(workbook.getSheet("PRODUCT"), "ID", 2, 12);
            assertFalse(row.getCell(21).getStringCellValue().contains("INDEX"));
            assertEquals(0, workbook.getSheet("INDEXES_COMPARE").getLastRowNum());
        }
    }

}
