package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * End-to-end regression test for the legacy Word-to-Oracle DDL generation path.
 *
 * <p>The test ensures default normalization and temporal precision bounds are applied in the
 * real generation path, while exact numeric precision beyond Oracle's hard limit is rejected
 * instead of being silently clamped.</p>
 */
class LegacyOracleGenerationPipelineTest {

    @TempDir
    Path tempDirectory;

    @Test
    void appliesDefaultNormalizationAndRejectsUnsafeOracleNumericPrecisionInTheRealGenerationPath() throws Exception {
        Path document = tempDirectory.resolve("14030927_CustmSubD.sd.spc.tb.JTMSCUSTOMERS.doc");
        Files.write(document, new byte[] {0});

        WordTableParseResult extraction = new WordTableParseResult(
                document,
                document.getFileName().toString(),
                WordDocumentFormat.DOC,
                WordDocumentFormat.DOC,
                false,
                1L,
                1L,
                WordTableParseStatus.SUCCESS,
                new ParsedWordTable("TABLE", "Customer", "JTMSCUSTOMERS", "",
                        MetadataConfidence.NOT_PRESENT, "", "", "", ""),
                List.of(
                        column(1, "SHAHABSTATUS", "NUMBER", "1", "0 1- دائم 2- موقت"),
                        column(2, "CUSTSTATUS", "NUMBER", "1", "1 1- فعال 0- غیرفعال"),
                        column(3, "REFIDSH", "NUMBER", "16", "0 CTShahabInquiry"),
                        column(4, "MAX_PRECISION_NUMBER", "NUMBER", "38", ""),
                        column(5, "LEGACY_TIMESTAMP_LENGTH", "TIMESTAMP", "26", "CURRENT TIMESTAMP"),
                        column(6, "EXPLICIT_TIMESTAMP_PRECISION", "TIMESTAMP(12)", "", "CURRENT TIMESTAMP"),
                        column(7, "CHAR_LENGTH_FROM_PHYSICAL", "C", "", "", "VC", "VARCHAR", "30"),
                        column(8, "AMBIGUOUS_S_FROM_PHYSICAL_C", "S", "", "", "C", "CHAR", "12"),
                        column(9, "AMBIGUOUS_S_LOGICAL_LENGTH", "S", "50", "", "VC", "VARCHAR", ""),
                        column(10, "PHYSICAL_LENGTH_ONLY", "C", "", "", "", "", "70"),
                        column(11, "INLINE_LENGTH", "C", "", "", "", "", "",
                                List.of("desc", "INLINE_LENGTH", "C", "", "", "", "", "VC", "", "VarChar(1000)")),
                        column(12, "ADJACENT_LENGTH", "C", "", "", "", "", "",
                                List.of("desc", "ADJACENT_LENGTH", "C", "", "", "", "VC", "15", "")),
                        column(13, "SHIFTED_LENGTH", "C", "", "", "", "", "",
                                List.of("desc", "SHIFTED_LENGTH", "C", "", "150", "")),
                        column(14, "DISPLACED_TYPE", "S", "", "", "", "", "",
                                List.of("desc", "DISPLACED_TYPE", "S", "", "", "", "", "VARCHAR", "40", "")),
                        column(15, "MULTI_NUMERIC_ADJACENT_LENGTH", "C", "", "", "", "", "",
                                List.of("desc", "MULTI_NUMERIC_ADJACENT_LENGTH", "C", "", "1", "2", "VC", "60", "3")),
                        column(16, "DISPLACED_PHYSICAL_S", "S", "", "", "", "", "",
                                List.of("desc", "DISPLACED_PHYSICAL_S", "S", "", "", "", "", "S", "", ""))
                ),
                List.of(),
                null,
                null,
                Instant.parse("2026-08-05T00:00:00Z"));

        WordTableParser stub = new WordTableParser() {
            @Override
            public WordTableParseResult parse(Path ignored) {
                return extraction;
            }

            @Override
            public WordTableParseResult parse(Path ignoredRoot, Path ignoredDocument) {
                return extraction;
            }
        };

        DatabaseSchema parsed = new LegacyWordSpecificationParser(stub)
                .parse(tempDirectory, document, "TSTSHMA");
        var table = parsed.tables().getFirst();
        assertEquals("0", table.findColumn("SHAHABSTATUS").orElseThrow().defaultValue().expression());
        assertEquals("1", table.findColumn("CUSTSTATUS").orElseThrow().defaultValue().expression());
        assertEquals("0", table.findColumn("REFIDSH").orElseThrow().defaultValue().expression());
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_DEFAULT_NORMALIZED"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_TEMPORAL_LENGTH_IGNORED"));
        assertEquals(null, table.findColumn("LEGACY_TIMESTAMP_LENGTH").orElseThrow().dataType().precision());
        assertEquals(12, table.findColumn("EXPLICIT_TIMESTAMP_PRECISION").orElseThrow().dataType().precision());
        assertEquals(30, table.findColumn("CHAR_LENGTH_FROM_PHYSICAL").orElseThrow().dataType().length());
        assertEquals("VARCHAR", table.findColumn("CHAR_LENGTH_FROM_PHYSICAL").orElseThrow().dataType().name().normalized());
        assertEquals(12, table.findColumn("AMBIGUOUS_S_FROM_PHYSICAL_C").orElseThrow().dataType().length());
        assertEquals("CHAR", table.findColumn("AMBIGUOUS_S_FROM_PHYSICAL_C").orElseThrow().dataType().name().normalized());
        assertEquals(50, table.findColumn("AMBIGUOUS_S_LOGICAL_LENGTH").orElseThrow().dataType().length());
        assertEquals(70, table.findColumn("PHYSICAL_LENGTH_ONLY").orElseThrow().dataType().length());
        assertEquals(1000, table.findColumn("INLINE_LENGTH").orElseThrow().dataType().length());
        assertEquals(15, table.findColumn("ADJACENT_LENGTH").orElseThrow().dataType().length());
        assertEquals(150, table.findColumn("SHIFTED_LENGTH").orElseThrow().dataType().length());
        assertEquals("VARCHAR", table.findColumn("DISPLACED_TYPE").orElseThrow().dataType().name().normalized());
        assertEquals(40, table.findColumn("DISPLACED_TYPE").orElseThrow().dataType().length());
        assertEquals(60, table.findColumn("MULTI_NUMERIC_ADJACENT_LENGTH").orElseThrow().dataType().length());
        assertEquals("SMALLINT", table.findColumn("DISPLACED_PHYSICAL_S").orElseThrow().dataType().name().normalized());
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_PHYSICAL_FALLBACK"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_LOGICAL_FALLBACK"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_PHYSICAL_LENGTH_ONLY_FALLBACK"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_INLINE_DECLARATION_RECOVERY"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_ADJACENT_TYPE_RECOVERY"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_CHARACTER_LENGTH_SHIFTED_CELL_RECOVERY"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("LEGACY_DATATYPE_DISPLACED_CELL_RECOVERY"));
        assertTrue(parsed.metadata().get("recovery.warnings").contains("DATATYPE_PHYSICAL_FALLBACK"));

        PreparedSchema prepared = new SchemaPreparationService().prepare(parsed);
        String sql = new DdlGenerator(new OracleDialect())
                .generate(prepared.schema(), prepared.validationReport());

        assertTrue(sql.contains("SHAHABSTATUS NUMBER(1) DEFAULT 0"));
        assertTrue(sql.contains("CUSTSTATUS NUMBER(1) DEFAULT 1"));
        assertTrue(sql.contains("REFIDSH NUMBER(16) DEFAULT 0"));
        assertTrue(sql.contains("MAX_PRECISION_NUMBER NUMBER(38)"));
        assertTrue(sql.contains("LEGACY_TIMESTAMP_LENGTH TIMESTAMP DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(sql.contains("EXPLICIT_TIMESTAMP_PRECISION TIMESTAMP(9) DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(sql.contains("CHAR_LENGTH_FROM_PHYSICAL VARCHAR2(30 CHAR)"));
        assertTrue(sql.contains("AMBIGUOUS_S_FROM_PHYSICAL_C CHAR(12 CHAR)"));
        assertTrue(sql.contains("AMBIGUOUS_S_LOGICAL_LENGTH VARCHAR2(50 CHAR)"));
        assertTrue(sql.contains("PHYSICAL_LENGTH_ONLY VARCHAR2(70 CHAR)"));
        assertTrue(sql.contains("INLINE_LENGTH VARCHAR2(1000 CHAR)"));
        assertTrue(sql.contains("ADJACENT_LENGTH VARCHAR2(15 CHAR)"));
        assertTrue(sql.contains("SHIFTED_LENGTH VARCHAR2(150 CHAR)"));
        assertTrue(sql.contains("DISPLACED_TYPE VARCHAR2(40 CHAR)"));
        assertTrue(sql.contains("MULTI_NUMERIC_ADJACENT_LENGTH VARCHAR2(60 CHAR)"));
        assertTrue(sql.contains("DISPLACED_PHYSICAL_S NUMBER"));
        assertDoesNotThrow(() -> new OracleDdlSanityChecker().requireValid(sql, document.toString()));

        Column unsupportedNumber = new Column(
                Identifier.of("UNSUPPORTED_NUMBER"), DataType.numeric("NUMBER", 70, null),
                true, null, Description.empty(), false, 1);
        DatabaseSchema unsupportedSchema = DatabaseSchema.builder("TSTSHMA")
                .addTable(Table.builder("TSTSHMA", "UNSUPPORTED_NUMERIC")
                        .addColumn(unsupportedNumber)
                        .build())
                .build();
        assertTrue(new DatatypeCompatibilityAnalyzer()
                .analyze(unsupportedSchema, new OracleDialect()).blocking());
        assertThrows(IllegalArgumentException.class,
                () -> new OracleDialect().sqlType(unsupportedNumber));
    }

    @Test
    void doesNotGuessAmbiguousLogicalSWithoutPhysicalEvidence() throws Exception {
        Path document = tempDirectory.resolve("ambiguous-s.doc");
        Files.write(document, new byte[] {0});

        WordTableParseResult extraction = new WordTableParseResult(
                document,
                document.getFileName().toString(),
                WordDocumentFormat.DOC,
                WordDocumentFormat.DOC,
                false,
                1L,
                1L,
                WordTableParseStatus.SUCCESS,
                new ParsedWordTable("TABLE", "", "AMBIGUOUS_S_TEST", "",
                        MetadataConfidence.NOT_PRESENT, "", "", "", ""),
                List.of(column(1, "AMBIGUOUS_ONLY", "S", "", "", "", "", "",
                        List.of("desc", "AMBIGUOUS_ONLY", "S", "", "", "", "", "", "", ""))),
                List.of(),
                null,
                null,
                Instant.parse("2026-08-17T00:00:00Z"));

        WordTableParser stub = new WordTableParser() {
            @Override
            public WordTableParseResult parse(Path ignored) {
                return extraction;
            }

            @Override
            public WordTableParseResult parse(Path ignoredRoot, Path ignoredDocument) {
                return extraction;
            }
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LegacyWordSpecificationParser(stub)
                        .parse(tempDirectory, document, "TSTSHMA"));
        assertTrue(failure.getMessage().contains("No reliable SQL data type for legacy column AMBIGUOUS_ONLY"));
    }

    private ParsedWordColumn column(
            int sequence,
            String name,
            String type,
            String length,
            String defaultValue) {
        return column(sequence, name, type, length, defaultValue, "", "", "");
    }

    private ParsedWordColumn column(
            int sequence,
            String name,
            String type,
            String length,
            String defaultValue,
            String physicalTypeRaw,
            String physicalType,
            String physicalLength) {
        return column(sequence, name, type, length, defaultValue,
                physicalTypeRaw, physicalType, physicalLength, List.of());
    }

    private ParsedWordColumn column(
            int sequence,
            String name,
            String type,
            String length,
            String defaultValue,
            String physicalTypeRaw,
            String physicalType,
            String physicalLength,
            List<String> rawCells) {
        Integer parsedLength = length == null || length.isBlank() ? null : Integer.valueOf(length);
        return new ParsedWordColumn(
                sequence,
                0,
                sequence,
                name,
                name,
                "",
                MetadataConfidence.NOT_PRESENT,
                type,
                type,
                DataTypeConfidence.TRUSTED,
                length,
                length,
                parsedLength,
                parsedLength,
                null,
                false,
                "",
                List.of(),
                false,
                false,
                "",
                List.of(),
                Boolean.FALSE,
                physicalTypeRaw,
                physicalType,
                physicalType == null || physicalType.isBlank()
                        ? DataTypeConfidence.NOT_PRESENT
                        : DataTypeConfidence.TRUSTED,
                physicalLength,
                physicalLength,
                "",
                defaultValue,
                "",
                rawCells);
    }
}
