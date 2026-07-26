package com.behsazan.schemaforge.reporting;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
