package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordLegacyIndexParsingTest {

    @Test
    void shouldReadCompositeIndexTokenFromPrimaryForeignKeyColumn() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0, "Table Name", "نام فارسی جدول", "Schema", "Database Type", "هدف از طراحی جدول");
            setRow(metadata, 1, "PROVINCES", "استانها", "BIM", "MCB", "test table");

            XWPFTable columns = document.createTable(4, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data Type", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check / Constraint", "IsDenormal");
            setRow(columns, 1,
                    "PROVINCE_ID", "شناسه استان", "NUMBER(2)", "PK",
                    "", "", "Y", "", "", "", "");
            setRow(columns, 2,
                    "PROVINCE_ENGLISH_NAME", "نام انگلیسی استان", "VARCHAR2(50)", "I1",
                    "", "", "", "", "", "", "");
            setRow(columns, 3,
                    "CREATION_DATE", "تاریخ ایجاد", "NUMBER(8)", "I1",
                    "", "", "Y", "", "", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        DatabaseSchema schema = new WordSpecificationParser().parse(new SpecificationSource(
                "legacy-index.docx", new ByteArrayInputStream(documentBytes)));
        Table table = schema.tables().getFirst();

        assertEquals(1, table.indexes().size());
        Index index = table.indexes().getFirst();
        assertEquals("IX_PROVINCES_I1", index.name().normalized());
        assertEquals(2, index.columns().size());
        assertEquals("PROVINCE_ENGLISH_NAME", index.columns().get(0).column().normalized());
        assertEquals("CREATION_DATE", index.columns().get(1).column().normalized());
    }

    private static void setRow(XWPFTable table, int rowIndex, String... values) {
        for (int index = 0; index < values.length; index++) {
            table.getRow(rowIndex).getCell(index).setText(values[index]);
        }
    }
}
