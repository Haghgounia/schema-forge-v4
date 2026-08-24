package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifest;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactManifestWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesChecksumSizeSelfEntryAndSkippedOutcome() throws Exception {
        ArtifactGenerationContext context = context();
        Path ddl = temp.resolve("ddl/oracle/T.sql");
        Files.createDirectories(ddl.getParent());
        Files.writeString(ddl, "CREATE TABLE T (ID NUMBER);\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.DDL, DatabasePlatform.ORACLE,
                "T", "ddl/oracle/T.sql", "application/sql", "DdlGenerator");
        context.ledger().skipped(context, ArtifactType.CRUD, DatabasePlatform.ORACLE,
                "T", "OracleCrudGenerationService");

        ArtifactManifest manifest = new ArtifactManifestWriter(new ObjectMapper()).write(
                temp, context, "sample", List.of(), Map.of());

        assertEquals(ArtifactManifest.CONTRACT, manifest.manifestContract());
        assertEquals(2, manifest.artifactOutcomes().generated());
        assertEquals(1, manifest.artifactOutcomes().skipped());
        assertEquals(List.of(ArtifactType.CRUD, ArtifactType.DDL, ArtifactType.MANIFEST),
                manifest.artifacts().stream().map(entry -> entry.type()).toList());
        assertTrue(Files.isRegularFile(temp.resolve("manifest.json")));

        var ddlEntry = manifest.artifacts().stream()
                .filter(entry -> entry.type() == ArtifactType.DDL)
                .findFirst().orElseThrow();
        assertEquals("SHA-256", ddlEntry.integrity().algorithm());
        assertEquals(Files.size(ddl), ddlEntry.integrity().sizeBytes());
        assertTrue(ddlEntry.integrity().sha256().matches("[0-9a-f]{64}"));

        var skipped = manifest.artifacts().stream()
                .filter(entry -> entry.status() == ArtifactStatus.SKIPPED)
                .findFirst().orElseThrow();
        assertNull(skipped.path());
        assertNull(skipped.mediaType());
        assertNull(skipped.integrity());

        var self = manifest.artifacts().stream()
                .filter(entry -> entry.type() == ArtifactType.MANIFEST)
                .findFirst().orElseThrow();
        assertEquals("manifest.json", self.path());
        assertNull(self.integrity());
    }

    @Test
    void serializesStableContractAndCapturedGenerationTime() throws Exception {
        ArtifactGenerationContext context = context();
        Path file = temp.resolve("reports/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "A", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "a", "reports/a.txt", "text/plain", "Test");

        new ArtifactManifestWriter(new ObjectMapper()).write(temp, context, "sample", List.of(), Map.of());
        JsonNode json = new ObjectMapper().readTree(temp.resolve("manifest.json").toFile());

        assertEquals("schemaforge-manifest/v1", json.path("manifestContract").asText());
        assertEquals("1", json.path("artifactContractVersion").asText());
        assertEquals("gen-1", json.path("generation").path("id").asText());
        assertEquals("20260823_001500_123", json.path("generation").path("timestampToken").asText());
        assertEquals("2026-08-23T00:15:00.123-07:00",
                json.path("generation").path("generatedAt").asText());
    }

    @Test
    void rejectsDuplicateGeneratedPath() throws Exception {
        ArtifactGenerationContext context = context();
        Path file = temp.resolve("reports/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "A", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "a", "reports/a.txt", "text/plain", "Test1");
        context.ledger().generated(context, ArtifactType.ERROR_REPORT, null,
                "b", "reports/a.txt", "text/plain", "Test2");

        assertThrows(IllegalStateException.class, () ->
                new ArtifactManifestWriter(new ObjectMapper()).write(
                        temp, context, "sample", List.of(), Map.of()));
    }

    @Test
    void rejectsGeneratedDescriptorWhoseFileIsMissing() {
        ArtifactGenerationContext context = context();
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "missing", "reports/missing.txt", "text/plain", "Test");

        assertThrows(IllegalStateException.class, () ->
                new ArtifactManifestWriter(new ObjectMapper()).write(
                        temp, context, "sample", List.of(), Map.of()));
    }

    private static ArtifactGenerationContext context() {
        return new ArtifactGenerationContext(
                "gen-1",
                "20260823_001500_123",
                OffsetDateTime.parse("2026-08-23T00:15:00.123-07:00"),
                ArtifactOrigin.STANDARD_WORD,
                "sample.docx",
                new ArtifactLedger());
    }
}
