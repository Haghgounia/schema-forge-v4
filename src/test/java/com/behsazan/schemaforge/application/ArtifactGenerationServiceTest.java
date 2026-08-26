package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactLedger;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactGenerationServiceTest {

    @Test
    void standardWordPackagesManifestAndCleansWorkspace() throws Exception {
        DocumentGenerationOrchestrator orchestrator = mock(DocumentGenerationOrchestrator.class);
        ArtifactGenerationService service = service(orchestrator);
        ArtifactGenerationContext context = context(ArtifactOrigin.STANDARD_WORD, "sample.docx");
        PreparedSchema prepared = prepared("APP");
        AtomicReference<Path> work = new AtomicReference<>();

        when(orchestrator.generateStandardWord(any(Path.class), any(Path.class), same(context), isNull()))
                .thenAnswer(invocation -> {
                    Path input = invocation.getArgument(0);
                    Path output = invocation.getArgument(1);
                    work.set(input.getParent());
                    assertEquals("sample.docx", input.getFileName().toString());
                    assertEquals("word-content", Files.readString(input));
                    assertEquals(work.get().resolve("output"), output);
                    return prepared;
                });

        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "word-content".getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> entries = unzip(service.generateStandardWord(file, "sample.docx", context));

        assertEquals(List.of("manifest.json"), entries.keySet().stream().toList());
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertEquals("schemaforge-manifest/v1", manifest.path("manifestContract").asText());
        assertEquals("sample.docx", manifest.path("source").path("name").asText());
        assertEquals("APP", manifest.path("models").get(0).path("schema").asText());
        assertEquals("sample.docx", manifest.path("models").get(0).path("sourceName").asText());
        assertEquals(ArtifactType.MANIFEST, context.ledger().snapshot().get(0).type());
        assertFalse(Files.exists(work.get()));
    }

    @Test
    void legacyWordPassesSchemaAndPackagesManifest() throws Exception {
        DocumentGenerationOrchestrator orchestrator = mock(DocumentGenerationOrchestrator.class);
        ArtifactGenerationService service = service(orchestrator);
        ArtifactGenerationContext context = context(ArtifactOrigin.LEGACY_WORD, "legacy.doc");
        PreparedSchema prepared = prepared("DPS");

        when(orchestrator.generateLegacyWord(any(Path.class), any(Path.class), eq("DPS"), same(context), isNull()))
                .thenReturn(prepared);

        MockMultipartFile file = new MockMultipartFile(
                "file", "legacy.doc", "application/msword", new byte[] {1, 2, 3});

        Map<String, byte[]> entries = unzip(service.generateLegacyWord(file, "legacy.doc", "DPS", context));
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));

        assertEquals("legacy.doc", manifest.path("source").path("name").asText());
        assertEquals("DPS", manifest.path("models").get(0).path("schema").asText());
        verify(orchestrator).generateLegacyWord(any(Path.class), any(Path.class), eq("DPS"), same(context), isNull());
    }

    @Test
    void cleanupRunsWhenDocumentGenerationFails() throws Exception {
        DocumentGenerationOrchestrator orchestrator = mock(DocumentGenerationOrchestrator.class);
        ArtifactGenerationService service = service(orchestrator);
        ArtifactGenerationContext context = context(ArtifactOrigin.STANDARD_WORD, "failure.docx");
        AtomicReference<Path> work = new AtomicReference<>();

        when(orchestrator.generateStandardWord(any(Path.class), any(Path.class), same(context), isNull()))
                .thenAnswer(invocation -> {
                    Path input = invocation.getArgument(0);
                    work.set(input.getParent());
                    throw new IOException("generation failed");
                });

        MockMultipartFile file = new MockMultipartFile(
                "file", "failure.docx", "application/octet-stream", new byte[] {9});

        IOException error = assertThrows(IOException.class,
                () -> service.generateStandardWord(file, "failure.docx", context));

        assertEquals("generation failed", error.getMessage());
        assertFalse(Files.exists(work.get()));
    }

    private static ArtifactGenerationService service(DocumentGenerationOrchestrator orchestrator) {
        return new ArtifactGenerationService(
                orchestrator,
                new ArtifactPackageBuilder(),
                new ArtifactManifestWriter(new ObjectMapper()));
    }

    private static PreparedSchema prepared(String schemaName) {
        return new PreparedSchema(
                DatabaseSchema.builder(schemaName).build(),
                new ValidationReport(true, List.of()));
    }

    private static ArtifactGenerationContext context(ArtifactOrigin origin, String sourceName) {
        return new ArtifactGenerationContext(
                "gen-c810",
                "20260823_060000_000",
                OffsetDateTime.parse("2026-08-23T06:00:00-07:00"),
                origin,
                sourceName,
                new ArtifactLedger());
    }

    private static Map<String, byte[]> unzip(byte[] content) throws IOException {
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
}
