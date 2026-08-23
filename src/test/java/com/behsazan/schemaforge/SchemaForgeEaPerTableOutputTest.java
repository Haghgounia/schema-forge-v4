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

        assertEquals(22, entries.size());
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".metadata-crud-summary.csv")));
        String modelName = entryName(entries, "model/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.schema\\.json");
        assertTrue(entries.containsKey("manifest.json"));
        String mermaidName = entryName(entries, "diagram/mermaid/tables/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.er\\.mmd");
        String graphvizName = entryName(entries, "diagram/graphviz/tables/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.er\\.dot");
        assertTrue(new String(entries.get(mermaidName), StandardCharsets.UTF_8).contains("erDiagram"));
        assertTrue(new String(entries.get(graphvizName), StandardCharsets.UTF_8).contains("digraph"));
        String conceptualMermaidName = entryName(entries,
                "diagram/mermaid/tables/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.conceptual-erd\\.mmd");
        String conceptualGraphvizName = entryName(entries,
                "diagram/graphviz/tables/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.conceptual-erd\\.dot");
        String conceptualMermaid = new String(entries.get(conceptualMermaidName), StandardCharsets.UTF_8);
        String conceptualGraphviz = new String(entries.get(conceptualGraphvizName), StandardCharsets.UTF_8);
        assertTrue(conceptualMermaid.startsWith("erDiagram"), conceptualMermaid);
        assertFalse(conceptualMermaid.contains("REGULATORY_RULE_ID"), conceptualMermaid);
        assertTrue(conceptualGraphviz.startsWith("digraph SchemaForge_Conceptual_ERD"), conceptualGraphviz);
        assertFalse(conceptualGraphviz.contains("REGULATORY_RULE_ID"), conceptualGraphviz);
        String timestamp = timestampFrom(entryName(entries,
                "ddl/oracle/FEE\\.FEE_VERSION_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.sql"));
        assertTrue(entries.containsKey("ddl/oracle/FEE.REGULATORY_RULE_" + timestamp + ".oracle.sql"));
        assertTrue(entries.containsKey("ddl/oracle/FEE.FEE_VERSION_" + timestamp + ".oracle.sql"));
        assertTrue(entries.containsKey("ddl/postgresql/fee.regulatory_rule_" + timestamp + ".postgresql.sql"));
        assertTrue(entries.containsKey("ddl/postgresql/fee.fee_version_" + timestamp + ".postgresql.sql"));
        assertTrue(entries.containsKey("ddl/db2zos/FEE.REGULATORY_RULE_" + timestamp + ".db2zos.sql"));
        assertTrue(entries.containsKey("ddl/db2zos/FEE.FEE_VERSION_" + timestamp + ".db2zos.sql"));
        assertTrue(entries.containsKey("ddl/sqlserver/FEE.REGULATORY_RULE_" + timestamp + ".sqlserver.sql"));
        assertTrue(entries.containsKey("ddl/sqlserver/FEE.FEE_VERSION_" + timestamp + ".sqlserver.sql"));
        assertTrue(entries.containsKey("ddl/mysql/FEE.REGULATORY_RULE_" + timestamp + ".mysql.sql"));
        assertTrue(entries.containsKey("ddl/mysql/FEE.FEE_VERSION_" + timestamp + ".mysql.sql"));

        String oracleRunAllName = "scripts/oracle/ea-sample_" + timestamp + ".oracle.run-all.sql";
        String postgresqlRunAllName = "scripts/postgresql/ea-sample_" + timestamp + ".postgresql.run-all.sql";
        String db2ZosRunAllName = "scripts/db2zos/ea-sample_" + timestamp + ".db2zos.run-all.sql";
        String sqlServerRunAllName = "scripts/sqlserver/ea-sample_" + timestamp + ".sqlserver.run-all.sql";
        String mysqlRunAllName = "scripts/mysql/ea-sample_" + timestamp + ".mysql.run-all.sql";
        assertTrue(entries.containsKey(oracleRunAllName));
        assertTrue(entries.containsKey(postgresqlRunAllName));
        assertTrue(entries.containsKey(db2ZosRunAllName));
        assertTrue(entries.containsKey(sqlServerRunAllName));
        assertTrue(entries.containsKey(mysqlRunAllName));
        assertFalse(entries.containsKey("ddl/oracle/FEE.REGULATORY_RULE.oracle.sql"));
        assertFalse(entries.containsKey("scripts/oracle/run_all.sql"));

        String oracleRunAll = new String(entries.get(oracleRunAllName), StandardCharsets.UTF_8);
        assertTrue(oracleRunAll.indexOf("FEE.FEE_VERSION_" + timestamp + ".oracle.sql")
                < oracleRunAll.indexOf("FEE.REGULATORY_RULE_" + timestamp + ".oracle.sql"));
        String db2ZosRunAll = new String(entries.get(db2ZosRunAllName), StandardCharsets.UTF_8);
        assertTrue(db2ZosRunAll.indexOf("FEE.FEE_VERSION_" + timestamp + ".db2zos.sql")
                < db2ZosRunAll.indexOf("FEE.REGULATORY_RULE_" + timestamp + ".db2zos.sql"));
        String sqlServerRunAll = new String(entries.get(sqlServerRunAllName), StandardCharsets.UTF_8);
        assertTrue(sqlServerRunAll.contains(
                ":r ../../ddl/sqlserver/FEE.FEE_VERSION_" + timestamp + ".sqlserver.sql"));
        assertTrue(sqlServerRunAll.indexOf("FEE.FEE_VERSION_" + timestamp + ".sqlserver.sql")
                < sqlServerRunAll.indexOf("FEE.REGULATORY_RULE_" + timestamp + ".sqlserver.sql"));
        String mysqlRunAll = new String(entries.get(mysqlRunAllName), StandardCharsets.UTF_8);
        assertTrue(mysqlRunAll.indexOf("FEE.FEE_VERSION_" + timestamp + ".mysql.sql")
                < mysqlRunAll.indexOf("FEE.REGULATORY_RULE_" + timestamp + ".mysql.sql"));

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        assertEquals(2, manifest.path("tableCount").asInt());
        assertEquals(mermaidName, manifest.path("mermaid").asText());
        assertEquals(graphvizName, manifest.path("graphviz").asText());
        assertEquals(conceptualMermaidName, manifest.path("conceptualErdMermaid").asText());
        assertEquals(conceptualGraphvizName, manifest.path("conceptualErdGraphviz").asText());
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
        assertEquals(42, entries.size());
        String timestamp = timestampFrom(entryName(entries, "ddl/oracle/FEE\\.REGULATORY_RULE_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.sql"));
        assertTrue(entries.containsKey("comparison/oracle/FEE.REGULATORY_RULE_" + timestamp + ".oracle.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/oracle/FEE.FEE_VERSION_" + timestamp + ".oracle.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/postgresql/fee.regulatory_rule_" + timestamp + ".postgresql.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/postgresql/fee.fee_version_" + timestamp + ".postgresql.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/db2zos/FEE.REGULATORY_RULE_" + timestamp + ".db2zos.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/db2zos/FEE.FEE_VERSION_" + timestamp + ".db2zos.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/sqlserver/FEE.REGULATORY_RULE_" + timestamp + ".sqlserver.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/sqlserver/FEE.FEE_VERSION_" + timestamp + ".sqlserver.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/mysql/FEE.REGULATORY_RULE_" + timestamp + ".mysql.compare.xlsx"));
        assertTrue(entries.containsKey("comparison/mysql/FEE.FEE_VERSION_" + timestamp + ".mysql.compare.xlsx"));
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String prefix = "migration/" + platform.commandLineName() + "/";
            assertTrue(entries.keySet().stream().anyMatch(name ->
                    name.startsWith(prefix) && name.endsWith("__FEE_REGULATORY_RULE_ALTER.sql")));
            assertTrue(entries.keySet().stream().anyMatch(name ->
                    name.startsWith(prefix) && name.endsWith("__FEE_FEE_VERSION_ALTER.sql")));
        }
    }


    @Test
    void shouldPlaceMetadataCrudArtifactsUnderDialectCrudDirectories() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;

        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                String idColumn = "REGULATORY_RULE".equalsIgnoreCase(tableName)
                        ? "REGULATORY_RULE_ID" : "FEE_VERSION_ID";
                return Optional.of(Table.builder(schemaName, tableName)
                        .addColumn(Column.required(idColumn, DataType.numeric("NUMBER", 19, 0)))
                        .addColumn(Column.required("NAME", DataType.varchar("VARCHAR2", 100)))
                        .primaryKey(new com.behsazan.schemaforge.domain.model.PrimaryKey(
                                com.behsazan.schemaforge.domain.valueobject.Identifier.of("PK_" + tableName),
                                List.of(com.behsazan.schemaforge.domain.valueobject.Identifier.of(idColumn))))
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
        assertTrue(entries.keySet().stream().anyMatch(name ->
                name.startsWith("crud/oracle/FEE.REGULATORY_RULE_")
                        && name.endsWith(".oracle.crud-package.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name ->
                name.startsWith("crud/sqlserver/FEE.REGULATORY_RULE_")
                        && name.endsWith(".sqlserver.crud-procedures.sql")));
        assertFalse(entries.keySet().stream().anyMatch(name ->
                !name.contains("/") && (name.endsWith(".oracle.crud-package.sql")
                        || name.endsWith(".sqlserver.crud-procedures.sql"))));
        String summaryName = entries.keySet().stream()
                .filter(name -> name.endsWith(".metadata-crud-summary.csv"))
                .findFirst().orElseThrow();
        String summary = new String(entries.get(summaryName), StandardCharsets.UTF_8);
        assertTrue(summary.contains("crud/oracle/"));
        assertTrue(summary.contains("crud/sqlserver/"));
    }

    @Test
    void shouldSkipCrudWithoutFailureWhenEaTableHasNoPrimaryKey() throws Exception {
        Path source = TestSamplePaths.EA_SAMPLE;
        String xml = Files.readString(source, StandardCharsets.UTF_8)
                .replaceAll("(?s)<UML:Operation name=\"PK_[^\"]*\">.*?</UML:Operation>", "");

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema("FEE");

        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                new ObjectMapper(), resolver, ea);
        MockMultipartFile file = new MockMultipartFile(
                "file", "ea-no-pk.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> entries = unzip(service.generateFromEaXml(file));

        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("ddl/oracle/FEE.REGULATORY_RULE_")
                && name.endsWith(".oracle.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.dot")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.dot")));
        assertFalse(entries.keySet().stream().anyMatch(name -> name.startsWith("crud/")));

        String summaryName = entries.keySet().stream()
                .filter(name -> name.endsWith(".metadata-crud-summary.csv"))
                .findFirst().orElseThrow();
        String summary = new String(entries.get(summaryName), StandardCharsets.UTF_8);
        assertTrue(summary.contains("SKIPPED_NO_PRIMARY_KEY"));
        assertFalse(summary.contains("FAILED"));
        assertFalse(summary.contains("SKIPPED_TABLE_NOT_FOUND"));
    }

    @Test
    void shouldUseApiSchemaParameterAndRenderEaPrimaryKeysAsIdentity() throws Exception {
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

        Map<String, byte[]> entries = unzip(service.generateFromEaXml(file, "API_SCHEMA"));

        String timestamp = timestampFrom(entryName(entries,
                "ddl/oracle/API_SCHEMA\\.REGULATORY_RULE_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.sql"));
        String oracleRegulatoryRule =
                "ddl/oracle/API_SCHEMA.REGULATORY_RULE_" + timestamp + ".oracle.sql";
        assertTrue(entries.containsKey(oracleRegulatoryRule));
        assertTrue(entries.containsKey(
                "ddl/oracle/API_SCHEMA.FEE_VERSION_" + timestamp + ".oracle.sql"));
        String oracleSql = new String(entries.get(oracleRegulatoryRule), StandardCharsets.UTF_8);
        assertTrue(oracleSql.contains(
                "CREATE SEQUENCE API_SCHEMA.SEQ_REGULATORY_RULE START WITH 1 INCREMENT BY 1"));
        assertTrue(oracleSql.contains("-- Persian table name: قانون نظارتی"));
        assertTrue(oracleSql.contains(
                "COMMENT ON TABLE API_SCHEMA.REGULATORY_RULE IS 'قانون نظارتی';"));
        assertTrue(oracleSql.contains(
                "REGULATORY_RULE_ID NUMBER(3,0) DEFAULT API_SCHEMA.SEQ_REGULATORY_RULE.NEXTVAL NOT NULL"));
        String sqlServerSql = new String(
                entries.get("ddl/sqlserver/API_SCHEMA.REGULATORY_RULE_"
                        + timestamp + ".sqlserver.sql"),
                StandardCharsets.UTF_8);
        assertTrue(sqlServerSql.contains(
                "REGULATORY_RULE_ID DECIMAL(3,0) IDENTITY(1,1) NOT NULL"));

        JsonNode model = objectMapper.readTree(entries.get(entryName(entries,
                "model/ea-sample_\\d{8}_\\d{6}_\\d{3}\\.schema\\.json")));
        assertEquals("API_SCHEMA", model.path("schema").path("name").asText());
        boolean identityFound = false;
        for (JsonNode table : model.path("schema").path("tables")) {
            if (!"REGULATORY_RULE".equals(table.path("name").asText())) {
                continue;
            }
            assertEquals("قانون نظارتی", table.path("persianName").asText());
            for (JsonNode column : table.path("columns")) {
                if ("REGULATORY_RULE_ID".equals(column.path("name").asText())) {
                    identityFound = column.path("identity").asBoolean();
                }
            }
        }
        assertTrue(identityFound);
    }

    private static String entryName(Map<String, byte[]> entries, String regex) {
        List<String> matches = entries.keySet().stream()
                .filter(name -> name.matches(regex))
                .toList();
        assertEquals(1, matches.size(), "Expected exactly one entry matching: " + regex);
        return matches.getFirst();
    }

    private static String timestampFrom(String fileName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("_(\\d{8}_\\d{6}_\\d{3})\\.")
                .matcher(fileName);
        assertTrue(matcher.find(), "Timestamp was not found in: " + fileName);
        return matcher.group(1);
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
