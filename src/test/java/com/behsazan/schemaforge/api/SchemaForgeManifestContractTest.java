package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaForgeManifestContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wordManifestCoversExactArchiveAndIntegrity() throws Exception {
        Path source = TestSamplePaths.PROVINCES_V1_2;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service().generateFromWord(file));
        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));

        assertBaseContract(manifest, "STANDARD_WORD", source.getFileName().toString());
        assertEquals(1, manifest.path("models").size());
        assertEquals(entries.size(), manifest.path("artifactOutcomes").path("generated").asInt());
        assertManifestMatchesArchive(entries, manifest);
    }

    @Test
    void legacyWordManifestUsesLegacyOrigin() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/msword", Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service().generateFromLegacyWord(file, "DPS"));
        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));

        assertBaseContract(manifest, "LEGACY_WORD", source.getFileName().toString());
        assertEquals("DPS", manifest.path("models").get(0).path("schema").asText());
        assertManifestMatchesArchive(entries, manifest);
    }

    @Test
    void zipBatchManifestKeepsChildSourceModelsAndFinalCollisionPaths() throws Exception {
        byte[] word = Files.readAllBytes(TestSamplePaths.PROVINCES_V1_2);
        byte[] zip = inputZip(Map.of(
                "first/" + TestSamplePaths.PROVINCES_V1_2.getFileName(), word,
                "second/" + TestSamplePaths.PROVINCES_V1_2.getFileName(), word));
        MockMultipartFile file = new MockMultipartFile("file", "batch.zip", "application/zip", zip);

        Map<String, byte[]> entries = unzip(service().generateFromZip(file));
        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));

        assertBaseContract(manifest, "ZIP_BATCH", "batch.zip");
        assertEquals(2, manifest.path("models").size());
        List<String> modelSources = manifest.path("models").findValuesAsText("sourceName");
        assertTrue(modelSources.contains("first/" + TestSamplePaths.PROVINCES_V1_2.getFileName()));
        assertTrue(modelSources.contains("second/" + TestSamplePaths.PROVINCES_V1_2.getFileName()));
        assertTrue(manifest.path("artifacts").findValuesAsText("path").stream()
                .anyMatch(path -> path.contains("__sf_")));
        assertManifestMatchesArchive(entries, manifest);
    }

    @Test
    void eaUsesStandardManifestWithEnterpriseArchitectExtensionOnly() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/xml", Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service().generateFromEaXml(file));
        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));

        assertBaseContract(manifest, "ENTERPRISE_ARCHITECT", source.getFileName().toString());
        assertTrue(manifest.path("extensions").has("enterpriseArchitect"));
        assertFalse(manifest.has("tableCount"));
        assertFalse(manifest.has("mermaid"));
        assertFalse(manifest.has("tables"));
        assertManifestMatchesArchive(entries, manifest);
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

    private static void assertBaseContract(JsonNode manifest, String origin, String sourceName) {
        assertEquals("schemaforge-manifest/v1", manifest.path("manifestContract").asText());
        assertEquals("1", manifest.path("artifactContractVersion").asText());
        assertEquals(origin, manifest.path("source").path("origin").asText());
        assertEquals(sourceName, manifest.path("source").path("name").asText());
        assertTrue(manifest.path("generation").path("id").asText().length() > 10);
        assertTrue(manifest.path("generation").path("timestampToken").asText()
                .matches("\\d{8}_\\d{6}_\\d{3}"));
        assertTrue(manifest.path("generation").path("generatedAt").asText().contains("T"));
    }

    private static void assertManifestMatchesArchive(Map<String, byte[]> entries, JsonNode manifest) throws Exception {
        Map<String, JsonNode> generatedByPath = new LinkedHashMap<>();
        JsonNode self = null;
        for (JsonNode artifact : manifest.path("artifacts")) {
            if ("GENERATED".equals(artifact.path("status").asText())) {
                String path = artifact.path("path").asText();
                generatedByPath.put(path, artifact);
                if ("manifest.json".equals(path)) {
                    self = artifact;
                } else {
                    byte[] bytes = entries.get(path);
                    assertTrue(bytes != null, path);
                    assertEquals(bytes.length, artifact.path("integrity").path("sizeBytes").asLong(), path);
                    assertEquals(sha256(bytes), artifact.path("integrity").path("sha256").asText(), path);
                    assertEquals("SHA-256", artifact.path("integrity").path("algorithm").asText(), path);
                }
            } else {
                assertTrue(artifact.path("path").isNull());
                assertTrue(artifact.path("mediaType").isNull());
                assertTrue(artifact.path("integrity").isNull());
            }
        }
        assertEquals(entries.keySet(), generatedByPath.keySet());
        assertTrue(self != null);
        assertTrue(self.path("integrity").isNull());
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

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }
}
