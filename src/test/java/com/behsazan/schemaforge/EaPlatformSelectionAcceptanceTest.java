package com.behsazan.schemaforge;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Acceptance coverage for REST-level EA database platform selection. */
class EaPlatformSelectionAcceptanceTest {

    @Test
    void oracleSelectionGeneratesOnlyOraclePlatformArtifactsAndNeverTouchesOtherRepositories() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema("FEE");
        ObjectMapper mapper = new ObjectMapper();
        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                mapper, resolver, ea);
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/xml",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromEaXml(
                file, null, null, "AUTO", List.of("oracle")));

        assertEquals(10, entries.size());
        assertEquals(2, entries.keySet().stream().filter(name -> name.startsWith("ddl/oracle/")).count());
        assertEquals(1, entries.keySet().stream().filter(name -> name.startsWith("scripts/oracle/")).count());
        assertFalse(entries.keySet().stream().anyMatch(EaPlatformSelectionAcceptanceTest::isNonOraclePlatformArtifact));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".metadata-crud-summary.csv")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".schema.json")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.dot")));

        String crudSummaryName = entries.keySet().stream()
                .filter(name -> name.endsWith(".metadata-crud-summary.csv"))
                .findFirst().orElseThrow();
        String crudSummary = new String(entries.get(crudSummaryName), StandardCharsets.UTF_8);
        assertTrue(crudSummary.contains("ORACLE"));
        assertFalse(crudSummary.contains("SQLSERVER"));

        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        JsonNode platforms = manifest.path("extensions").path("generationOptions").path("platforms");
        assertEquals(1, platforms.size());
        assertEquals("oracle", platforms.get(0).asText());

        verify(resolver, atLeastOnce()).resolve(DatabasePlatform.ORACLE);
        verify(resolver, never()).resolve(DatabasePlatform.POSTGRESQL);
        verify(resolver, never()).resolve(DatabasePlatform.DB2_ZOS);
        verify(resolver, never()).resolve(DatabasePlatform.DB2_LUW);
        verify(resolver, never()).resolve(DatabasePlatform.SQLSERVER);
        verify(resolver, never()).resolve(DatabasePlatform.MYSQL);
    }

    @Test
    void selectionParserSupportsRepeatedCommaSeparatedAliasesAndAllDefault() {
        assertEquals(Set.of(DatabasePlatform.ORACLE, DatabasePlatform.MYSQL, DatabasePlatform.DB2_LUW),
                DatabasePlatform.parseSelection(List.of("oracle,mysql", "luw")));
        assertEquals(Set.of(DatabasePlatform.values()), DatabasePlatform.parseSelection(null));
        assertEquals(Set.of(DatabasePlatform.values()), DatabasePlatform.parseSelection(List.of("all")));
    }

    private static boolean isNonOraclePlatformArtifact(String name) {
        return name.startsWith("ddl/postgresql/")
                || name.startsWith("ddl/db2zos/")
                || name.startsWith("ddl/db2luw/")
                || name.startsWith("ddl/sqlserver/")
                || name.startsWith("ddl/mysql/")
                || name.startsWith("scripts/postgresql/")
                || name.startsWith("scripts/db2zos/")
                || name.startsWith("scripts/db2luw/")
                || name.startsWith("scripts/sqlserver/")
                || name.startsWith("scripts/mysql/")
                || name.startsWith("migration/postgresql/")
                || name.startsWith("migration/db2zos/")
                || name.startsWith("migration/db2luw/")
                || name.startsWith("migration/sqlserver/")
                || name.startsWith("migration/mysql/")
                || name.startsWith("crud/sqlserver/")
                || name.startsWith("comparison/postgresql/")
                || name.startsWith("comparison/db2zos/")
                || name.startsWith("comparison/db2luw/")
                || name.startsWith("comparison/sqlserver/")
                || name.startsWith("comparison/mysql/");
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }
}
