package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R7.6B acceptance gate for REST artifact identity, naming, provenance and collision policy. */
class ArtifactIdentityNamingAcceptanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void standardWordManifestCarriesConsistentArtifactIdentityAndNaming() throws Exception {
        Path source = TestSamplePaths.PROVINCES_V1_2;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        assertIdentityContract(service().generateFromWord(file));
    }

    @Test
    void legacyWordManifestCarriesConsistentArtifactIdentityAndNaming() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/msword",
                Files.readAllBytes(source));

        assertIdentityContract(service().generateFromLegacyWord(file, "DPS"));
    }

    @Test
    void collisionPolicyIsDeterministicAndFlywayNamesAreNeverSilentlyRenamed() {
        CollisionSafeArtifactTargetAllocator left = new CollisionSafeArtifactTargetAllocator();
        CollisionSafeArtifactTargetAllocator right = new CollisionSafeArtifactTargetAllocator();
        Path canonical = Path.of(
                "ddl", "oracle", "APP.CUSTOMER_20260826_010203_456.oracle.sql");

        assertEquals(canonical, left.reserve(canonical, "a/customer.docx"));
        assertEquals(canonical, right.reserve(canonical, "a/customer.docx"));
        Path leftCollision = left.reserve(canonical, "b/customer.docx");
        Path rightCollision = right.reserve(canonical, "b/customer.docx");

        assertNotEquals(canonical, leftCollision);
        assertEquals(leftCollision, rightCollision);
        assertTrue(leftCollision.getFileName().toString().matches(
                "APP\\.CUSTOMER__sf_[0-9a-f]{10}_20260826_010203_456\\.oracle\\.sql"));

        Path migration = Path.of(
                "migration", "oracle", "V20260826010203001__APP_CUSTOMER_ALTER.sql");
        CollisionSafeArtifactTargetAllocator migrations = new CollisionSafeArtifactTargetAllocator();
        assertEquals(migration, migrations.reserve(migration, "a/customer.docx"));
        assertThrows(IllegalStateException.class,
                () -> migrations.reserve(migration, "b/customer.docx"));
    }

    private void assertIdentityContract(byte[] archive) throws Exception {
        Map<String, byte[]> entries = unzip(archive);
        ArtifactManifest manifest = objectMapper.readValue(entries.get("manifest.json"), ArtifactManifest.class);
        List<ArtifactManifestArtifact> artifacts = manifest.artifacts();

        Set<String> generatedPathsCaseInsensitive = new LinkedHashSet<>();
        for (ArtifactManifestArtifact artifact : artifacts) {
            assertNotNull(artifact.type(), "artifact type must be present");
            assertNotNull(artifact.status(), "artifact status must be present");
            assertNotNull(artifact.logicalName(), "logicalName must be present");
            assertFalse(artifact.logicalName().isBlank(), "logicalName must not be blank");
            assertNotNull(artifact.provenance(), "provenance must be present");
            assertNotNull(artifact.provenance().origin(), "origin must be present");
            assertNotNull(artifact.provenance().sourceName(), "sourceName must be present");
            assertFalse(artifact.provenance().sourceName().isBlank(), "sourceName must not be blank");
            assertNotNull(artifact.provenance().producer(), "producer must be present");
            assertFalse(artifact.provenance().producer().isBlank(), "producer must not be blank");

            assertPlatformContract(artifact);

            if (artifact.status() != ArtifactStatus.GENERATED) {
                assertNull(artifact.path(), "non-generated artifact must not claim a path");
                continue;
            }

            assertNotNull(artifact.path(), "generated artifact must have a path");
            assertTrue(entries.containsKey(artifact.path()), "generated path must exist in ZIP: " + artifact.path());
            assertFalse(artifact.path().startsWith("/"), "artifact path must be package-relative");
            assertFalse(artifact.path().contains("\\"), "artifact path must use portable '/' separators");
            assertFalse(artifact.path().contains("../"), "artifact path must not traverse directories");
            assertTrue(generatedPathsCaseInsensitive.add(artifact.path().toLowerCase(Locale.ROOT)),
                    "generated paths must be case-insensitively unique: " + artifact.path());

            assertPathFamily(artifact);
        }

        long manifestCount = artifacts.stream()
                .filter(a -> a.type() == ArtifactType.MANIFEST && a.status() == ArtifactStatus.GENERATED)
                .count();
        assertEquals(1, manifestCount, "exactly one generated manifest is required");
    }

    private static void assertPlatformContract(ArtifactManifestArtifact artifact) {
        switch (artifact.type()) {
            case DDL, MIGRATION, CRUD, COMPARISON_WORKBOOK, RUN_SCRIPT ->
                    assertNotNull(artifact.platform(), artifact.type() + " must declare a DBMS platform");
            case CANONICAL_JSON, MERMAID_DIAGRAM, GRAPHVIZ_DIAGRAM, MANIFEST,
                 SUMMARY_REPORT, ERROR_REPORT, ISSUE_REPORT ->
                    assertNull(artifact.platform(), artifact.type() + " must be platform-neutral");
        }
    }

    private static void assertPathFamily(ArtifactManifestArtifact artifact) {
        String path = artifact.path();
        String platform = artifact.platform() == null ? null : artifact.platform().commandLineName();
        switch (artifact.type()) {
            case DDL -> {
                assertTrue(path.startsWith("ddl/" + platform + "/"), path);
                assertTrue(path.endsWith("." + platform + ".sql"), path);
            }
            case MIGRATION -> {
                assertTrue(path.startsWith("migration/" + platform + "/V"), path);
                assertTrue(path.endsWith("_ALTER.sql"), path);
            }
            case CRUD -> assertTrue(path.startsWith("crud/" + platform + "/"), path);
            case COMPARISON_WORKBOOK -> {
                assertTrue(path.startsWith("comparison/" + platform + "/"), path);
                assertTrue(path.endsWith("." + platform + ".compare.xlsx"), path);
            }
            case CANONICAL_JSON -> {
                assertTrue(path.startsWith("model/"), path);
                assertTrue(path.endsWith(".schema.json"), path);
            }
            case MERMAID_DIAGRAM -> {
                assertTrue(path.startsWith("diagram/mermaid/"), path);
                assertTrue(path.endsWith(".mmd"), path);
            }
            case GRAPHVIZ_DIAGRAM -> {
                assertTrue(path.startsWith("diagram/graphviz/"), path);
                assertTrue(path.endsWith(".dot"), path);
            }
            case MANIFEST -> assertEquals("manifest.json", path);
            case RUN_SCRIPT -> assertTrue(path.startsWith("scripts/" + platform + "/"), path);
            case SUMMARY_REPORT, ERROR_REPORT, ISSUE_REPORT -> assertTrue(path.startsWith("reports/"), path);
        }
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    assertNull(result.putIfAbsent(entry.getName(), zip.readAllBytes()),
                            "duplicate ZIP entry: " + entry.getName());
                }
                zip.closeEntry();
            }
        }
        assertTrue(result.containsKey("manifest.json"), "manifest.json must exist");
        return result;
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
