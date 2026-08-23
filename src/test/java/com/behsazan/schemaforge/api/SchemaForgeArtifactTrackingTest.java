package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaForgeArtifactTrackingTest {

    @Test
    void wordGeneratedDescriptorsMatchActualArchiveEntries() throws Exception {
        SchemaForgeApiService service = service();
        Path source = TestSamplePaths.PROVINCES_V1_2;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        SchemaForgeApiService.GenerationArchive result = service.generateFromWordTracked(file);

        assertGeneratedDescriptorsMatchArchive(result);
        assertEquals(1, result.artifacts().stream().map(ArtifactDescriptor::generationId).distinct().count());
        assertTrue(result.artifacts().stream().allMatch(
                artifact -> artifact.provenance().origin() == ArtifactOrigin.STANDARD_WORD));
    }

    @Test
    void zipBatchDescriptorsUseFinalArchiveLayout() throws Exception {
        SchemaForgeApiService service = service();
        Path source = TestSamplePaths.PROVINCES_V1_2;
        byte[] batch = zip("specifications/" + source.getFileName(), Files.readAllBytes(source));
        MockMultipartFile file = new MockMultipartFile("file", "batch.zip", "application/zip", batch);

        SchemaForgeApiService.GenerationArchive result = service.generateFromZipTracked(file);

        assertGeneratedDescriptorsMatchArchive(result);
        assertEquals(1, result.artifacts().stream().map(ArtifactDescriptor::generationId).distinct().count());
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.status() == ArtifactStatus.GENERATED
                        && artifact.relativePath().startsWith("ddl/oracle/")));
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.status() == ArtifactStatus.GENERATED
                        && artifact.relativePath().startsWith("reports/")));
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.provenance().sourceName().equals("specifications/" + source.getFileName())));
    }

    @Test
    void eaGeneratedDescriptorsMatchActualArchiveEntries() throws Exception {
        SchemaForgeApiService service = service();
        Path source = TestSamplePaths.EA_SAMPLE;
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/xml", Files.readAllBytes(source));

        SchemaForgeApiService.GenerationArchive result = service.generateFromEaXmlTracked(file, null);

        assertGeneratedDescriptorsMatchArchive(result);
        assertEquals(1, result.artifacts().stream().map(ArtifactDescriptor::generationId).distinct().count());
        assertTrue(result.artifacts().stream().anyMatch(artifact ->
                artifact.status() == ArtifactStatus.GENERATED
                        && artifact.relativePath().equals("manifest.json")));
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

    private static void assertGeneratedDescriptorsMatchArchive(
            SchemaForgeApiService.GenerationArchive result) throws Exception {
        Set<String> archiveEntries = unzipEntries(result.content());
        Set<String> artifactPaths = new LinkedHashSet<>();
        for (ArtifactDescriptor artifact : result.artifacts()) {
            if (artifact.status() == ArtifactStatus.GENERATED) {
                assertFalse(artifact.relativePath().isBlank());
                artifactPaths.add(artifact.relativePath());
            }
        }
        assertEquals(archiveEntries, artifactPaths);
    }

    private static Set<String> unzipEntries(byte[] content) throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] zip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
