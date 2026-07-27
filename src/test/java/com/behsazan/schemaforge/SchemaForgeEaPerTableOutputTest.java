package com.behsazan.schemaforge;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
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
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the behavior and regression expectations of Schema Forge EA Per Table Output.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SchemaForgeEaPerTableOutputTest {

    @Test
    void shouldGenerateIndependentSqlFilesAndRunAllForEveryEaTable() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema("FEE");
        ObjectMapper objectMapper = new ObjectMapper();

        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                objectMapper, resolver, ea);

        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/xml",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromEaXml(file));

        assertEquals(11, entries.size());
        assertTrue(entries.containsKey("model.json"));
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("oracle/FEE.REGULATORY_RULE.oracle.sql"));
        assertTrue(entries.containsKey("oracle/FEE.FEE_VERSION.oracle.sql"));
        assertTrue(entries.containsKey("postgresql/fee.regulatory_rule.postgresql.sql"));
        assertTrue(entries.containsKey("postgresql/fee.fee_version.postgresql.sql"));
        assertTrue(entries.containsKey("db2zos/FEE.REGULATORY_RULE.db2zos.sql"));
        assertTrue(entries.containsKey("db2zos/FEE.FEE_VERSION.db2zos.sql"));
        assertTrue(entries.containsKey("oracle/run_all.sql"));
        assertTrue(entries.containsKey("postgresql/run_all.sql"));
        assertTrue(entries.containsKey("db2zos/run_all.sql"));
        assertFalse(entries.keySet().stream().anyMatch(name -> name.matches("ea-sample_.*\\.oracle\\.sql")));

        String oracleRunAll = new String(entries.get("oracle/run_all.sql"), StandardCharsets.UTF_8);
        assertTrue(oracleRunAll.indexOf("FEE.FEE_VERSION.oracle.sql")
                < oracleRunAll.indexOf("FEE.REGULATORY_RULE.oracle.sql"));
        String db2ZosRunAll = new String(entries.get("db2zos/run_all.sql"), StandardCharsets.UTF_8);
        assertTrue(db2ZosRunAll.indexOf("FEE.FEE_VERSION.db2zos.sql")
                < db2ZosRunAll.indexOf("FEE.REGULATORY_RULE.db2zos.sql"));

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        assertEquals(2, manifest.path("tableCount").asInt());
        assertEquals(2, manifest.path("tables").size());
    }


    @Test
    void shouldPlaceEaComparisonWorkbooksInPerDialectPerTableFolders() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;

        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                if (!"FEE".equalsIgnoreCase(schemaName)) return Optional.empty();
                String idColumn = "REGULATORY_RULE".equalsIgnoreCase(tableName)
                        ? "REGULATORY_RULE_ID" : "FEE_VERSION_ID";
                return Optional.of(Table.builder("FEE", tableName)
                        .addColumn(Column.required(idColumn, DataType.numeric("NUMBER", 10, 0)))
                        .build());
            }

            @Override public boolean schemaExists(String schemaName) { return true; }
            @Override public List<String> findTableSchemas(String tableName) { return List.of("FEE"); }
        };

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(repository);
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema("FEE");

        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                new ObjectMapper(), resolver, ea);
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/xml",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromEaXml(file));
        assertEquals(17, entries.size());
        assertTrue(entries.containsKey("comparison/oracle/FEE.REGULATORY_RULE.oracle.xlsx"));
        assertTrue(entries.containsKey("comparison/oracle/FEE.FEE_VERSION.oracle.xlsx"));
        assertTrue(entries.containsKey("comparison/postgresql/fee.regulatory_rule.postgresql.xlsx"));
        assertTrue(entries.containsKey("comparison/postgresql/fee.fee_version.postgresql.xlsx"));
        assertTrue(entries.containsKey("comparison/db2zos/FEE.REGULATORY_RULE.db2zos.xlsx"));
        assertTrue(entries.containsKey("comparison/db2zos/FEE.FEE_VERSION.db2zos.xlsx"));
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
}
