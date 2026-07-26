package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordContinuationForeignKeyTest {

    @Test
    void shouldReadHeaderlessContinuationTableAndPreserveForeignKeys() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0, "Table Name", "نام فارسی جدول", "Schema", "Database Type", "هدف از طراحی جدول");
            setRow(metadata, 1, "PROVINCES", "استانها", "BIM", "MCB", "test table");

            XWPFTable columns = document.createTable(2, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data Type", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check / Constraint", "IsDenormal");
            setRow(columns, 1,
                    "PROVINCE_ID", "شناسه استان", "NUMBER(2)", "PK",
                    "", "", "Y", "", "", "", "");

            XWPFTable continuation = document.createTable(3, 11);
            setRow(continuation, 0,
                    "LANGUAGE_ID", "زبان رسمی", "NUMBER(8)", "LANGUAGES/Y",
                    "", "", "", "", "", "", "");
            setRow(continuation, 1,
                    "COUNTRY_ID", "کشور", "NUMBER(8)", "COUNTRIES/Y",
                    "", "", "", "", "", "", "");
            setRow(continuation, 2,
                    "CALENDAR_ID", "تقویم", "NUMBER(8)", "TIM. CALENDARS/N",
                    "", "", "", "", "", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        DatabaseSchema schema = new WordSpecificationParser().parse(new SpecificationSource(
                "continuation.docx", new ByteArrayInputStream(documentBytes)));
        Table table = schema.tables().getFirst();

        assertEquals(4, table.columns().size());
        assertEquals(3, table.foreignKeys().size());
        assertTrue(table.columns().stream().anyMatch(column -> column.name().normalized().equals("LANGUAGE_ID")));
        assertTrue(table.columns().stream().anyMatch(column -> column.name().normalized().equals("COUNTRY_ID")));
        assertTrue(table.columns().stream().anyMatch(column -> column.name().normalized().equals("CALENDAR_ID")));
        assertEquals("LANGUAGES", table.foreignKeys().get(0).referencedTable().name().normalized());
        assertTrue(table.foreignKeys().get(0).physicalReference());
        assertTrue(!table.foreignKeys().get(0).schemaExplicit());
        assertTrue(table.foreignKeys().get(1).physicalReference());
        assertTrue(!table.foreignKeys().get(1).schemaExplicit());
        assertEquals("TIM", table.foreignKeys().get(2).referencedTable().schemaName().orElseThrow().normalized());
        assertTrue(!table.foreignKeys().get(2).physicalReference());
        assertTrue(table.foreignKeys().get(2).schemaExplicit());
        assertEquals("CALENDARS", table.foreignKeys().get(2).referencedTable().name().normalized());
    }

    private static void setRow(XWPFTable table, int rowIndex, String... values) {
        for (int index = 0; index < values.length; index++) {
            table.getRow(rowIndex).getCell(index).setText(values[index]);
        }
    }
}
