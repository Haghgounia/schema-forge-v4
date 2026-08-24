package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchGenerationOrchestratorTest {

    private static final String TIMESTAMP = "20260823_050000_000";

    @Test
    void batchGenerationPreservesFaultIsolationDiagnosticsManifestAndLedgerIdentity() throws Exception {
        Fixture fixture = fixture();
        byte[] valid = Files.readAllBytes(TestSamplePaths.PROVINCES_V1_2);
        byte[] invalid = documentWithoutColumnSpecificationTable();
        byte[] upload = inputZip(Map.of(
                "specifications/MCB.BIM.TBL.PROVINCES.V1.2.docx", valid,
                "specifications/notes.docx", invalid));
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "batch.zip", TIMESTAMP);

        Map<String, byte[]> output = unzip(fixture.orchestrator().generate(
                upload("batch.zip", upload), "batch", context));

        String summary = text(output, "reports/batch-generation-summary.csv");
        assertTrue(summary.contains("MCB.BIM.TBL.PROVINCES.V1.2.docx\",\"SUCCESS"));
        assertTrue(summary.contains("notes.docx\",\"FAILED"));
        assertTrue(text(output, "reports/batch-generation-errors.log")
                .contains("Column specification table was not found"));
        assertTrue(output.containsKey("manifest.json"));
        assertTrue(output.containsKey("diagram/mermaid/batch/schema-er.mmd"));
        assertTrue(output.containsKey("diagram/graphviz/batch/schema-overview.dot"));

        long manifests = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.MANIFEST)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.GENERATED)
                .count();
        assertEquals(1, manifests);
        assertTrue(context.ledger().snapshot().stream()
                .allMatch(descriptor -> context.generationId().equals(descriptor.generationId())));
        var batchDiagnostics = context.ledger().snapshot().stream()
                .filter(descriptor -> (descriptor.type() == ArtifactType.SUMMARY_REPORT
                        || descriptor.type() == ArtifactType.ERROR_REPORT)
                        && "batch-generation".equals(descriptor.logicalName()))
                .toList();
        assertEquals(2, batchDiagnostics.size());
        assertTrue(batchDiagnostics.stream()
                .map(ArtifactDescriptor::provenance)
                .allMatch(provenance -> "SchemaForgeApiService".equals(provenance.producer())));
    }

    @Test
    void duplicateDocumentsPreserveCollisionSafePathsAndSharedTimestamp() throws Exception {
        Fixture fixture = fixture();
        byte[] valid = Files.readAllBytes(TestSamplePaths.PROVINCES_V1_2);
        byte[] upload = inputZip(Map.of(
                "first/MCB.BIM.TBL.PROVINCES.V1.2.docx", valid,
                "second/MCB.BIM.TBL.PROVINCES.V1.2.docx", valid));
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "duplicates.zip", TIMESTAMP);

        Map<String, byte[]> output = unzip(fixture.orchestrator().generate(
                upload("duplicates.zip", upload), "duplicates", context));

        var oracleDdls = output.keySet().stream()
                .filter(name -> name.startsWith("ddl/oracle/") && name.endsWith(".oracle.sql"))
                .sorted()
                .toList();
        assertEquals(2, oracleDdls.size());
        assertTrue(oracleDdls.stream().anyMatch(name -> !name.contains("__sf_")));
        assertTrue(oracleDdls.stream().anyMatch(name -> name.contains("__sf_")));
        assertTrue(oracleDdls.stream().allMatch(name -> name.contains(TIMESTAMP)));
    }

    @Test
    void batchGenerationRejectsArchiveWithoutProcessableDocuments() throws Exception {
        Fixture fixture = fixture();
        byte[] upload = inputZip(Map.of(
                "readme.txt", "no specifications".getBytes(StandardCharsets.UTF_8),
                "~$temporary.docx", new byte[] {1, 2, 3}));
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "empty.zip", TIMESTAMP);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.orchestrator().generate(upload("empty.zip", upload), "empty", context));

        assertTrue(exception.getMessage().contains("processable DOCX"));
        assertFalse(context.ledger().snapshot().stream()
                .anyMatch(descriptor -> descriptor.status() == ArtifactStatus.GENERATED));
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

    private record Fixture(BatchGenerationOrchestrator orchestrator) {
    }
}
