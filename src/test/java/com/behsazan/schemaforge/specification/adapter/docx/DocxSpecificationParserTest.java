package com.behsazan.schemaforge.specification.adapter.docx;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.spi.SpecificationSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import static org.assertj.core.api.Assertions.assertThat;

class DocxSpecificationParserTest {
    private final DocxSpecificationParser parser = new DocxSpecificationParser();

    @Test
    void parsesCitiesDocumentIntoCanonicalSchema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/samples/docx/BIM.TBL.CITIES.V1.1.docx")) {
            assertThat(input).isNotNull();
            DatabaseSchema schema = parser.parse(new SpecificationSource("BIM.TBL.CITIES.V1.1.docx", input));

            assertThat(schema.name().value()).isEqualTo("BIM");
            assertThat(schema.tables()).hasSize(1);
            assertThat(schema.sequences()).hasSize(1);

            Table table = schema.tables().getFirst();
            assertThat(table.qualifiedName().name().value()).isEqualTo("CITIES");
            assertThat(table.columns()).hasSize(16);
            assertThat(table.primaryKey()).isPresent();
            assertThat(table.uniqueKeys()).hasSize(2);
            assertThat(table.foreignKeys()).hasSize(1);
            assertThat(table.findColumn("CITY_ID")).isPresent();
            var cityId = table.findColumn("CITY_ID").orElseThrow();
            assertThat(cityId.defaultValue()).isNotNull();
            assertThat(cityId.defaultValue().expression())
                    .isEqualTo("BIM.SEQ_CITIES.NEXTVAL");
        }
    }


    @Test
    void parsesPersianHeaderAliases() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 3);
            metadata.getRow(0).getCell(0).setText("نام جدول");
            metadata.getRow(0).getCell(1).setText("نام طرحواره");
            metadata.getRow(0).getCell(2).setText("شرح جدول");
            metadata.getRow(1).getCell(0).setText("CUSTOMERS");
            metadata.getRow(1).getCell(1).setText("BIM");
            metadata.getRow(1).getCell(2).setText("مشتریان");

            XWPFTable columns = document.createTable(2, 6);
            columns.getRow(0).getCell(0).setText("نام ستون");
            columns.getRow(0).getCell(1).setText("شرح ستون");
            columns.getRow(0).getCell(2).setText("نوع داده");
            columns.getRow(0).getCell(3).setText("کلید");
            columns.getRow(0).getCell(4).setText("اجباری");
            columns.getRow(0).getCell(5).setText("مقدار پیش فرض");
            columns.getRow(1).getCell(0).setText("CUSTOMER_ID");
            columns.getRow(1).getCell(1).setText("شناسه مشتری");
            columns.getRow(1).getCell(2).setText("NUMBER(18)");
            columns.getRow(1).getCell(3).setText("PK");
            columns.getRow(1).getCell(4).setText("Y");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        DatabaseSchema schema = parser.parse(new SpecificationSource(
                "persian-headers.docx",
                new ByteArrayInputStream(documentBytes)));

        Table table = schema.tables().getFirst();
        assertThat(schema.name().value()).isEqualTo("BIM");
        assertThat(table.qualifiedName().name().value()).isEqualTo("CUSTOMERS");
        assertThat(table.columns()).hasSize(1);
        assertThat(table.primaryKey()).isPresent();
        assertThat(table.findColumn("CUSTOMER_ID")).isPresent();
    }


    @Test
    void parsesOracleDataTypesWithFlexibleSpacing() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 2);
            metadata.getRow(0).getCell(0).setText("TABLE NAME");
            metadata.getRow(0).getCell(1).setText("SCHEMA NAME");
            metadata.getRow(1).getCell(0).setText("TYPE_SAMPLE");
            metadata.getRow(1).getCell(1).setText("BIM");

            String[][] definitions = {
                    {"AMOUNT", " number ( 18 , 2 ) "},
                    {"TITLE", "varchar2 ( 250 )"},
                    {"LOCAL_TITLE", "NVARCHAR2(120)"},
                    {"CREATED_AT", "timestamp ( 6 )"},
                    {"OFFSET_AT", "TIMESTAMP(3) WITH TIME ZONE"},
                    {"LOCAL_AT", "TIMESTAMP WITH LOCAL TIME ZONE"},
                    {"PAYLOAD", "BLOB"},
                    {"RAW_VALUE", "LONG RAW"},
                    {"DOCUMENT_DATA", "JSON"}
            };
            XWPFTable columns = document.createTable(definitions.length + 1, 2);
            columns.getRow(0).getCell(0).setText("COLUMN NAME");
            columns.getRow(0).getCell(1).setText("DATA TYPE");
            for (int index = 0; index < definitions.length; index++) {
                columns.getRow(index + 1).getCell(0).setText(definitions[index][0]);
                columns.getRow(index + 1).getCell(1).setText(definitions[index][1]);
            }

            document.write(output);
            documentBytes = output.toByteArray();
        }

        Table table = parser.parse(new SpecificationSource(
                "oracle-types.docx",
                new ByteArrayInputStream(documentBytes))).tables().getFirst();

        var amount = table.findColumn("AMOUNT").orElseThrow().dataType();
        assertThat(amount.name().value()).isEqualTo("NUMBER");
        assertThat(amount.precision()).isEqualTo(18);
        assertThat(amount.scale()).isEqualTo(2);

        assertThat(table.findColumn("TITLE").orElseThrow().dataType().length()).isEqualTo(250);
        assertThat(table.findColumn("LOCAL_TITLE").orElseThrow().dataType().length()).isEqualTo(120);
        assertThat(table.findColumn("CREATED_AT").orElseThrow().dataType().precision()).isEqualTo(6);
        assertThat(table.findColumn("OFFSET_AT").orElseThrow().dataType().name().value())
                .isEqualTo("TIMESTAMP_WITH_TIME_ZONE");
        assertThat(table.findColumn("OFFSET_AT").orElseThrow().dataType().precision()).isEqualTo(3);
        assertThat(table.findColumn("LOCAL_AT").orElseThrow().dataType().name().value())
                .isEqualTo("TIMESTAMP_WITH_LOCAL_TIME_ZONE");
        assertThat(table.findColumn("PAYLOAD").orElseThrow().dataType().name().value()).isEqualTo("BLOB");
        assertThat(table.findColumn("RAW_VALUE").orElseThrow().dataType().name().value()).isEqualTo("LONG_RAW");
        assertThat(table.findColumn("DOCUMENT_DATA").orElseThrow().dataType().name().value()).isEqualTo("JSON");
    }

    @Test
    void parsesVirtualColumnExpressionHeader() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable metadata = document.createTable(2, 2);
            metadata.getRow(0).getCell(0).setText("TABLE NAME");
            metadata.getRow(0).getCell(1).setText("SCHEMA NAME");
            metadata.getRow(1).getCell(0).setText("ACCOUNT_BALANCE");
            metadata.getRow(1).getCell(1).setText("BANK");

            XWPFTable columns = document.createTable(4, 3);
            columns.getRow(0).getCell(0).setText("COLUMN NAME");
            columns.getRow(0).getCell(1).setText("DATA TYPE");
            columns.getRow(0).getCell(2).setText("VIRTUAL COLUMN EXPRESSION");
            columns.getRow(1).getCell(0).setText("DEBIT_AMOUNT");
            columns.getRow(1).getCell(1).setText("NUMBER(18,2)");
            columns.getRow(2).getCell(0).setText("CREDIT_AMOUNT");
            columns.getRow(2).getCell(1).setText("NUMBER(18,2)");
            columns.getRow(3).getCell(0).setText("NET_AMOUNT");
            columns.getRow(3).getCell(1).setText("NUMBER(18,2)");
            columns.getRow(3).getCell(2).setText("CREDIT_AMOUNT - DEBIT_AMOUNT");

            document.write(output);
            documentBytes = output.toByteArray();
        }

        Table table = parser.parse(new SpecificationSource(
                "virtual-column.docx",
                new ByteArrayInputStream(documentBytes))).tables().getFirst();

        var virtualColumn = table.findColumn("NET_AMOUNT").orElseThrow();
        assertThat(virtualColumn.generated()).isTrue();
        assertThat(virtualColumn.generatedExpression()).isEqualTo("CREDIT_AMOUNT - DEBIT_AMOUNT");
    }

    @Test
    void supportsDocxCaseInsensitively() {
        assertThat(parser.supports("TABLE.DOCX")).isTrue();
        assertThat(parser.supports("TABLE.xlsx")).isFalse();
    }
}
