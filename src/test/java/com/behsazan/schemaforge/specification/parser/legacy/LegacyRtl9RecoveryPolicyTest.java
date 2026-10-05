package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.model.ColumnPhysicalOptionKeys;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9RecoveryPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsMissingDatatypeAndMissingCharacterLengthWithoutAbortingSnapshot() throws Exception {
        Path source = tempDir.resolve("TashilatD.sd.spc.tb.TESTRTL9.docx");
        Files.write(source, new byte[] {1});

        ParsedWordTable table = new ParsedWordTable(
                "تعریف جدول", "سامانه تست", "TESTRTL9", "جدول آزمایشی",
                MetadataConfidence.TRUSTED, "EXPLICIT_ENTITY_HEADER", "جدول آزمایشی", "", "");

        ParsedWordColumn missingType = column(1, "NO_TYPE", "", DataTypeConfidence.NOT_PRESENT, "");
        ParsedWordColumn missingLength = column(2, "USRCD", "CHAR", DataTypeConfidence.TRUSTED, "");

        WordTableParseResult result = new WordTableParseResult(
                source, source.getFileName().toString(), WordDocumentFormat.DOCX, WordDocumentFormat.DOCX, false,
                1, 1, WordTableParseStatus.SUCCESS, table, List.of(missingType, missingLength), List.of(),
                "", "", Instant.now());
        WordTableParser stub = new WordTableParser() {
            @Override public WordTableParseResult parse(Path document) { return result; }
            @Override public WordTableParseResult parse(Path inputRoot, Path document) { return result; }
        };

        var schema = new LegacyWordSpecificationParser(stub).parse(tempDir, source, "TASSHMA");
        assertEquals(2, schema.tables().getFirst().columns().size());

        var unresolvedType = schema.tables().getFirst().columns().get(0);
        assertEquals("MISSING_DATA_TYPE", unresolvedType.dataType().name().normalized());

        var unresolvedLength = schema.tables().getFirst().columns().get(1);
        assertEquals("CHAR", unresolvedLength.dataType().name().normalized());
        assertNull(unresolvedLength.dataType().length());
        assertEquals(com.behsazan.schemaforge.domain.valueobject.LengthSemantics.DEFAULT,
                unresolvedLength.dataType().lengthSemantics());
        assertEquals("true", unresolvedLength.physicalOptions().get(
                ColumnPhysicalOptionKeys.RECOVERY_UNRESOLVED_CHARACTER_LENGTH));

        String warnings = schema.metadata().getOrDefault("recovery.warnings", "");
        assertTrue(warnings.contains("LEGACY_DATATYPE_NOT_PRESENT|column=NO_TYPE"));
        assertTrue(warnings.contains("LEGACY_CHARACTER_LENGTH_NOT_PRESENT|column=USRCD"));

        var validation = new SpecificationValidator().validate(schema);
        assertFalse(validation.valid());
        assertTrue(validation.issues().stream().anyMatch(i -> "COLUMN_DATATYPE_UNRESOLVED".equals(i.code())));
        assertTrue(validation.issues().stream().anyMatch(i -> "COLUMN_CHARACTER_LENGTH_UNRESOLVED".equals(i.code())));
    }

    @Test
    void stripsTrailingRtl9RowHeaderArtifactFromPersianEntityTitle() {
        DocTableExtractor extractor = new DocTableExtractor();
        assertEquals("قراردادها", extractor.normalizePersianLabel("قراردادها ردیف"));
        assertEquals("", extractor.normalizePersianLabel("ردیف"));
    }

    private static ParsedWordColumn column(int sequence, String name, String type,
                                            DataTypeConfidence confidence, String length) {
        return new ParsedWordColumn(
                sequence, 0, sequence, name, name, "", MetadataConfidence.NOT_PRESENT,
                type, type, confidence, length, length, null, null, null, false,
                "", List.of(), false, false, "", List.of(), Boolean.TRUE,
                "", "", DataTypeConfidence.NOT_PRESENT, "", "", "", null, "", List.of());
    }
}
