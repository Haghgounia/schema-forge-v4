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
                        column(6, "EXPLICIT_TIMESTAMP_PRECISION", "TIMESTAMP(12)", "", "CURRENT TIMESTAMP")
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

        PreparedSchema prepared = new SchemaPreparationService().prepare(parsed);
        String sql = new DdlGenerator(new OracleDialect())
                .generate(prepared.schema(), prepared.validationReport());

        assertTrue(sql.contains("SHAHABSTATUS NUMBER(1) DEFAULT 0"));
        assertTrue(sql.contains("CUSTSTATUS NUMBER(1) DEFAULT 1"));
        assertTrue(sql.contains("REFIDSH NUMBER(16) DEFAULT 0"));
        assertTrue(sql.contains("MAX_PRECISION_NUMBER NUMBER(38)"));
        assertTrue(sql.contains("LEGACY_TIMESTAMP_LENGTH TIMESTAMP DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(sql.contains("EXPLICIT_TIMESTAMP_PRECISION TIMESTAMP(9) DEFAULT CURRENT_TIMESTAMP"));
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

    private ParsedWordColumn column(
            int sequence,
            String name,
            String type,
            String length,
            String defaultValue) {
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
                "",
                "",
                DataTypeConfidence.NOT_PRESENT,
                "",
                "",
                "",
                defaultValue,
                "",
                List.of());
    }
}
