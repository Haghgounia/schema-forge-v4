package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.artifact.manifest.ArtifactManifest;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactRequestStatusEscalationContractTest {

    @TempDir
    Path temp;

    @Test
    void requestLevelPartialSuccessMustEscalateArtifactLevelSuccess() throws Exception {
        ArtifactGenerationContext context = context();

        Path file = temp.resolve("reports/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "A", StandardCharsets.UTF_8);

        context.ledger().generated(
                context,
                ArtifactType.SUMMARY_REPORT,
                null,
                "a",
                "reports/a.txt",
                "text/plain",
                "Test");

        ArtifactManifest manifest = new ArtifactManifestWriter(new ObjectMapper()).write(
                temp,
                context,
                "sample",
                List.of(),
                Map.of(),
                ArtifactRequestStatus.PARTIAL_SUCCESS);

        assertEquals(
                ArtifactRequestStatus.PARTIAL_SUCCESS,
                manifest.requestStatus());
    }

    @Test
    void requestLevelFailedMustNotBeWrittenToCompletedManifest() throws Exception {
        ArtifactGenerationContext context = context();

        Path file = temp.resolve("reports/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "A", StandardCharsets.UTF_8);

        context.ledger().generated(
                context,
                ArtifactType.SUMMARY_REPORT,
                null,
                "a",
                "reports/a.txt",
                "text/plain",
                "Test");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactManifestWriter(new ObjectMapper()).write(
                        temp,
                        context,
                        "sample",
                        List.of(),
                        Map.of(),
                        ArtifactRequestStatus.FAILED));
    }

    private static ArtifactGenerationContext context() {
        return new ArtifactGenerationContext(
                "gen-1",
                "20260823_001500_123",
                OffsetDateTime.parse("2026-08-23T00:15:00.123-07:00"),
                ArtifactOrigin.ZIP_BATCH,
                "batch.zip",
                new ArtifactLedger());
    }
}
