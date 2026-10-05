package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9DependencyCanonicalMappingTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsThreePartAndSameSchemaTwoPartDependencyReferences() throws Exception {
        Path source = tempDir.resolve("TashilatD.sd.spc.tb.CTFAVRGREQ.docx");
        Files.write(source, new byte[] {1});

        ParsedWordTable table = new ParsedWordTable(
                "تعریف جدول", "سامانه تست", "CTFAVRGREQ", "",
                MetadataConfidence.NOT_PRESENT, "", "", "", "");

        List<ParsedWordColumn> columns = List.of(
                dependencyColumn(1, "INTCSTMRCD", "TASSHMA.CTFCSTMR.INTCSTMRID"),
                dependencyColumn(2, "FORMCODE", "CTFACTIVITY.FORMCODE")
        );
        WordTableParseResult result = result(source, table, columns);
        WordTableParser stub = stub(result);

        var schema = new LegacyWordSpecificationParser(stub).parse(tempDir, source, "TASSHMA");
        var canonical = schema.tables().getFirst();

        assertEquals(2, canonical.foreignKeys().size());
        var customerFk = canonical.foreignKeys().stream()
                .filter(fk -> fk.columns().getFirst().normalized().equals("INTCSTMRCD"))
                .findFirst().orElseThrow();
        assertEquals("TASSHMA", customerFk.referencedTable().schema().normalized());
        assertEquals("CTFCSTMR", customerFk.referencedTable().name().normalized());
        assertEquals("INTCSTMRID", customerFk.referencedColumns().getFirst().normalized());
        assertTrue(customerFk.schemaExplicit());

        var activityFk = canonical.foreignKeys().stream()
                .filter(fk -> fk.columns().getFirst().normalized().equals("FORMCODE"))
                .findFirst().orElseThrow();
        assertEquals("TASSHMA", activityFk.referencedTable().schema().normalized());
        assertEquals("CTFACTIVITY", activityFk.referencedTable().name().normalized());
        assertEquals("FORMCODE", activityFk.referencedColumns().getFirst().normalized());
        assertFalse(activityFk.schemaExplicit());
    }

    @Test
    void rejectsDependencySchemaTableWithoutReferencedColumn() throws Exception {
        Path source = tempDir.resolve("TashilatD.sd.spc.tb.CTFMEANREQRESULT.docx");
        Files.write(source, new byte[] {1});

        ParsedWordTable table = new ParsedWordTable(
                "تعریف جدول", "سامانه تست", "CTFMEANREQRESULT", "",
                MetadataConfidence.NOT_PRESENT, "", "", "", "");
        WordTableParseResult result = result(
                source, table, List.of(dependencyColumn(1, "REQCD", "TASSHMA.CTFMEANREQRESULT")));

        var schema = new LegacyWordSpecificationParser(stub(result)).parse(tempDir, source, "TASSHMA");
        var canonical = schema.tables().getFirst();

        assertTrue(canonical.foreignKeys().isEmpty());
        String warnings = schema.metadata().getOrDefault("recovery.warnings", "");
        assertTrue(warnings.contains("LEGACY_DEPENDENCY_REFERENCE_INVALID|column=REQCD"));
    }

    private static WordTableParseResult result(
            Path source,
            ParsedWordTable table,
            List<ParsedWordColumn> columns) {
        return new WordTableParseResult(
                source, source.getFileName().toString(),
                WordDocumentFormat.DOCX, WordDocumentFormat.DOCX, false,
                1, 1, WordTableParseStatus.SUCCESS, table, columns,
                List.of(), "", "", Instant.now());
    }

    private static WordTableParser stub(WordTableParseResult result) {
        return new WordTableParser() {
            @Override public WordTableParseResult parse(Path document) { return result; }
            @Override public WordTableParseResult parse(Path inputRoot, Path document) { return result; }
        };
    }

    private static ParsedWordColumn dependencyColumn(int sequence, String name, String reference) {
        return new ParsedWordColumn(
                sequence, 0, sequence, name, name, "", MetadataConfidence.NOT_PRESENT,
                "INTEGER", "INTEGER", DataTypeConfidence.TRUSTED,
                "", "", null, null, null, false,
                "FKD", List.of("FKD"), false, true,
                "", List.of(), Boolean.TRUE,
                "", "", DataTypeConfidence.NOT_PRESENT,
                "", "", reference, null, "", List.of()
        );
    }
}
