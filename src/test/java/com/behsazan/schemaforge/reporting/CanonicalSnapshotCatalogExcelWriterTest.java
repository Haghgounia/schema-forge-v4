package com.behsazan.schemaforge.reporting;

import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalSnapshotCatalogExcelWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesTablesFieldsObjectsAndMetadataIntoOneWorkbook() throws Exception {
        CanonicalSchemaSnapshot snapshot = new CanonicalSchemaSnapshot(
                "1.0", "4", "test-parser", "2026-10-05T00:00:00Z",
                new CanonicalSchemaSnapshot.SourceSnapshot(
                        "sample.docx", "sample.docx", "abc123", 100, "2026-10-05T00:00:00Z", "legacy-word"),
                new CanonicalSchemaSnapshot.SchemaSnapshot(
                        "TASSHMA", "", Map.of("source.word.parseStatus", "SUCCESS"),
                        List.of(new CanonicalSchemaSnapshot.TableSnapshot(
                                "TASSHMA", "SAMPLE", "نمونه", "Sample table",
                                List.of(
                                        new CanonicalSchemaSnapshot.ColumnSnapshot(
                                                "ID",
                                                new CanonicalSchemaSnapshot.DataTypeSnapshot(
                                                        "DECIMAL", null, "DEFAULT", 18, 0),
                                                false, null, "شناسه", false, 1, null, Map.of()),
                                        new CanonicalSchemaSnapshot.ColumnSnapshot(
                                                "CODE",
                                                new CanonicalSchemaSnapshot.DataTypeSnapshot(
                                                        "CHAR", null, "DEFAULT", null, null),
                                                true, null, "کد", false, 2, null,
                                                Map.of("schemaforge.recovery.unresolvedCharacterLength", "true"))),
                                new CanonicalSchemaSnapshot.PrimaryKeySnapshot(
                                        "PK_SAMPLE", List.of("ID"), false, false, Map.of()),
                                List.of(), List.of(), List.of(), List.of(), Map.of())),
                        List.of()));

        Path input = tempDir.resolve("snapshots");
        Files.createDirectories(input);
        new CanonicalSnapshotJsonStore().writeSnapshot(input.resolve("sample.docx.schema.json"), snapshot);
        Path output = tempDir.resolve("catalog.xlsx");

        CanonicalSnapshotCatalogExcelWriter.Result result =
                new CanonicalSnapshotCatalogExcelWriter().write(input, output);

        assertEquals(1, result.snapshotFiles());
        assertEquals(1, result.tables());
        assertEquals(2, result.fields());
        assertEquals(0, result.unresolvedDatatypes());
        assertEquals(1, result.unresolvedCharacterLengths());
        assertTrue(Files.size(output) > 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertNotNull(workbook.getSheet("SUMMARY"));
            assertNotNull(workbook.getSheet("TABLES"));
            assertNotNull(workbook.getSheet("FIELDS"));
            assertNotNull(workbook.getSheet("OBJECTS"));
            assertNotNull(workbook.getSheet("METADATA"));
            assertEquals(2, workbook.getSheet("TABLES").getPhysicalNumberOfRows());
            assertEquals(3, workbook.getSheet("FIELDS").getPhysicalNumberOfRows());
            assertEquals("SAMPLE", workbook.getSheet("TABLES").getRow(1).getCell(10).getStringCellValue());
            assertEquals("CODE", workbook.getSheet("FIELDS").getRow(2).getCell(9).getStringCellValue());
        }
    }
}
