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

/**
 * Regressions derived from real CIF design documents with variant headers and malformed datatypes.
 *
 * <p>The fixture deliberately excludes SPACE_FREE_NAME. It protects the parser from the
 * "Data RANGE" header variant and the real NUMBER)5) typo without coupling the test to
 * unrelated columns in the source document.</p>
 */
class WordVariantHeaderAndDatatypeRecoveryTest {

    @Test
    void shouldAcceptDataRangeHeaderAndRecoverReversedNumberParenthesis() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0,
                    "Table Name", "نام فارسی جدول", "Schema", "Database RANGE", "هدف از طراحی جدول");
            setRow(metadata, 1,
                    "HOUSE_SIZE_RANGES", "بازه متراژ محل سکونت", "CIF", "MCB", "parser regression");

            XWPFTable columns = document.createTable(5, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data RANGE", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check | Constraint", "IsDenormal");
            setRow(columns, 1,
                    "HOUSE_SIZE_RANGE_ID", "شناسه بازه", "IDENTITY NUMBER(1)", "PK",
                    "", "", "Y", "", "", "", "");
            setRow(columns, 2,
                    "HOUSE_SIZE_RANGE_CODE", "کد بازه", "VARCHAR2(10)", "",
                    "U1", "", "Y", "", "", "", "");
            setRow(columns, 3,
                    "HOUSE_SIZE_FROM", "متراژ از", "NUMBER)5)", "",
                    "", "", "", "", "", "", "");
            setRow(columns, 4,
                    "IS_ACTIVE", "وضعیت", "NUMBER(1)", "",
                    "", "", "Y", "1", "1:active | 0:inactive", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        DatabaseSchema schema = new WordSpecificationParser().parse(new SpecificationSource(
                "house-size-ranges-variant.docx", new ByteArrayInputStream(documentBytes)));
        Table table = schema.tables().getFirst();

        assertEquals("HOUSE_SIZE_RANGES", table.qualifiedName().name().normalized());
        assertEquals(4, table.columns().size());
        assertEquals("NUMBER", table.columns().get(2).dataType().name().normalized());
        assertEquals(5, table.columns().get(2).dataType().precision());
        assertEquals(1, table.uniqueKeys().size());
        assertTrue(table.columns().stream().noneMatch(column ->
                column.name().normalized().equals("SPACE_FREE_NAME")));
    }

    private static void setRow(XWPFTable table, int rowIndex, String... values) {
        for (int index = 0; index < values.length; index++) {
            table.getRow(rowIndex).getCell(index).setText(values[index]);
        }
    }
}
