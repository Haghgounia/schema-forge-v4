package com.behsazan.schemaforge;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies fault isolation and diagnostics for ZIP-based REST generation.
 *
 * @since 4.1
 */
class SchemaForgeApiZipBatchTest {

    @Test
    void zipGenerationShouldContinueAfterInvalidDocumentAndReportFailure() throws Exception {
        byte[] valid = Files.readAllBytes(TestSamplePaths.PROVINCES_V1_2);
        byte[] invalid = documentWithoutColumnSpecificationTable();
        byte[] upload = inputZip(Map.of(
                "specifications/MCB.BIM.TBL.PROVINCES.V1.2.docx", valid,
                "specifications/notes.docx", invalid,
                "specifications/~$MCB.BIM.TBL.PROVINCES.V1.2.docx", invalid));

        Map<String, byte[]> output = unzip(service().generateFromZip(upload("batch.zip", upload)));

        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("oracle/") && name.endsWith(".oracle.sql")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("postgresql/") && name.endsWith(".postgresql.sql")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("db2zos/") && name.endsWith(".db2zos.sql")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("sqlserver/") && name.endsWith(".sqlserver.sql")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("json/") && name.endsWith(".json")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("mermaid/tables/") && name.endsWith(".mermaid.mmd")));
        assertTrue(output.keySet().stream().anyMatch(name -> name.startsWith("graphviz/tables/") && name.endsWith(".graphviz.dot")));
        assertTrue(output.containsKey("mermaid/batch/schema-er.mmd"));
        assertTrue(output.containsKey("mermaid/batch/schema-dependency.mmd"));
        assertTrue(output.containsKey("mermaid/batch/issues.csv"));
        assertTrue(output.containsKey("mermaid/batch/summary.txt"));
        assertTrue(output.containsKey("graphviz/batch/schema-dependency.dot"));
        assertTrue(output.containsKey("graphviz/batch/schema-clustered.dot"));
        assertTrue(output.containsKey("graphviz/batch/schema-compact.dot"));
        assertTrue(output.containsKey("graphviz/batch/schema-overview.dot"));
        assertTrue(output.containsKey("graphviz/batch/issues.csv"));
        assertTrue(output.containsKey("graphviz/batch/summary.txt"));
        assertTrue(text(output, "mermaid/batch/schema-er.mmd").startsWith("erDiagram"));
        assertTrue(text(output, "mermaid/batch/schema-dependency.mmd").startsWith("flowchart LR"));
        assertTrue(text(output, "graphviz/batch/schema-dependency.dot").startsWith("digraph SchemaForge_Dependency"));
        assertTrue(text(output, "graphviz/batch/schema-clustered.dot").startsWith("digraph SchemaForge_Clustered_Dependency"));
        assertTrue(text(output, "graphviz/batch/schema-compact.dot").startsWith("digraph SchemaForge_Clustered_Dependency"));
        assertTrue(text(output, "graphviz/batch/schema-overview.dot").startsWith("digraph SchemaForge_Clustered_Dependency"));

        String summary = text(output, "reports/batch-generation-summary.csv");
        assertTrue(summary.contains("MCB.BIM.TBL.PROVINCES.V1.2.docx\",\"SUCCESS"));
        assertTrue(summary.contains("notes.docx\",\"FAILED"));
        assertTrue(summary.contains("Column specification table was not found"));
        assertFalse(summary.contains("~$MCB.BIM.TBL.PROVINCES.V1.2.docx"));

        String errors = text(output, "reports/batch-generation-errors.log");
        assertTrue(errors.contains("Document : specifications/notes.docx"));
        assertTrue(errors.contains("Column specification table was not found"));
    }

    @Test
    void zipGenerationShouldRejectArchiveWithoutProcessableWordDocuments() throws Exception {
        byte[] upload = inputZip(Map.of(
                "readme.txt", "no specifications".getBytes(StandardCharsets.UTF_8),
                "~$temporary.docx", new byte[] {1, 2, 3}));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service().generateFromZip(upload("empty.zip", upload)));

        assertTrue(exception.getMessage().contains("processable DOCX"));
    }

    @Test
    void zipGenerationShouldReturnDiagnosticArchiveWhenEveryDocumentFails() throws Exception {
        byte[] invalid = documentWithoutColumnSpecificationTable();
        byte[] upload = inputZip(Map.of("notes.docx", invalid));

        Map<String, byte[]> output = unzip(service().generateFromZip(upload("invalid.zip", upload)));

        String summary = text(output, "reports/batch-generation-summary.csv");
        assertTrue(summary.contains("notes.docx\",\"FAILED"));
        assertTrue(text(output, "reports/batch-generation-errors.log")
                .contains("Column specification table was not found"));
        assertFalse(output.keySet().stream().anyMatch(name -> name.endsWith(".sql")));
        assertFalse(output.containsKey("mermaid/batch/schema-er.mmd"));
        assertFalse(output.containsKey("mermaid/batch/schema-dependency.mmd"));
        assertFalse(output.containsKey("graphviz/batch/schema-dependency.dot"));
        assertFalse(output.containsKey("graphviz/batch/schema-clustered.dot"));
        assertFalse(output.containsKey("graphviz/batch/schema-compact.dot"));
        assertFalse(output.containsKey("graphviz/batch/schema-overview.dot"));
    }

    private static SchemaForgeApiService service() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        return new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                new ObjectMapper(), resolver);
    }

    private static MockMultipartFile upload(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/zip", content);
    }

    private static byte[] documentWithoutColumnSpecificationTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var metadata = document.createTable(2, 3);
            metadata.getRow(0).getCell(0).setText("TABLE NAME");
            metadata.getRow(0).getCell(1).setText("SCHEMA");
            metadata.getRow(0).getCell(2).setText("TABLE DESCRIPTION");
            metadata.getRow(1).getCell(0).setText("INVALID_NOTES");
            metadata.getRow(1).getCell(1).setText("BIM");
            metadata.getRow(1).getCell(2).setText("Metadata exists, but the column specification table is missing.");
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] inputZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] content) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }
}
