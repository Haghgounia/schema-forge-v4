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

/** Regression coverage for Word object-name isolation and inline section boundaries. */
class WordObjectNameIsolationRegressionTest {

    @Test
    void inlineSequenceSectionMustNotBeParsedAsColumnsAndSourceSequenceNameIsIgnored() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0, "Table Name", "نام فارسی جدول", "Schema", "Database Type", "هدف از طراحی جدول");
            setRow(metadata, 1, "SATNA_BANK_TO_BANK_TRANSFERS", "test", "RTG", "MCB", "test table");

            XWPFTable columns = document.createTable(6, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data Type", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check / Constraint", "IsDenormal");
            setRow(columns, 1,
                    "BANK_TO_BANK_TRANSFER_ID", "id", "IDENTITY NUMBER(8)", "PK",
                    "", "", "Y", "", "", "", "");
            setRow(columns, 2,
                    "MESSAGE_DIRECTION", "direction", "NUMBER(1)", "",
                    "U1", "", "Y", "", "", "", "");
            setRow(columns, 3,
                    "TRANSACTION_REFERENCE_NUMBER", "reference", "VARCHAR2(16)", "",
                    "U1", "", "Y", "", "", "", "");
            setRow(columns, 4,
                    "Sequence Name", "نام فارسی توالی", "Schema", "Database Type",
                    "طول", "هدف از طراحی توالی", "", "", "", "", "");
            setRow(columns, 5,
                    "SEQ_VENDOR_DEFINED_TRANSACTION_REFERENCE_NUMBER", "vendor sequence", "RTG", "MCB",
                    "6", "This source-provided sequence name and description must be ignored", "", "", "", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        DatabaseSchema schema = parse("inline-sequence.docx", documentBytes);
        Table table = schema.tables().getFirst();

        assertEquals(3, table.columns().size());
        assertEquals(1, table.uniqueKeys().size());
        assertEquals(
                "UK_SATNA_BANK_TO_BANK_TRANSFERS_MESSAGE_DIRECTION_TRANSACTION_REFERENCE_NUMBER",
                table.uniqueKeys().getFirst().name().normalized());
        assertEquals(1, schema.sequences().size());
        assertEquals("SEQ_SATNA_BANK_TO_BANK_TRANSFERS", schema.sequences().getFirst().qualifiedName().name().normalized());
    }

    @Test
    void sourceProvidedIndexTokenMustNeverBecomePhysicalIndexName() throws Exception {
        String sourceIndexName = "IX_VENDOR_" + "X".repeat(140);
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0, "Table Name", "نام فارسی جدول", "Schema", "Database Type", "هدف از طراحی جدول");
            setRow(metadata, 1, "PAYMENTS", "test", "RTG", "MCB", "test table");

            XWPFTable columns = document.createTable(3, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data Type", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check / Constraint", "IsDenormal");
            setRow(columns, 1,
                    "SOURCE_ID", "source", "NUMBER(8)", "",
                    "", sourceIndexName, "Y", "", "", "", "");
            setRow(columns, 2,
                    "TARGET_ID", "target", "NUMBER(8)", "",
                    "", sourceIndexName, "Y", "", "", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        Table table = parse("source-index-name.docx", documentBytes).tables().getFirst();

        assertEquals(1, table.indexes().size());
        assertEquals("IX_PAYMENTS_SOURCE_ID_TARGET_ID", table.indexes().getFirst().name().normalized());
    }

    @Test
    void foreignKeyNameMustBeDerivedFromChildTableAndColumns() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 5);
            setRow(metadata, 0, "Table Name", "نام فارسی جدول", "Schema", "Database Type", "هدف از طراحی جدول");
            setRow(metadata, 1, "PAYMENTS", "test", "RTG", "MCB", "test table");

            XWPFTable columns = document.createTable(2, 11);
            setRow(columns, 0,
                    "Column Name", "نام فارسی ستون", "Data Type", "Primary/Foreign Key",
                    "Unique", "Index", "Required", "Default", "Range", "Check / Constraint", "IsDenormal");
            setRow(columns, 1,
                    "PARENT_ID", "parent", "NUMBER(8)", "PARENTS/Y",
                    "", "", "Y", "", "", "", "");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        Table table = parse("foreign-key-name.docx", documentBytes).tables().getFirst();

        assertEquals(1, table.foreignKeys().size());
        assertEquals("FK_PAYMENTS_PARENT_ID", table.foreignKeys().getFirst().name().normalized());
    }

    private static DatabaseSchema parse(String fileName, byte[] bytes) {
        return new WordSpecificationParser().parse(new SpecificationSource(
                fileName, new ByteArrayInputStream(bytes)));
    }

    private static void setRow(XWPFTable table, int rowIndex, String... values) {
        for (int index = 0; index < values.length; index++) {
            table.getRow(rowIndex).getCell(index).setText(values[index]);
        }
    }
}
