package com.behsazan.schemaforge;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Integration-style test for the legacy Word REST service pipeline.
 *
 * <p>It verifies that a binary Word specification is mapped with the requested schema and
 * packaged through the standard multi-artifact output path, including Oracle DDL, canonical
 * metadata and CRUD summary artifacts.</p>
 */
class SchemaForgeLegacyWordApiServiceTest {

    @Test
    void legacyWordRestPathUsesRequiredSchemaAndStandardOutputPipeline() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);

        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                new ObjectMapper(), resolver);
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/msword",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromLegacyWord(file, "DPS"));

        assertEquals(DatabasePlatform.values().length + 7, entries.size());
        String oracleName = entries.keySet().stream()
                .filter(name -> name.endsWith(".oracle.sql"))
                .findFirst().orElseThrow();
        String sql = new String(entries.get(oracleName), StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
        assertTrue(sql.contains("COMMENT ON TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".mysql.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".schema.json")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".metadata-crud-summary.csv")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.dot")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.dot")));

        String modelName = entries.keySet().stream()
                .filter(name -> name.endsWith(".schema.json"))
                .findFirst().orElseThrow();
        assertCanonicalObjectNaming(new ObjectMapper().readTree(entries.get(modelName)));
    }

    private static void assertCanonicalObjectNaming(JsonNode root) {
        for (JsonNode table : root.path("schema").path("tables")) {
            String tableName = table.path("name").asText().toUpperCase();
            JsonNode primaryKey = table.path("primaryKey");
            if (!primaryKey.isMissingNode() && !primaryKey.isNull()) {
                assertEquals("PK_" + tableName, primaryKey.path("name").asText().toUpperCase());
            }
            for (JsonNode key : table.path("uniqueKeys")) {
                assertEquals("UK_" + tableName + "_" + joinedColumns(key.path("columns")),
                        key.path("name").asText().toUpperCase());
            }
            for (JsonNode key : table.path("foreignKeys")) {
                assertEquals("FK_" + tableName + "_" + joinedColumns(key.path("columns")),
                        key.path("name").asText().toUpperCase());
            }
            for (JsonNode index : table.path("indexes")) {
                StringBuilder columns = new StringBuilder();
                for (JsonNode column : index.path("columns")) {
                    if (!columns.isEmpty()) columns.append('_');
                    columns.append(column.path("name").asText().toUpperCase());
                }
                assertEquals("IX_" + tableName + "_" + columns,
                        index.path("name").asText().toUpperCase());
            }
            for (JsonNode check : table.path("checkConstraints")) {
                assertTrue(check.path("name").asText().toUpperCase().startsWith("CHK_" + tableName + "_"),
                        check.toString());
            }
        }
    }

    private static String joinedColumns(JsonNode columns) {
        StringBuilder result = new StringBuilder();
        for (JsonNode column : columns) {
            if (!result.isEmpty()) result.append('_');
            result.append(column.asText().toUpperCase());
        }
        return result.toString();
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
