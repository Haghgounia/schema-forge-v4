package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R7.7B acceptance gate for run-all/batch fault isolation and deterministic accounting.
 */
class RunAllFailureIsolationAcceptanceTest {

    private static final String TIMESTAMP = "20260826_180000_000";

    @Test
    void failureSkipAndDuplicateDoNotStopLaterValidDocuments() throws Exception {
        byte[] upload = mixedBatch();
        Map<String, byte[]> output = run(upload);

        String summary = text(output, "reports/batch-generation-summary.csv");
        assertTrue(summary.contains("01-continent.docx\",\"SUCCESS"));
        assertTrue(summary.contains("02-broken.docx\",\"FAILED"));
        assertTrue(summary.contains("03-province.docx\",\"SUCCESS"));
        assertTrue(summary.contains("04-province-duplicate.docx\",\"SKIPPED"));
        assertTrue(summary.contains("DUPLICATE_SOURCE_CONTENT"));
        assertTrue(summary.contains("05-readme.txt\",\"SKIPPED\",\"0\",\"UNSUPPORTED_EXTENSION"));

        String errors = text(output, "reports/batch-generation-errors.log");
        assertTrue(errors.contains("02-broken.docx"));
        assertTrue(errors.contains("Column specification table was not found"));

        JsonNode batchInput = manifest(output).path("extensions").path("batchInput");
        assertEquals(5, batchInput.path("regularFileCount").asInt());
        assertEquals(4, batchInput.path("processableDocumentCount").asInt());
        assertEquals(2, batchInput.path("successCount").asInt());
        assertEquals(1, batchInput.path("failedCount").asInt());
        assertEquals(2, batchInput.path("skippedCount").asInt());

        assertEquals(2, ddlPaths(output, "ddl/oracle/", ".oracle.sql").size());
        assertEquals(2, ddlPaths(output, "ddl/postgresql/", ".postgresql.sql").size());
        assertEquals(2, ddlPaths(output, "ddl/db2zos/", ".db2zos.sql").size());
        assertEquals(2, ddlPaths(output, "ddl/sqlserver/", ".sqlserver.sql").size());
        assertEquals(2, ddlPaths(output, "ddl/mysql/", ".mysql.sql").size());
    }

    @Test
    void repeatedRunProducesDeterministicSummaryCountsAndExecutablePaths() throws Exception {
        byte[] upload = mixedBatch();
        Map<String, byte[]> first = run(upload);
        Map<String, byte[]> second = run(upload);

        assertEquals(
                text(first, "reports/batch-generation-summary.csv"),
                text(second, "reports/batch-generation-summary.csv"));
        assertEquals(
                manifest(first).path("extensions").path("batchInput"),
                manifest(second).path("extensions").path("batchInput"));

        for (String prefix : new String[] {
                "ddl/oracle/", "ddl/postgresql/", "ddl/db2zos/", "ddl/sqlserver/", "ddl/mysql/"}) {
            assertEquals(ddlPaths(first, prefix, ".sql"), ddlPaths(second, prefix, ".sql"));
        }
    }

    private static byte[] mixedBatch() throws Exception {
        byte[] continents = Files.readAllBytes(TestSamplePaths.CONTINENTS_V1_0);
        byte[] provinces = Files.readAllBytes(TestSamplePaths.PROVINCES_V1_2);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("01-continent.docx", continents);
        entries.put("02-broken.docx", documentWithoutColumnSpecificationTable());
        entries.put("03-province.docx", provinces);
        entries.put("04-province-duplicate.docx", provinces);
        entries.put("05-readme.txt", "unsupported batch input".getBytes(StandardCharsets.UTF_8));
        return inputZip(entries);
    }

    private static Map<String, byte[]> run(byte[] upload) throws Exception {
        Fixture fixture = fixture();
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "r77b-batch.zip", TIMESTAMP);
        return unzip(fixture.orchestrator().generate(
                new MockMultipartFile("file", "r77b-batch.zip", "application/zip", upload),
                "r77b-batch",
                context));
    }

    private static Fixture fixture() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        ObjectMapper objectMapper = new ObjectMapper();
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        GrantProperties grantProperties = GrantProperties.defaults();
        SchemaPreparationService preparation = new SchemaPreparationService(
                AuditProperties.defaults(), grantProperties, spellCheck, objectMapper);
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        ArtifactPackageBuilder packages = new ArtifactPackageBuilder();
        DiagramArtifactProducer diagrams = new DiagramArtifactProducer(naming);
        MigrationArtifactProducer migrations = new MigrationArtifactProducer(naming);
        ComparisonArtifactProducer comparisons = new ComparisonArtifactProducer(naming);
        CrudArtifactProducer crud = new CrudArtifactProducer(naming, resolver, grantProperties);
        DocumentGenerationOrchestrator documents = new DocumentGenerationOrchestrator(
                preparation, resolver, naming, diagrams, migrations, comparisons,
                crud, new OracleDdlSanityChecker());

        return new Fixture(new BatchGenerationOrchestrator(
                documents, diagrams, naming, packages, new ArtifactManifestWriter(objectMapper)));
    }

    private static byte[] documentWithoutColumnSpecificationTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var metadata = document.createTable(2, 3);
            metadata.getRow(0).getCell(0).setText("TABLE NAME");
            metadata.getRow(0).getCell(1).setText("SCHEMA");
            metadata.getRow(0).getCell(2).setText("TABLE DESCRIPTION");
            metadata.getRow(1).getCell(0).setText("INVALID_R77B");
            metadata.getRow(1).getCell(1).setText("BIM");
            metadata.getRow(1).getCell(2).setText("Column specification table intentionally missing.");
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] inputZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
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

    private static JsonNode manifest(Map<String, byte[]> output) throws Exception {
        return new ObjectMapper().readTree(output.get("manifest.json"));
    }

    private static Set<String> ddlPaths(Map<String, byte[]> output, String prefix, String suffix) {
        Set<String> paths = new TreeSet<>();
        output.keySet().stream()
                .filter(path -> path.startsWith(prefix) && path.endsWith(suffix))
                .forEach(paths::add);
        return paths;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }

    private record Fixture(BatchGenerationOrchestrator orchestrator) {
    }
}
