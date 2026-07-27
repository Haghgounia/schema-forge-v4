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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the behavior and regression expectations of Schema Forge Api Service Regression.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SchemaForgeApiServiceRegressionTest {

    private static final Pattern JSON_NAME = Pattern.compile(
            "MCB\\.BIM\\.TBL\\.PROVINCES\\.V1\\.2_\\d{8}_\\d{6}_\\d{3}\\.json");
    private static final Pattern ORACLE_NAME = Pattern.compile(
            "MCB\\.BIM\\.TBL\\.PROVINCES\\.V1\\.2_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.sql");
    private static final Pattern POSTGRESQL_NAME = Pattern.compile(
            "MCB\\.BIM\\.TBL\\.PROVINCES\\.V1\\.2_\\d{8}_\\d{6}_\\d{3}\\.postgresql\\.sql");

    @Test
    void restWordGenerationShouldUseLatestParserAndTimestampAllArtifacts() throws Exception {
        Path source = TestSamplePaths.PROVINCES_V1_2;

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);

        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(),
                spellCheck, new ObjectMapper(), resolver);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromWord(file));
        assertEquals(3, entries.size());

        String jsonName = entries.keySet().stream().filter(name -> JSON_NAME.matcher(name).matches())
                .findFirst().orElseThrow();
        String oracleName = entries.keySet().stream().filter(name -> ORACLE_NAME.matcher(name).matches())
                .findFirst().orElseThrow();
        String postgresqlName = entries.keySet().stream().filter(name -> POSTGRESQL_NAME.matcher(name).matches())
                .findFirst().orElseThrow();

        String sharedTimestamp = jsonName.substring(
                "MCB.BIM.TBL.PROVINCES.V1.2_".length(), jsonName.length() - ".json".length());
        assertTrue(oracleName.contains("_" + sharedTimestamp + ".oracle.sql"));
        assertTrue(postgresqlName.contains("_" + sharedTimestamp + ".postgresql.sql"));

        JsonNode json = new ObjectMapper().readTree(entries.get(jsonName));
        JsonNode table = json.path("schema").path("tables").get(0);
        assertEquals(20, table.path("columns").size());
        assertEquals(3, table.path("foreignKeys").size());
        assertTrue(table.path("columns").toString().contains("LANGUAGE_ID"));
        assertTrue(table.path("columns").toString().contains("COUNTRY_ID"));
        assertTrue(table.path("columns").toString().contains("CALENDAR_ID"));

        String oracleSql = new String(entries.get(oracleName));
        String postgresqlSql = new String(entries.get(postgresqlName));
        assertTrue(oracleSql.contains("FK_PROVINCES_LANGUAGE_ID"));
        assertTrue(oracleSql.contains("FK_PROVINCES_COUNTRY_ID"));
        assertTrue(oracleSql.contains("FK_PROVINCES_CALENDAR_ID"));
        assertTrue(oracleSql.contains(") TABLESPACE TS_DPS;"));
        assertTrue(oracleSql.contains("TABLESPACE ITS_DPS"));
        assertTrue(oracleSql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.PROVINCES TO U_DEVELOPER;"));
        assertTrue(oracleSql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON DPS.PROVINCES TO U_DESIGNER;"));
        assertTrue(postgresqlSql.contains("fk_provinces_language_id"));
        assertTrue(postgresqlSql.contains("fk_provinces_country_id"));
        assertTrue(postgresqlSql.contains("fk_provinces_calendar_id"));
        assertTrue(postgresqlSql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON dps.provinces TO U_DEVELOPER;"));
        assertTrue(postgresqlSql.contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON dps.provinces TO U_DESIGNER;"));
    }

    private static Map<String, byte[]> unzip(byte[] content) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
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
