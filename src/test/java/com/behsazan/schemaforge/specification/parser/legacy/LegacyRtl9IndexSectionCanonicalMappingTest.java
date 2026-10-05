package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.enums.IndexType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9IndexSectionCanonicalMappingTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsRecoveredSectionTokensToCanonicalPrimaryKeyAndIndexes() throws Exception {
        Path source = tempDir.resolve("TashilatD.sd.spc.tb.CTFCNTRCT.docx");
        Files.write(source, new byte[] {1});

        ParsedWordTable table = new ParsedWordTable(
                "تعریف جدول", "سامانه تست", "CTFCNTRCT", "قراردادها",
                MetadataConfidence.TRUSTED, "EXPLICIT_ENTITY_HEADER", "قراردادها", "", "");

        List<ParsedWordColumn> columns = List.of(
                column(1, "CNTRCTID", List.of("PK:1"), List.of()),
                column(2, "INTCSTMRCD", List.of(), List.of("IX3:1")),
                column(3, "PRPSLTYPCD", List.of(), List.of("IX1:1", "IX2:1")),
                column(4, "CNTRCTORGUNITCD", List.of(), List.of("IX2:2"))
        );

        WordTableParseResult result = new WordTableParseResult(
                source, source.getFileName().toString(), WordDocumentFormat.DOCX, WordDocumentFormat.DOCX, false,
                1, 1, WordTableParseStatus.SUCCESS, table, columns, List.of(), "", "", Instant.now());
        WordTableParser stub = new WordTableParser() {
            @Override public WordTableParseResult parse(Path document) { return result; }
            @Override public WordTableParseResult parse(Path inputRoot, Path document) { return result; }
        };

        var schema = new LegacyWordSpecificationParser(stub).parse(tempDir, source, "TASSHMA");
        var canonical = schema.tables().getFirst();

        assertTrue(canonical.primaryKey().isPresent());
        assertEquals(List.of("CNTRCTID"), canonical.primaryKey().orElseThrow().columns().stream()
                .map(identifier -> identifier.normalized()).toList());
        assertEquals(3, canonical.indexes().size());
        List<List<String>> memberSets = canonical.indexes().stream()
                .map(index -> index.columns().stream()
                        .map(column -> column.column().normalized()).toList())
                .toList();
        assertTrue(memberSets.contains(List.of("PRPSLTYPCD")));
        assertTrue(memberSets.contains(List.of("PRPSLTYPCD", "CNTRCTORGUNITCD")));
        assertTrue(memberSets.contains(List.of("INTCSTMRCD")));
        assertTrue(canonical.indexes().stream().allMatch(index -> index.type() == IndexType.NORMAL));
    }

    private static ParsedWordColumn column(int sequence, String name, List<String> keys, List<String> indexes) {
        return new ParsedWordColumn(
                sequence, 0, sequence, name, name, "", MetadataConfidence.NOT_PRESENT,
                "INTEGER", "INTEGER", DataTypeConfidence.TRUSTED,
                "", "", null, null, null, false,
                String.join(" ", keys), keys,
                keys.stream().anyMatch(value -> value.toUpperCase().startsWith("PK")), false,
                String.join(" ", indexes), indexes,
                Boolean.TRUE,
                "", "", DataTypeConfidence.NOT_PRESENT,
                "", "", "", null, "", List.of()
        );
    }
}
