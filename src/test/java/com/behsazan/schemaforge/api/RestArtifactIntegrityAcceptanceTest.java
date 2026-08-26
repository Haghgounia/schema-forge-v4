package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifest;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestArtifact;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R7.6A acceptance gate for REST ZIP/manifest/artifact integrity. */
class RestArtifactIntegrityAcceptanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void standardWordZipAndManifestAreBidirectionallyConsistent() throws Exception {
        SchemaForgeApiService service = service();
        Path source = TestSamplePaths.PROVINCES_V1_2;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        assertArchiveIntegrity(service.generateFromWord(file));
    }

    @Test
    void legacyWordZipAndManifestAreBidirectionallyConsistent() throws Exception {
        SchemaForgeApiService service = service();
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/msword",
                Files.readAllBytes(source));

        assertArchiveIntegrity(service.generateFromLegacyWord(file, "DPS"));
    }

    private void assertArchiveIntegrity(byte[] archiveBytes) throws Exception {
        Map<String, byte[]> entries = unzipStrict(archiveBytes);
        assertTrue(entries.containsKey("manifest.json"), "manifest.json must exist at package root");

        ArtifactManifest manifest = objectMapper.readValue(entries.get("manifest.json"), ArtifactManifest.class);
        assertEquals(ArtifactManifest.CONTRACT, manifest.manifestContract());

        List<ArtifactManifestArtifact> artifacts = manifest.artifacts();
        long generated = artifacts.stream().filter(a -> a.status() == ArtifactStatus.GENERATED).count();
        long skipped = artifacts.stream().filter(a -> a.status() == ArtifactStatus.SKIPPED).count();
        long failed = artifacts.stream().filter(a -> a.status() == ArtifactStatus.FAILED).count();

        assertEquals(generated, manifest.artifactOutcomes().generated());
        assertEquals(skipped, manifest.artifactOutcomes().skipped());
        assertEquals(failed, manifest.artifactOutcomes().failed());

        List<String> generatedPaths = artifacts.stream()
                .filter(a -> a.status() == ArtifactStatus.GENERATED)
                .map(ArtifactManifestArtifact::path)
                .toList();
        assertEquals(generatedPaths.size(), new LinkedHashSet<>(generatedPaths).size(),
                "manifest must not contain duplicate generated paths");
        assertFalse(generatedPaths.stream().anyMatch(path -> path == null || path.isBlank()),
                "generated artifacts must always have paths");

        Set<String> expectedEntries = new LinkedHashSet<>(generatedPaths);
        assertEquals(expectedEntries, entries.keySet(),
                "ZIP entries and manifest generated paths must match exactly (no orphan/missing artifacts)");

        for (ArtifactManifestArtifact artifact : artifacts) {
            if (artifact.status() != ArtifactStatus.GENERATED) {
                assertNull(artifact.path(), "non-generated artifact must not claim a packaged path");
                assertNull(artifact.integrity(), "non-generated artifact must not claim byte integrity");
                continue;
            }

            byte[] content = entries.get(artifact.path());
            assertNotNull(content, "generated artifact is missing from ZIP: " + artifact.path());
            if (artifact.type() == ArtifactType.MANIFEST) {
                assertNull(artifact.integrity(), "manifest self-entry intentionally has no self-checksum");
                continue;
            }

            assertNotNull(artifact.integrity(), "generated artifact must have integrity: " + artifact.path());
            assertEquals("SHA-256", artifact.integrity().algorithm());
            assertEquals(content.length, artifact.integrity().sizeBytes());
            assertEquals(sha256(content), artifact.integrity().sha256(),
                    "checksum mismatch for " + artifact.path());
        }
    }

    private static Map<String, byte[]> unzipStrict(byte[] content) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] prior = entries.putIfAbsent(entry.getName(), zip.readAllBytes());
                    if (prior != null) {
                        duplicates.add(entry.getName());
                    }
                }
                zip.closeEntry();
            }
        }
        assertTrue(duplicates.isEmpty(), "ZIP must not contain duplicate entries: " + duplicates);
        return entries;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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
}
