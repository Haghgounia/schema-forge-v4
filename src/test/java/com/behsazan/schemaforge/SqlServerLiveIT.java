package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.JdbcSqlServerMetadataRepository;
import com.behsazan.schemaforge.reporting.SchemaCompareExcelWriter;
import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerConnectionProbeResult;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerConnectionProbeService;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerOfflineDdlValidator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit Microsoft SQL Server integration test. The *IT suffix keeps it out of the normal test suite.
 * Run only against an approved disposable validation database.
 */
class SqlServerLiveIT {
    private static final String CONFIRMATION = "I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE";

    @Test
    void probesExecutesReadsMetadataAndProducesSameComparison() throws Exception {
        String confirmation = required("schemaforge.sqlserver.execution.confirm");
        assertEquals(CONFIRMATION, confirmation,
                "Explicit DDL confirmation is required for the disposable SQL Server integration test.");

        String url = required("schemaforge.sqlserver.url");
        String user = System.getProperty("schemaforge.sqlserver.user", "");
        String password = System.getProperty("schemaforge.sqlserver.password", "");
        String driver = System.getProperty(
                "schemaforge.sqlserver.driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        JdbcConnectionSettings settings = new JdbcConnectionSettings(url, user, password);

        SqlServerConnectionProbeResult probe = new SqlServerConnectionProbeService().probe(settings, driver);
        assertTrue(probe.successful(), probe.message());

        String suffix = Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase(Locale.ROOT);
        String schemaName = safeIdentifier(
                System.getProperty("schemaforge.sqlserver.test.schema-prefix", "SFV") + "_" + suffix);
        String parentName = "PARENTS";
        String childName = "CHILD_RECORDS";
        String sequenceName = "SF_SEQUENCE";
        String indexName = "IX_CHILD_RECORDS_PARENT";

        DatabaseSchema model = smokeModel(schemaName, parentName, childName, sequenceName, indexName);
        String sql = new DdlGenerator(new SqlServerDialect()).generate(model);
        assertTrue(new SqlServerOfflineDdlValidator().validate(sql).valid());

        Class.forName(driver);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                for (String sqlStatement : new SqlScriptStatementParser().parse(sql, DatabasePlatform.SQLSERVER)) {
                    execute(connection, sqlStatement);
                }

                assertCatalogObjects(connection, schemaName, parentName, childName, sequenceName, indexName);
                assertMetadataAndComparison(url, user, password, driver, model, parentName, childName);
            } finally {
                executeIgnoringFailure(connection,
                        "DROP TABLE " + quote(schemaName) + "." + quote(childName));
                executeIgnoringFailure(connection,
                        "DROP TABLE " + quote(schemaName) + "." + quote(parentName));
                executeIgnoringFailure(connection,
                        "DROP SEQUENCE " + quote(schemaName) + "." + quote(sequenceName));
                executeIgnoringFailure(connection, "DROP SCHEMA " + quote(schemaName));
            }
        }
    }

    private DatabaseSchema smokeModel(
            String schema,
            String parentName,
            String childName,
            String sequenceName,
            String indexName) {
        Column parentId = column("ID", DataType.numeric("NUMBER", 9, 0), false,
                "Parent identifier", true, 1);
        Column parentCode = column("CODE", DataType.varchar("VARCHAR2", 20), false,
                "Parent business code", false, 2);
        Table parent = Table.builder(schema, parentName)
                .description("SchemaForge disposable SQL Server parent table")
                .addColumn(parentId)
                .addColumn(parentCode)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_" + parentName), List.of(Identifier.of("ID"))))
                .build();

        Column childId = column("ID", DataType.numeric("NUMBER", 9, 0), false,
                "Child identifier", true, 1);
        Column childParentId = column("PARENT_ID", DataType.numeric("NUMBER", 9, 0), false,
                "Parent reference", false, 2);
        Column childCode = column("CODE", DataType.varchar("VARCHAR2", 30), false,
                "Child business code", false, 3);
        Column childActive = column("ACTIVE", DataType.numeric("NUMBER", 1, 0), true,
                "Child active flag", false, 4);
        Table child = Table.builder(schema, childName)
                .description("SchemaForge disposable SQL Server child table")
                .addColumn(childId)
                .addColumn(childParentId)
                .addColumn(childCode)
                .addColumn(childActive)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_" + childName), List.of(Identifier.of("ID"))))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_" + childName + "_ACTIVE"), "ACTIVE IN (0, 1)"))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_" + childName + "_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of(schema, parentName),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION,
                        false,
                        false,
                        true,
                        true))
                .addIndex(new Index(
                        Identifier.of(indexName),
                        List.of(
                                new IndexColumn(Identifier.of("PARENT_ID"), SortDirection.ASC),
                                new IndexColumn(Identifier.of("CODE"), SortDirection.DESC)),
                        IndexType.NORMAL,
                        Description.empty(),
                        List.of(Identifier.of("ACTIVE")),
                        null))
                .build();

        Sequence sequence = new Sequence(
                QualifiedName.of(schema, sequenceName),
                1, 1, 1L, 999999L, false, 20, Description.empty());
        return DatabaseSchema.builder(schema)
                .addSequence(sequence)
                .addTable(parent)
                .addTable(child)
                .build();
    }

    private Column column(
            String name,
            DataType type,
            boolean nullable,
            String description,
            boolean identity,
            int position) {
        return new Column(
                Identifier.of(name),
                type,
                nullable,
                null,
                new Description(description),
                identity,
                position);
    }

    private void assertCatalogObjects(
            Connection connection,
            String schema,
            String parent,
            String child,
            String sequence,
            String index) throws Exception {
        assertEquals(2, count(connection,
                "SELECT COUNT(*) FROM sys.tables T JOIN sys.schemas S ON S.schema_id=T.schema_id "
                        + "WHERE S.name=? AND T.name IN (?,?)",
                schema, parent, child));
        assertEquals(6, count(connection,
                "SELECT COUNT(*) FROM sys.columns C JOIN sys.tables T ON T.object_id=C.object_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id "
                        + "WHERE S.name=? AND T.name IN (?,?)",
                schema, parent, child));
        assertEquals(2, count(connection,
                "SELECT COUNT(*) FROM sys.key_constraints K JOIN sys.tables T ON T.object_id=K.parent_object_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id WHERE S.name=? AND K.type='PK'",
                schema));
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM sys.foreign_keys F JOIN sys.tables T ON T.object_id=F.parent_object_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id "
                        + "WHERE S.name=? AND F.is_disabled=0 AND F.is_not_trusted=0",
                schema));
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM sys.check_constraints C "
                        + "JOIN sys.tables T ON T.object_id=C.parent_object_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id "
                        + "WHERE S.name=? AND C.is_disabled=0 AND C.is_not_trusted=0",
                schema));
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM sys.indexes I JOIN sys.tables T ON T.object_id=I.object_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id WHERE S.name=? AND I.name=?",
                schema, index));
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM sys.sequences Q JOIN sys.schemas S ON S.schema_id=Q.schema_id "
                        + "WHERE S.name=? AND Q.name=?",
                schema, sequence));
        assertEquals(8, count(connection,
                "SELECT COUNT(*) FROM sys.extended_properties EP "
                        + "JOIN sys.tables T ON T.object_id=EP.major_id "
                        + "JOIN sys.schemas S ON S.schema_id=T.schema_id "
                        + "WHERE EP.class=1 AND EP.name=N'MS_Description' AND S.name=?",
                schema));
    }

    private void assertMetadataAndComparison(
            String url,
            String user,
            String password,
            String driver,
            DatabaseSchema model,
            String parentName,
            String childName) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName(driver);
        JdbcSqlServerMetadataRepository repository = new JdbcSqlServerMetadataRepository(
                new NamedParameterJdbcTemplate(dataSource));
        SchemaCompareExcelWriter writer = new SchemaCompareExcelWriter();

        for (String tableName : List.of(parentName, childName)) {
            Table document = model.tables().stream()
                    .filter(table -> table.qualifiedName().name().value().equals(tableName))
                    .findFirst()
                    .orElseThrow();
            Table database = repository.findTable(
                            document.qualifiedName().schemaName().orElseThrow().value(), tableName)
                    .orElseThrow();
            byte[] workbook = writer.write(
                    document, database, Map.of(), DatabasePlatform.SQLSERVER);
            assertWorkbookSame(workbook, tableName);
        }
    }

    private void assertWorkbookSame(byte[] content, String tableName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var columnSheet = workbook.getSheet(tableName);
            assertTrue(columnSheet != null, "Missing column comparison sheet for " + tableName);
            for (int row = 1; row <= columnSheet.getLastRowNum(); row++) {
                var current = columnSheet.getRow(row);
                if (current == null) continue;
                assertTrue(current.getCell(21).getStringCellValue().isBlank(),
                        "Column comparison is not SAME at row " + row + ": "
                                + current.getCell(21).getStringCellValue());
            }

            for (String sheetName : List.of(
                    "PRIMARY_KEY_COMPARE", "FOREIGN_KEYS_COMPARE",
                    "INDEXES_COMPARE", "UNIQUE_INDEXES_COMPARE")) {
                var sheet = workbook.getSheet(sheetName);
                assertTrue(sheet != null, "Missing object comparison sheet " + sheetName);
                for (int row = 1; row <= sheet.getLastRowNum(); row++) {
                    var current = sheet.getRow(row);
                    if (current == null) continue;
                    String status = current.getCell(5).getStringCellValue();
                    assertFalse(status.isBlank(), "Missing comparison status in " + sheetName);
                    assertEquals("SAME", status,
                            "Object comparison is not SAME in " + sheetName + " row " + row);
                }
            }
        }
    }

    private int count(Connection connection, String sql, String... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeIgnoringFailure(Connection connection, String sql) {
        try {
            execute(connection, sql);
        } catch (Exception ignored) {
            // Best-effort cleanup for disposable integration-test objects.
        }
    }

    private String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + property);
        }
        return value.trim();
    }

    private String safeIdentifier(String value) {
        String safe = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (safe.isBlank() || !Character.isLetter(safe.charAt(0))) safe = "SFV_" + safe;
        return safe.length() <= 128 ? safe : safe.substring(0, 128);
    }

    private String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }
}
