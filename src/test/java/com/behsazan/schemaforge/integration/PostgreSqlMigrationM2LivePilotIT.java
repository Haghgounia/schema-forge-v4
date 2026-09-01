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
import com.behsazan.schemaforge.metadata.repository.JdbcPostgreSqlMetadataRepository;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL pilot for ALTER/Migration M2. */
class PostgreSqlMigrationM2LivePilotIT {
    private static final String PARENT = "SF_M2_PARENT";
    private static final String CHILD = "SF_M2_CHILD";

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesRealM2MigrationAndPreservesCreateOutput() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "PostgreSQL M2 live pilot disabled; provide schemaforge.postgresql.migration.jdbc.url and jdbc.user");
        config.validate();

        Class.forName("org.postgresql.Driver");
        DriverManager.setLoginTimeout(15);
        Path outputDir = Path.of(config.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        String serverVersion = "unknown";
        String databaseName = "unknown";
        String schemaOwner = "unknown";
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
            assertTrue(meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql"),
                    "pilot must run against real PostgreSQL");

            databaseName = querySingle(connection, "SELECT current_database()");
            String currentUser = querySingle(connection, "SELECT current_user");
            schemaOwner = querySingle(connection,
                    "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '" + config.schema() + "'");
            assertEquals(currentUser.toLowerCase(Locale.ROOT), schemaOwner.toLowerCase(Locale.ROOT),
                    "pilot schema must be owned by the connected PostgreSQL user for destructive safety");

            resetPilotTables(connection, config.schema());
            createOldLiveState(connection, config.schema());

            JdbcPostgreSqlMetadataRepository repository = repository(config);
            Table desiredParent = desiredParent(config.schema());
            Table desiredChild = desiredChild(config.schema());

            DatabaseSchema desiredSchema = DatabaseSchema.builder(config.schema())
                    .addTable(desiredParent)
                    .addTable(desiredChild)
                    .build();
            String createSql = new DdlGenerator(DialectFactory.create(DatabasePlatform.POSTGRESQL)).generate(desiredSchema);
            Path createFile = outputDir.resolve("postgresql-m2-pilot-create-reference.postgresql.sql");
            Files.writeString(createFile, createSql, StandardCharsets.UTF_8);
            String expectedCreate = ("CREATE TABLE " + config.schema() + "." + CHILD)
                    .toLowerCase(Locale.ROOT);
            createGenerated = createSql.toLowerCase(Locale.ROOT).contains(expectedCreate);
            assertTrue(createGenerated,
                    "full CREATE TABLE output must be generated even when the live table already exists");

            Table liveBefore = repository.findTable(config.schema(), CHILD).orElseThrow();
            TableMigrationPlan initialPlan = new SchemaDiffEngine().diff(
                    DatabasePlatform.POSTGRESQL, liveBefore, desiredChild);
            initialColumnChanges = initialPlan.columnChanges().size();
            initialObjectChanges = initialPlan.objectChanges().size();
            assertTrue(initialColumnChanges >= 5, "pilot must exercise multiple column changes");
            assertTrue(initialObjectChanges >= 5, "pilot must exercise PK/FK/UK/CHECK/INDEX changes");

            MigrationGenerationService service = new MigrationGenerationService();
            MigrationArtifact safe = service.generate(
                    DatabasePlatform.POSTGRESQL, liveBefore, desiredChild, MigrationRenderOptions.safeDefaults());
            assertTrue(safe.sql().contains("-- BLOCKED:"),
                    "safe render must comment destructive migration SQL");
            Files.writeString(outputDir.resolve("SAFE__" + safe.fileName()), safe.sql(), StandardCharsets.UTF_8);

            MigrationArtifact confirmed = service.generate(
                    DatabasePlatform.POSTGRESQL, liveBefore, desiredChild, new MigrationRenderOptions(true));
            assertFalse(confirmed.sql().contains("-- BLOCKED: destructive"),
                    "confirmed render must enable explicitly approved destructive SQL");
            Files.writeString(outputDir.resolve(confirmed.fileName()), confirmed.sql(), StandardCharsets.UTF_8);

            for (String sql : splitter.parse(confirmed.sql(), DatabasePlatform.POSTGRESQL)) {
                if (sql == null || sql.isBlank() || commentOnly(sql)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(config.statementTimeoutSeconds());
                    statement.execute(sql);
                    statementsExecuted++;
                }
            }

            Table liveAfter = repository.findTable(config.schema(), CHILD).orElseThrow();
            TableMigrationPlan afterPlan = new SchemaDiffEngine().diff(
                    DatabasePlatform.POSTGRESQL, liveAfter, desiredChild);
            residualChanges = afterPlan.columnChanges().size() + afterPlan.objectChanges().size();
            if (!afterPlan.empty()) {
                Files.writeString(outputDir.resolve("residual-diff.txt"), describe(afterPlan), StandardCharsets.UTF_8);
            }
            assertTrue(afterPlan.empty(),
                    "post-migration PostgreSQL metadata must match desired M2 model; see residual-diff.txt if present");

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT id,parent_id,code,status,mobile_no FROM " + config.schema() + "." + CHILD)) {
                assertTrue(rows.next());
                assertEquals(10L, rows.getLong("id"));
                assertEquals(1L, rows.getLong("parent_id"));
                assertEquals("C1", rows.getString("code"));
                assertEquals("A", rows.getString("status"));
                assertEquals(null, rows.getString("mobile_no"));
                assertFalse(rows.next());
                dataPreserved = true;
            }

            writeSummary(outputDir, config, serverVersion, databaseName, schemaOwner,
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
        System.out.println("PostgreSQL M2 live pilot");
        System.out.println("Server               : " + serverVersion);
        System.out.println("Database             : " + databaseName);
        System.out.println("Pilot schema         : " + config.schema());
        System.out.println("Schema owner         : " + schemaOwner);
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

    private JdbcPostgreSqlMetadataRepository repository(Config config) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(config.url(), config.user(), config.password());
        return new JdbcPostgreSqlMetadataRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    private static void resetPilotTables(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + schema + "." + CHILD + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS " + schema + "." + PARENT + " CASCADE");
        }
    }

    private static void createOldLiveState(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE %s.%s (
                      ID BIGINT NOT NULL,
                      CODE VARCHAR(20),
                      CONSTRAINT PK_SF_M2_PARENT PRIMARY KEY (ID)
                    )
                    """.formatted(schema, PARENT));
            statement.execute("""
                    CREATE TABLE %s.%s (
                      ID BIGINT NOT NULL,
                      PARENT_ID BIGINT,
                      CODE VARCHAR(20),
                      STATUS VARCHAR(1),
                      LEGACY_NOTE VARCHAR(20),
                      CONSTRAINT PK_SF_M2_CHILD PRIMARY KEY (ID),
                      CONSTRAINT UK_SF_M2_CHILD_CODE UNIQUE (CODE),
                      CONSTRAINT CHK_SF_M2_CHILD_STATUS CHECK (ID > 0),
                      CONSTRAINT FK_SF_M2_CHILD_PARENT FOREIGN KEY (PARENT_ID)
                        REFERENCES %s.%s (ID)
                    )
                    """.formatted(schema, CHILD, schema, PARENT));
            statement.execute("CREATE INDEX IX_SF_M2_CHILD_PARENT ON "
                    + schema + "." + CHILD + " (PARENT_ID ASC)");
            statement.execute("CREATE INDEX IX_SF_M2_CHILD_STATUS ON "
                    + schema + "." + CHILD + " (STATUS ASC)");
            statement.execute("INSERT INTO " + schema + "." + PARENT + " (ID,CODE) VALUES (1,'P1')");
            statement.execute("INSERT INTO " + schema + "." + CHILD
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
            Path outputDir, Config config, String serverVersion, String databaseName, String schemaOwner,
            int columnChanges, int objectChanges, int statementsExecuted, int residualChanges,
            boolean createGenerated, boolean dataPreserved) throws Exception {
        String summary = """
                SchemaForge PostgreSQL ALTER/Migration M2 live pilot
                ====================================================
                Server              : %s
                Database            : %s
                Pilot schema        : %s
                Schema owner        : %s
                CREATE generated    : %s
                Column changes      : %d
                Object changes      : %d
                Statements executed : %d
                Residual changes    : %d
                Data preserved      : %s
                Cleanup             : %s
                """.formatted(serverVersion, databaseName, config.schema(), schemaOwner, createGenerated,
                columnChanges, objectChanges, statementsExecuted, residualChanges,
                dataPreserved, config.cleanup());
        Files.writeString(outputDir.resolve("postgresql-m2-live-pilot-summary.txt"), summary, StandardCharsets.UTF_8);
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
                    "schemaforge.postgresql.migration.jdbc.password", "postgresql.jdbc.password");
            if (password.isBlank()) password = System.getenv("POSTGRESQL_JDBC_PASSWORD");
            String user = firstProperty(
                    "schemaforge.postgresql.migration.jdbc.user", "postgresql.jdbc.user");
            String url = firstProperty(
                    "schemaforge.postgresql.migration.jdbc.url", "postgresql.jdbc.url");
            return new Config(
                    url,
                    user,
                    password == null ? "" : password.trim(),
                    property("schemaforge.postgresql.migration.schema", "tstshma").toLowerCase(Locale.ROOT),
                    Boolean.parseBoolean(property("schemaforge.postgresql.migration.confirmDestructive", "false")),
                    Boolean.parseBoolean(property("schemaforge.postgresql.migration.cleanup", "true")),
                    Integer.parseInt(property("schemaforge.postgresql.migration.statementTimeoutSeconds", "30")),
                    property("schemaforge.postgresql.migration.outputDir", "target/postgresql-migration-m2-live-pilot"));
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank();
        }

        void validate() {
            assertNotNull(schema);
            if (!confirmDestructive) {
                throw new IllegalStateException(
                        "PostgreSQL M2 live pilot requires schemaforge.postgresql.migration.confirmDestructive=true");
            }
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("PostgreSQL pilot URL must start with jdbc:postgresql:");
            }
            if (!schema.matches("[a-z_][a-z0-9_$]*")) {
                throw new IllegalArgumentException("invalid pilot schema identifier: " + schema);
            }
            if (schema.equals("pg_catalog") || schema.equals("information_schema") || schema.startsWith("pg_toast")) {
                throw new IllegalStateException("refusing destructive PostgreSQL pilot in system schema " + schema);
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
