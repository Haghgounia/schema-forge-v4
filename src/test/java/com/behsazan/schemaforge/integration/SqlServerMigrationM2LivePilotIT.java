package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
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
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.JdbcSqlServerMetadataRepository;
import com.behsazan.schemaforge.migration.MigrationArtifact;
import com.behsazan.schemaforge.migration.MigrationGenerationService;
import com.behsazan.schemaforge.migration.MigrationRenderOptions;
import com.behsazan.schemaforge.migration.SchemaDiffEngine;
import com.behsazan.schemaforge.migration.TableMigrationPlan;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Microsoft SQL Server pilot for ALTER/Migration M2. */
class SqlServerMigrationM2LivePilotIT {
    private static final String PARENT = "SF_M2_PARENT";
    private static final String CHILD = "SF_M2_CHILD";

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesRealM2MigrationAndPreservesCreateOutput() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "SQL Server M2 live pilot disabled; provide schemaforge.sqlserver.migration.jdbc.url and jdbc.user");
        config.validate();

        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        DriverManager.setLoginTimeout(15);
        Path outputDir = Path.of(config.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        String serverVersion = "unknown";
        String databaseName = "unknown";
        String loginName = "unknown";
        int statementsExecuted = 0;
        boolean createGenerated = false;
        boolean dataPreserved = false;
        int initialColumnChanges = -1;
        int initialObjectChanges = -1;
        int residualChanges = -1;

        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(true);
            DatabaseMetaData meta = connection.getMetaData();
            serverVersion = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            assertTrue(meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft sql server"),
                    "pilot must run against real Microsoft SQL Server");

            databaseName = querySingle(connection, "SELECT DB_NAME()");
            loginName = querySingle(connection, "SELECT SUSER_SNAME()");
            assertFalse(isSystemDatabase(databaseName),
                    "refusing destructive SQL Server pilot in system database " + databaseName);

            ensureSchema(connection, config.schema());
            resetPilotTables(connection, config.schema());
            createOldLiveState(connection, config.schema());

            JdbcSqlServerMetadataRepository repository = repository(config);
            Table desiredParent = desiredParent(config.schema());
            Table desiredChild = desiredChild(config.schema());

            DatabaseSchema desiredSchema = DatabaseSchema.builder(config.schema())
                    .addTable(desiredParent)
                    .addTable(desiredChild)
                    .build();
            String createSql = new DdlGenerator(DialectFactory.create(DatabasePlatform.SQLSERVER)).generate(desiredSchema);
            Path createFile = outputDir.resolve("sqlserver-m2-pilot-create-reference.sqlserver.sql");
            Files.writeString(createFile, createSql, StandardCharsets.UTF_8);
            String expectedCreate = ("CREATE TABLE " + config.schema() + "." + CHILD).toLowerCase(Locale.ROOT);
            createGenerated = createSql.toLowerCase(Locale.ROOT).contains(expectedCreate);
            assertTrue(createGenerated,
                    "full CREATE TABLE output must be generated even when the live table already exists");

            Table liveBefore = repository.findTable(config.schema(), CHILD).orElseThrow();
            TableMigrationPlan initialPlan = new SchemaDiffEngine().diff(
                    DatabasePlatform.SQLSERVER, liveBefore, desiredChild);
            initialColumnChanges = initialPlan.columnChanges().size();
            initialObjectChanges = initialPlan.objectChanges().size();
            assertTrue(initialColumnChanges >= 5, "pilot must exercise multiple column changes");
            assertTrue(initialObjectChanges >= 5, "pilot must exercise PK/FK/UK/CHECK/INDEX changes");

            MigrationGenerationService service = new MigrationGenerationService();
            MigrationArtifact safe = service.generate(
                    DatabasePlatform.SQLSERVER, liveBefore, desiredChild, MigrationRenderOptions.safeDefaults());
            assertTrue(safe.sql().contains("-- BLOCKED:"),
                    "safe render must comment destructive migration SQL");
            Files.writeString(outputDir.resolve("SAFE__" + safe.fileName()), safe.sql(), StandardCharsets.UTF_8);

            MigrationArtifact confirmed = service.generate(
                    DatabasePlatform.SQLSERVER, liveBefore, desiredChild, new MigrationRenderOptions(true));
            assertFalse(confirmed.sql().contains("-- BLOCKED: destructive"),
                    "confirmed render must enable explicitly approved destructive SQL");
            int dependencyDrop = confirmed.sql().indexOf("DROP INDEX IX_SF_M2_CHILD_PARENT ON "
                    + config.schema() + "." + CHILD);
            int parentAlter = confirmed.sql().indexOf("ALTER TABLE " + config.schema() + "." + CHILD
                    + " ALTER COLUMN PARENT_ID BIGINT NOT NULL");
            int dependencyRecreate = confirmed.sql().lastIndexOf("CREATE INDEX IX_SF_M2_CHILD_PARENT ON "
                    + config.schema() + "." + CHILD + "(PARENT_ID)");
            assertTrue(dependencyDrop >= 0,
                    "unchanged SQL Server dependency index must be temporarily dropped");
            assertTrue(parentAlter > dependencyDrop,
                    "PARENT_ID ALTER COLUMN must execute after dependency DROP");
            assertTrue(dependencyRecreate > parentAlter,
                    "unchanged SQL Server dependency index must be recreated after ALTER COLUMN");
            Files.writeString(outputDir.resolve(confirmed.fileName()), confirmed.sql(), StandardCharsets.UTF_8);

            for (String sql : splitter.parse(confirmed.sql(), DatabasePlatform.SQLSERVER)) {
                if (sql == null || sql.isBlank() || commentOnly(sql)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(config.statementTimeoutSeconds());
                    statement.execute(sql);
                    statementsExecuted++;
                }
            }

            Table liveAfter = repository.findTable(config.schema(), CHILD).orElseThrow();
            TableMigrationPlan afterPlan = new SchemaDiffEngine().diff(
                    DatabasePlatform.SQLSERVER, liveAfter, desiredChild);
            residualChanges = afterPlan.columnChanges().size() + afterPlan.objectChanges().size();
            if (!afterPlan.empty()) {
                Files.writeString(outputDir.resolve("residual-diff.txt"), describe(afterPlan), StandardCharsets.UTF_8);
            }
            assertTrue(afterPlan.empty(),
                    "post-migration SQL Server metadata must match desired M2 model; see residual-diff.txt if present");

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT ID,PARENT_ID,CODE,STATUS,MOBILE_NO FROM " + q(config.schema()) + "." + q(CHILD))) {
                assertTrue(rows.next());
                assertEquals(10L, rows.getLong("ID"));
                assertEquals(1L, rows.getLong("PARENT_ID"));
                assertEquals("C1", rows.getString("CODE"));
                assertEquals("A", rows.getString("STATUS"));
                assertEquals(null, rows.getString("MOBILE_NO"));
                assertFalse(rows.next());
                dataPreserved = true;
            }

            writeSummary(outputDir, config, serverVersion, databaseName, loginName,
                    initialColumnChanges, initialObjectChanges, statementsExecuted, residualChanges,
                    createGenerated, dataPreserved);
        } finally {
            if (config.cleanup() && config.enabled()) {
                try (Connection cleanup = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                    cleanup.setAutoCommit(true);
                    resetPilotTables(cleanup, config.schema());
                }
            }
        }

        System.out.println("============================================================");
        System.out.println("SQL Server M2 live pilot");
        System.out.println("Server               : " + serverVersion);
        System.out.println("Database             : " + databaseName);
        System.out.println("Pilot schema         : " + config.schema());
        System.out.println("Login                : " + loginName);
        System.out.println("CREATE generated     : " + createGenerated);
        System.out.println("Column changes       : " + initialColumnChanges);
        System.out.println("Object changes       : " + initialObjectChanges);
        System.out.println("Statements executed  : " + statementsExecuted);
        System.out.println("Residual changes     : " + residualChanges);
        System.out.println("Data preserved       : " + dataPreserved);
        System.out.println("Cleanup              : " + config.cleanup());
        System.out.println("Artifacts            : " + outputDir);
        System.out.println("============================================================");
    }

    private JdbcSqlServerMetadataRepository repository(Config config) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(config.url(), config.user(), config.password());
        return new JdbcSqlServerMetadataRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    private static void ensureSchema(Connection connection, String schema) throws Exception {
        String sql = "IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = N'" + sqlLiteral(schema)
                + "') EXEC(N'CREATE SCHEMA " + q(schema) + " AUTHORIZATION [dbo]')";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void resetPilotTables(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + q(schema) + "." + q(CHILD));
            statement.execute("DROP TABLE IF EXISTS " + q(schema) + "." + q(PARENT));
        }
    }

    private static void createOldLiveState(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE %s.%s (
                      ID BIGINT NOT NULL,
                      CODE VARCHAR(20) NULL,
                      CONSTRAINT PK_SF_M2_PARENT PRIMARY KEY (ID)
                    )
                    """.formatted(q(schema), q(PARENT)));
            statement.execute("""
                    CREATE TABLE %s.%s (
                      ID BIGINT NOT NULL,
                      PARENT_ID BIGINT NULL,
                      CODE VARCHAR(20) NULL,
                      STATUS VARCHAR(1) NULL,
                      LEGACY_NOTE VARCHAR(20) NULL,
                      CONSTRAINT PK_SF_M2_CHILD PRIMARY KEY (ID),
                      CONSTRAINT UK_SF_M2_CHILD_CODE UNIQUE (CODE),
                      CONSTRAINT CHK_SF_M2_CHILD_STATUS CHECK (ID > 0),
                      CONSTRAINT FK_SF_M2_CHILD_PARENT FOREIGN KEY (PARENT_ID)
                        REFERENCES %s.%s (ID)
                    )
                    """.formatted(q(schema), q(CHILD), q(schema), q(PARENT)));
            statement.execute("CREATE INDEX IX_SF_M2_CHILD_PARENT ON "
                    + q(schema) + "." + q(CHILD) + " (PARENT_ID ASC)");
            statement.execute("CREATE INDEX IX_SF_M2_CHILD_STATUS ON "
                    + q(schema) + "." + q(CHILD) + " (STATUS ASC)");
            statement.execute("INSERT INTO " + q(schema) + "." + q(PARENT) + " (ID,CODE) VALUES (1,'P1')");
            statement.execute("INSERT INTO " + q(schema) + "." + q(CHILD)
                    + " (ID,PARENT_ID,CODE,STATUS,LEGACY_NOTE) VALUES (10,1,'C1','A','legacy')");
        }
    }

    private static Table desiredParent(String schema) {
        return Table.builder(schema, PARENT)
                .addColumn(column("ID", DataType.simple("BIGINT"), false, null, 1))
                .addColumn(column("CODE", DataType.varchar("VARCHAR", 20), true, null, 2))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_SF_M2_PARENT"), List.of(Identifier.of("ID")), false, false))
                .build();
    }

    private static Table desiredChild(String schema) {
        return Table.builder(schema, CHILD)
                .addColumn(column("ID", DataType.simple("BIGINT"), false, null, 1))
                .addColumn(column("PARENT_ID", DataType.simple("BIGINT"), false, null, 2))
                .addColumn(column("CODE", DataType.varchar("VARCHAR", 40), false, "'NEW'", 3))
                .addColumn(column("STATUS", DataType.varchar("VARCHAR", 1), true, null, 4))
                .addColumn(column("MOBILE_NO", DataType.varchar("VARCHAR", 30), true, null, 5))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_SF_M2_CHILD"),
                        List.of(Identifier.of("ID"), Identifier.of("PARENT_ID")), false, false))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_SF_M2_CHILD_CODE"),
                        List.of(Identifier.of("CODE"), Identifier.of("STATUS")), false, false))
                .addCheck(new CheckConstraint(
                        Identifier.of("CHK_SF_M2_CHILD_STATUS"),
                        "(ID > 0) AND (PARENT_ID > 0)"))
                .addIndex(index("IX_SF_M2_CHILD_PARENT", "PARENT_ID"))
                .addIndex(index("IX_SF_M2_CHILD_CODE", "CODE"))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_SF_M2_CHILD_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of(schema, PARENT),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.CASCADE,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private static Index index(String name, String column) {
        return new Index(
                Identifier.of(name),
                List.of(new IndexColumn(Identifier.of(column), SortDirection.ASC)),
                IndexType.NORMAL,
                Description.empty());
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultValue, int position) {
        return new Column(
                Identifier.of(name), type, nullable, new DefaultValue(defaultValue),
                Description.empty(), false, position);
    }

    private static String querySingle(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), "query returned no rows: " + sql);
            String value = rows.getString(1);
            assertNotNull(value, "query returned NULL: " + sql);
            return value.trim();
        }
    }

    private static boolean commentOnly(String sql) {
        String withoutLineComments = sql.replaceAll("(?m)^\\s*--.*$", "").trim();
        return withoutLineComments.isEmpty();
    }

    private static String describe(TableMigrationPlan plan) {
        StringBuilder value = new StringBuilder();
        plan.columnChanges().forEach(change -> value.append("COLUMN ")
                .append(change.kind()).append(' ').append(change.columnName()).append(" : ")
                .append(change.rationale()).append(System.lineSeparator()));
        plan.objectChanges().forEach(change -> {
            value.append("OBJECT ")
                    .append(change.kind()).append(' ').append(change.objectType()).append(' ')
                    .append(change.objectName()).append(" : ").append(change.rationale())
                    .append(System.lineSeparator());
            if (change.before() instanceof CheckConstraint before) {
                value.append("  live-check    : ").append(before.expression()).append(System.lineSeparator());
            }
            if (change.after() instanceof CheckConstraint after) {
                value.append("  desired-check : ").append(after.expression()).append(System.lineSeparator());
            }
        });
        return value.toString();
    }

    private static void writeSummary(
            Path outputDir, Config config, String serverVersion, String databaseName, String loginName,
            int columnChanges, int objectChanges, int statementsExecuted, int residualChanges,
            boolean createGenerated, boolean dataPreserved) throws Exception {
        String summary = """
                SchemaForge SQL Server ALTER/Migration M2 live pilot
                ====================================================
                Server              : %s
                Database            : %s
                Pilot schema        : %s
                Login               : %s
                CREATE generated    : %s
                Column changes      : %d
                Object changes      : %d
                Statements executed : %d
                Residual changes    : %d
                Data preserved      : %s
                Cleanup             : %s
                """.formatted(serverVersion, databaseName, config.schema(), loginName, createGenerated,
                columnChanges, objectChanges, statementsExecuted, residualChanges,
                dataPreserved, config.cleanup());
        Files.writeString(outputDir.resolve("sqlserver-m2-live-pilot-summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private static String q(String identifier) {
        return "[" + identifier.replace("]", "]]" ) + "]";
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static boolean isSystemDatabase(String databaseName) {
        String value = databaseName.toLowerCase(Locale.ROOT);
        return value.equals("master") || value.equals("model") || value.equals("msdb") || value.equals("tempdb");
    }

    private record Config(
            String url,
            String user,
            String password,
            String schema,
            boolean confirmDestructive,
            boolean cleanup,
            int statementTimeoutSeconds,
            String outputDir) {
        static Config fromSystemProperties() {
            String password = firstProperty(
                    "schemaforge.sqlserver.migration.jdbc.password", "sqlserver.jdbc.password");
            if (password.isBlank()) password = System.getenv("SQLSERVER_JDBC_PASSWORD");
            String user = firstProperty(
                    "schemaforge.sqlserver.migration.jdbc.user", "sqlserver.jdbc.user");
            String url = firstProperty(
                    "schemaforge.sqlserver.migration.jdbc.url", "sqlserver.jdbc.url");
            return new Config(
                    url,
                    user,
                    password == null ? "" : password.trim(),
                    property("schemaforge.sqlserver.migration.schema", "TSTSHMA"),
                    Boolean.parseBoolean(property("schemaforge.sqlserver.migration.confirmDestructive", "false")),
                    Boolean.parseBoolean(property("schemaforge.sqlserver.migration.cleanup", "true")),
                    Integer.parseInt(property("schemaforge.sqlserver.migration.statementTimeoutSeconds", "30")),
                    property("schemaforge.sqlserver.migration.outputDir", "target/sqlserver-migration-m2-live-pilot"));
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank();
        }

        void validate() {
            assertNotNull(schema);
            if (!confirmDestructive) {
                throw new IllegalStateException(
                        "SQL Server M2 live pilot requires schemaforge.sqlserver.migration.confirmDestructive=true");
            }
            if (!url.startsWith("jdbc:sqlserver:")) {
                throw new IllegalArgumentException("SQL Server pilot URL must start with jdbc:sqlserver:");
            }
            if (!schema.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
                throw new IllegalArgumentException("invalid pilot schema identifier: " + schema);
            }
            String lower = schema.toLowerCase(Locale.ROOT);
            if (lower.equals("sys") || lower.equals("information_schema") || lower.equals("dbo") || lower.equals("guest")) {
                throw new IllegalStateException("refusing destructive SQL Server pilot in protected schema " + schema);
            }
            if (statementTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("statement timeout must be positive");
            }
        }

        private static String firstProperty(String primary, String fallbackName) {
            String primaryValue = System.getProperty(primary);
            if (primaryValue != null && !primaryValue.isBlank()) return primaryValue.trim();
            String fallbackValue = System.getProperty(fallbackName);
            return fallbackValue == null ? "" : fallbackValue.trim();
        }

        private static String property(String name, String fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
