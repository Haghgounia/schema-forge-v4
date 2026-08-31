package com.behsazan.schemaforge;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the behavior and regression expectations of Schema Forge Api Comparison Excel.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SchemaForgeApiComparisonExcelTest {
    private static final Pattern ORACLE_COMPARE = Pattern.compile(
            "comparison/oracle/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.compare\\.xlsx");
    private static final Pattern POSTGRESQL_COMPARE = Pattern.compile(
            "comparison/postgresql/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.postgresql\\.compare\\.xlsx");
    private static final Pattern DB2_ZOS_COMPARE = Pattern.compile(
            "comparison/db2zos/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.db2zos\\.compare\\.xlsx");
    private static final Pattern DB2_LUW_COMPARE = Pattern.compile(
            "comparison/db2luw/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.db2luw\\.compare\\.xlsx");
    private static final Pattern SQLSERVER_COMPARE = Pattern.compile(
            "comparison/sqlserver/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.sqlserver\\.compare\\.xlsx");
    private static final Pattern MYSQL_COMPARE = Pattern.compile(
            "comparison/mysql/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.mysql\\.compare\\.xlsx");


    @Test
    void shouldAddComparisonWorkbookForEachDatabaseWhenTableAlreadyExists() throws Exception {
        Path source = TestSamplePaths.PROVINCES_V1_2;

        Table databaseTable = Table.builder("BIM", "PROVINCES")
                .addColumn(Column.required("PROVINCE_ID", DataType.numeric("NUMBER", 2, 0)))
                .addColumn(Column.nullable("PROVINCE_CODE", DataType.varchar("VARCHAR2", 10)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_PROVINCES"),
                        List.of(Identifier.of("PROVINCE_ID"))))
                .build();

        MetadataRepository repository = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                return Map.of();
            }

            @Override
            public Optional<Table> findTable(String schemaName, String tableName) {
                return "BIM".equalsIgnoreCase(schemaName) && "PROVINCES".equalsIgnoreCase(tableName)
                        ? Optional.of(databaseTable) : Optional.empty();
            }

            @Override public boolean schemaExists(String schemaName) { return true; }
            @Override public List<String> findTableSchemas(String tableName) { return List.of("BIM"); }
        };

        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(repository);

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        SchemaForgeApiService service = new SchemaForgeApiService(
                AuditProperties.defaults(), GrantProperties.defaults(), spellCheck,
                new ObjectMapper(), resolver);

        MockMultipartFile file = new MockMultipartFile(
                "file", source.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(source));

        Map<String, byte[]> entries = unzip(service.generateFromWord(file));
        assertEquals(DatabasePlatform.values().length * 3 + 9, entries.size());
        assertTrue(entries.keySet().stream().anyMatch(name -> name.matches(
                "crud/oracle/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.oracle\\.crud-package\\.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.matches(
                "crud/sqlserver/BIM\\.PROVINCES_\\d{8}_\\d{6}_\\d{3}\\.sqlserver\\.crud-procedures\\.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".metadata-crud-summary.csv")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".er.dot")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.mmd")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".conceptual-erd.dot")));
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            String prefix = "migration/" + platform.commandLineName() + "/";
            assertTrue(entries.keySet().stream().anyMatch(name ->
                    name.startsWith(prefix)
                            && name.matches(java.util.regex.Pattern.quote(prefix)
                            + "V\\d{17}__BIM_PROVINCES_ALTER\\.sql")),
                    "missing Flyway migration for " + platform);
        }
        // Existing-table detection must never replace the ordinary CREATE scripts.
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".oracle.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".postgresql.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".db2zos.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".db2luw.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".sqlserver.sql")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.endsWith(".mysql.sql")));

        String oracleCreateName = entries.keySet().stream()
                .filter(name -> name.startsWith("ddl/oracle/") && name.endsWith(".oracle.sql"))
                .findFirst().orElseThrow();
        String oracleMigrationName = entries.keySet().stream()
                .filter(name -> name.startsWith("migration/oracle/")
                        && name.endsWith("__BIM_PROVINCES_ALTER.sql"))
                .findFirst().orElseThrow();
        assertTrue(new String(entries.get(oracleCreateName)).contains("CREATE TABLE BIM.PROVINCES"));
        assertTrue(new String(entries.get(oracleMigrationName)).contains("ALTER TABLE BIM.PROVINCES"));

        String oracleName = entries.keySet().stream().filter(name -> ORACLE_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();
        String postgresqlName = entries.keySet().stream().filter(name -> POSTGRESQL_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();
        String db2ZosName = entries.keySet().stream().filter(name -> DB2_ZOS_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();
        String db2LuwName = entries.keySet().stream().filter(name -> DB2_LUW_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();
        String sqlServerName = entries.keySet().stream().filter(name -> SQLSERVER_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();
        String mysqlName = entries.keySet().stream().filter(name -> MYSQL_COMPARE.matcher(name).matches())
                .findFirst().orElseThrow();

        verifyWorkbook(entries.get(oracleName));
        verifyWorkbook(entries.get(postgresqlName));
        verifyWorkbook(entries.get(db2ZosName));
        verifyWorkbook(entries.get(db2LuwName));
        verifyWorkbook(entries.get(sqlServerName));
        verifyWorkbook(entries.get(mysqlName));
    }

    private static void verifyWorkbook(byte[] content) throws Exception {
        assertNotNull(content);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("PROVINCES");
            assertNotNull(sheet);
            assertEquals(22, sheet.getRow(0).getLastCellNum());
            assertEquals("COLUMN_USAGE", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("DIFF", sheet.getRow(0).getCell(21).getStringCellValue());
            assertTrue(sheet.getLastRowNum() >= 20);
            assertNotNull(workbook.getSheet("PRIMARY_KEY_COMPARE"));
            assertNotNull(workbook.getSheet("FOREIGN_KEYS_COMPARE"));
            assertNotNull(workbook.getSheet("INDEXES_COMPARE"));
            assertNotNull(workbook.getSheet("UNIQUE_INDEXES_COMPARE"));
        }
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
