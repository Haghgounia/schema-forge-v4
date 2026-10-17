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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

        assertEquals(DatabasePlatform.values().length + 8, entries.size());
        String oracleName = entries.keySet().stream()
                .filter(name -> name.endsWith(".oracle.sql"))
                .findFirst().orElseThrow();
        String sql = new String(entries.get(oracleName), StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
        assertTrue(sql.contains("COMMENT ON TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".mysql.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".mariadb.sql")));
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


    @Test
    void legacyWordPlatformSelectionGeneratesOnlySelectedDatabaseArtifacts() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.DB2_LUW)).thenReturn(MetadataRepository.empty());
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck, mapper, resolver);
        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(), "application/msword", Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromLegacyWord(
                file, "DPS", null, "AUTO", List.of("db2luw")));

        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("ddl/db2luw/")));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("ddl/") && !name.startsWith("ddl/db2luw/")));
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        assertEquals("db2luw", manifest.path("extensions").path("generationOptions")
                .path("platforms").get(0).asText());
    }

    @Test
    void legacyWordZipBatchUsesLegacyParserSchemaAndPerDocumentFaultIsolation() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        byte[] uploadBytes = inputZip(Map.of(
                "legacy/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc", Files.readAllBytes(source),
                "legacy/broken.doc", "not-a-word-document".getBytes(StandardCharsets.UTF_8),
                "readme.txt", "ignored".getBytes(StandardCharsets.UTF_8)));

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(MetadataRepository.empty());
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck, mapper, resolver);
        MockMultipartFile file = new MockMultipartFile(
                "file", "legacy-batch.zip", "application/zip", uploadBytes);

        Map<String, byte[]> entries = unzip(service.generateFromLegacyWordZip(
                file, "TASSHMA", false, null, List.of("oracle")));

        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        assertEquals("PARTIAL_SUCCESS", manifest.path("requestStatus").asText());
        assertEquals("LEGACY_WORD", manifest.path("extensions").path("batchInput").path("inputMode").asText());
        assertEquals("TASSHMA", manifest.path("extensions").path("batchInput").path("schema").asText());
        assertEquals(3, manifest.path("extensions").path("batchInput").path("regularFileCount").asInt());
        assertEquals(2, manifest.path("extensions").path("batchInput").path("processableDocumentCount").asInt());
        assertEquals(1, manifest.path("extensions").path("batchInput").path("successCount").asInt());
        assertEquals(1, manifest.path("extensions").path("batchInput").path("failedCount").asInt());
        assertEquals(1, manifest.path("extensions").path("batchInput").path("skippedCount").asInt());
        assertEquals("TASSHMA", manifest.path("extensions").path("generationOptions")
                .path("legacyWordSchema").asText());

        String summary = new String(entries.get("reports/batch-generation-summary.csv"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("CTPIncomeParamActivityLog.doc\",\"SUCCESS"));
        assertTrue(summary.contains("broken.doc\",\"FAILED"));
        assertTrue(summary.contains("readme.txt\",\"SKIPPED"));
        assertTrue(summary.contains("UNSUPPORTED_EXTENSION"));
        assertTrue(new String(entries.get("reports/batch-generation-errors.log"), StandardCharsets.UTF_8)
                .contains("broken.doc"));

        String generationSummary = new String(
                entries.get("reports/schemaforge-generation-summary.txt"), StandardCharsets.UTF_8);
        assertTrue(generationSummary.contains("Request Type         : LEGACY_WORD_BATCH"));
        assertTrue(generationSummary.contains("Legacy Word Schema"));
        assertTrue(generationSummary.contains("TASSHMA"));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("ddl/oracle/")));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("ddl/mysql/")));
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
}
