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
import com.behsazan.schemaforge.metadata.repository.JdbcMySqlMetadataRepository;
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

/**
 * Real MySQL 8.x pilot for ALTER/Migration M2.
 *
 * <p>The test uses a dedicated disposable database whose name must start with
 * {@code SCHEMAFORGE_}. It proves that full CREATE SQL is still generated while
 * the table already exists, then renders and executes a confirmed migration that
 * covers column changes plus PK/FK/UK/CHECK/INDEX replacement.</p>
 */
class MySqlMigrationM2LivePilotIT {
    private static final String DEFAULT_DATABASE = "SCHEMAFORGE_M2_PILOT";
    private static final String PARENT = "SF_M2_PARENT";
    private static final String CHILD = "SF_M2_CHILD";

    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void executesRealM2MigrationAndPreservesCreateOutput() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "MySQL M2 live pilot disabled; provide schemaforge.mysql.migration.jdbc.url and jdbc.user");
        config.validate();

        Class.forName("com.mysql.cj.jdbc.Driver");
        DriverManager.setLoginTimeout(15);
        Path outputDir = Path.of(config.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        String serverVersion = "unknown";
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
            assertTrue(meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql"),
                    "pilot must run against real MySQL");

            resetPilotDatabase(connection, config.database());
            createOldLiveState(connection, config.database());

            JdbcMySqlMetadataRepository repository = repository(config);
            Table desiredParent = desiredParent(config.database());
            Table desiredChild = desiredChild(config.database());

            // CREATE output must remain independent from live-table existence.
            DatabaseSchema desiredSchema = DatabaseSchema.builder(config.database())
                    .addTable(desiredParent)
                    .addTable(desiredChild)
                    .build();
            String createSql = new DdlGenerator(DialectFactory.create(DatabasePlatform.MYSQL)).generate(desiredSchema);
            Path createFile = outputDir.resolve("mysql-m2-pilot-create-reference.mysql.sql");
            Files.writeString(createFile, createSql, StandardCharsets.UTF_8);
            String expectedCreate = "CREATE TABLE `" + config.database() + "`.`" + CHILD + "`";
            createGenerated = createSql.contains(expectedCreate);
            assertTrue(createGenerated,
                    "full CREATE TABLE output must be generated even when the live table already exists");

            Table liveBefore = repository.findTable(config.database(), CHILD).orElseThrow();
            TableMigrationPlan initialPlan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, liveBefore, desiredChild);
            initialColumnChanges = initialPlan.columnChanges().size();
            initialObjectChanges = initialPlan.objectChanges().size();
            assertTrue(initialColumnChanges >= 5, "pilot must exercise multiple column changes");
            assertTrue(initialObjectChanges >= 5, "pilot must exercise PK/FK/UK/CHECK/INDEX changes");

            MigrationGenerationService service = new MigrationGenerationService();
            MigrationArtifact safe = service.generate(
                    DatabasePlatform.MYSQL, liveBefore, desiredChild, MigrationRenderOptions.safeDefaults());
            assertTrue(safe.sql().contains("-- BLOCKED:"),
                    "safe render must comment destructive migration SQL");
            Files.writeString(outputDir.resolve("SAFE__" + safe.fileName()), safe.sql(), StandardCharsets.UTF_8);

            MigrationArtifact confirmed = service.generate(
                    DatabasePlatform.MYSQL, liveBefore, desiredChild, new MigrationRenderOptions(true));
            assertFalse(confirmed.sql().contains("-- BLOCKED: destructive"),
                    "confirmed render must enable explicitly approved destructive SQL");
            Path migrationFile = outputDir.resolve(confirmed.fileName());
            Files.writeString(migrationFile, confirmed.sql(), StandardCharsets.UTF_8);

            for (String sql : splitter.parse(confirmed.sql(), DatabasePlatform.MYSQL)) {
                if (sql == null || sql.isBlank() || commentOnly(sql)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(config.statementTimeoutSeconds());
                    statement.execute(sql);
                    statementsExecuted++;
                }
            }

            Table liveAfter = repository.findTable(config.database(), CHILD).orElseThrow();
            TableMigrationPlan afterPlan = new SchemaDiffEngine().diff(DatabasePlatform.MYSQL, liveAfter, desiredChild);
            residualChanges = afterPlan.columnChanges().size() + afterPlan.objectChanges().size();
            if (!afterPlan.empty()) {
                Files.writeString(outputDir.resolve("residual-diff.txt"), describe(afterPlan), StandardCharsets.UTF_8);
            }
            assertTrue(afterPlan.empty(),
                    "post-migration live metadata must match desired M2 model; see residual-diff.txt if present");

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT ID,PARENT_ID,CODE,STATUS,MOBILE_NO FROM `" + config.database() + "`.`" + CHILD + "`")) {
                assertTrue(rows.next());
                assertEquals(10L, rows.getLong("ID"));
                assertEquals(1L, rows.getLong("PARENT_ID"));
                assertEquals("C1", rows.getString("CODE"));
                assertEquals("A", rows.getString("STATUS"));
                assertEquals(null, rows.getString("MOBILE_NO"));
                assertFalse(rows.next());
                dataPreserved = true;
            }

            writeSummary(outputDir, config, serverVersion, initialColumnChanges, initialObjectChanges,
                    statementsExecuted, residualChanges, createGenerated, dataPreserved);
        } finally {
            if (config.cleanup()) {
                try (Connection cleanup = DriverManager.getConnection(config.url(), config.user(), config.password())) {
                    cleanup.setAutoCommit(true);
                    dropPilotDatabase(cleanup, config.database());
                }
            }
        }

        System.out.println("============================================================");
        System.out.println("MySQL M2 live pilot");
        System.out.println("Server               : " + serverVersion);
        System.out.println("Pilot database       : " + config.database());
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

    private JdbcMySqlMetadataRepository repository(Config config) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(config.url(), config.user(), config.password());
        return new JdbcMySqlMetadataRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    private static void resetPilotDatabase(Connection connection, String database) throws Exception {
        dropPilotDatabase(connection, database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static void dropPilotDatabase(Connection connection, String database) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private static void createOldLiveState(Connection connection, String database) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE `%s`.`%s` (
                      `ID` BIGINT NOT NULL,
                      `CODE` VARCHAR(20) NULL,
                      CONSTRAINT `PK_SF_M2_PARENT` PRIMARY KEY (`ID`)
                    ) ENGINE=InnoDB
                    """.formatted(database, PARENT));
            statement.execute("""
                    CREATE TABLE `%s`.`%s` (
                      `ID` BIGINT NOT NULL,
                      `PARENT_ID` BIGINT NULL,
                      `CODE` VARCHAR(20) NULL,
                      `STATUS` VARCHAR(1) NULL,
                      `LEGACY_NOTE` VARCHAR(20) NULL,
                      CONSTRAINT `PK_SF_M2_CHILD` PRIMARY KEY (`ID`),
                      CONSTRAINT `UK_SF_M2_CHILD_CODE` UNIQUE (`CODE`),
                      CONSTRAINT `CK_SF_M2_CHILD_STATUS` CHECK (`STATUS` IN ('A','I')),
                      INDEX `IX_SF_M2_CHILD_PARENT` (`PARENT_ID` ASC),
                      INDEX `IX_SF_M2_CHILD_STATUS` (`STATUS` ASC),
                      CONSTRAINT `FK_SF_M2_CHILD_PARENT` FOREIGN KEY (`PARENT_ID`)
                        REFERENCES `%s`.`%s` (`ID`) ON DELETE NO ACTION ON UPDATE NO ACTION
                    ) ENGINE=InnoDB
                    """.formatted(database, CHILD, database, PARENT));
            statement.execute("INSERT INTO `" + database + "`.`" + PARENT + "` (`ID`,`CODE`) VALUES (1,'P1')");
            statement.execute("INSERT INTO `" + database + "`.`" + CHILD
                    + "` (`ID`,`PARENT_ID`,`CODE`,`STATUS`,`LEGACY_NOTE`) VALUES (10,1,'C1','A','legacy')");
        }
    }

    private static Table desiredParent(String database) {
        return Table.builder(database, PARENT)
                .addColumn(column("ID", DataType.simple("BIGINT"), false, null, 1))
                .addColumn(column("CODE", DataType.varchar("VARCHAR", 20), true, null, 2))
                .primaryKey(new PrimaryKey(Identifier.of("PK_SF_M2_PARENT"), List.of(Identifier.of("ID"))))
                .build();
    }

    private static Table desiredChild(String database) {
        return Table.builder(database, CHILD)
                .addColumn(column("ID", DataType.simple("BIGINT"), false, null, 1))
                .addColumn(column("PARENT_ID", DataType.simple("BIGINT"), false, null, 2))
                .addColumn(column("CODE", DataType.varchar("VARCHAR", 40), false, "'NEW'", 3))
                .addColumn(column("STATUS", DataType.varchar("VARCHAR", 1), true, null, 4))
                .addColumn(column("MOBILE_NO", DataType.varchar("VARCHAR", 30), true, null, 5))
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_SF_M2_CHILD"),
                        List.of(Identifier.of("ID"), Identifier.of("PARENT_ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_SF_M2_CHILD_CODE"),
                        List.of(Identifier.of("CODE"), Identifier.of("STATUS"))))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_SF_M2_CHILD_STATUS"),
                        "STATUS IN ('A','I','S')"))
                .addIndex(new Index(
                        Identifier.of("IX_SF_M2_CHILD_PARENT"),
                        List.of(new IndexColumn(Identifier.of("PARENT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addIndex(new Index(
                        Identifier.of("IX_SF_M2_CHILD_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_SF_M2_CHILD_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of(database, PARENT),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.CASCADE,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private static Column column(String name, DataType type, boolean nullable, String defaultValue, int position) {
        return new Column(
                Identifier.of(name), type, nullable, new DefaultValue(defaultValue),
                Description.empty(), false, position);
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
        plan.objectChanges().forEach(change -> value.append("OBJECT ")
                .append(change.kind()).append(' ').append(change.objectType()).append(' ')
                .append(change.objectName()).append(" : ").append(change.rationale())
                .append(System.lineSeparator()));
        return value.toString();
    }

    private static void writeSummary(
            Path outputDir, Config config, String serverVersion,
            int columnChanges, int objectChanges, int statementsExecuted, int residualChanges,
            boolean createGenerated, boolean dataPreserved) throws Exception {
        String summary = """
                SchemaForge MySQL ALTER/Migration M2 live pilot
                ================================================
                Server              : %s
                Pilot database      : %s
                CREATE generated    : %s
                Column changes      : %d
                Object changes      : %d
                Statements executed : %d
                Residual changes    : %d
                Data preserved      : %s
                Cleanup             : %s
                """.formatted(serverVersion, config.database(), createGenerated, columnChanges,
                objectChanges, statementsExecuted, residualChanges, dataPreserved, config.cleanup());
        Files.writeString(outputDir.resolve("mysql-m2-live-pilot-summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private record Config(
            String url,
            String user,
            String password,
            String database,
            boolean confirmDestructive,
            boolean cleanup,
            int statementTimeoutSeconds,
            String outputDir) {
        static Config fromSystemProperties() {
            String password = System.getProperty("schemaforge.mysql.migration.jdbc.password");
            if (password == null || password.isBlank()) password = System.getenv("MYSQL_JDBC_PASSWORD");
            return new Config(
                    property("schemaforge.mysql.migration.jdbc.url", ""),
                    property("schemaforge.mysql.migration.jdbc.user", ""),
                    password == null ? "" : password,
                    property("schemaforge.mysql.migration.database", DEFAULT_DATABASE),
                    Boolean.parseBoolean(property("schemaforge.mysql.migration.confirmDestructive", "false")),
                    Boolean.parseBoolean(property("schemaforge.mysql.migration.cleanup", "true")),
                    Integer.parseInt(property("schemaforge.mysql.migration.statementTimeoutSeconds", "30")),
                    property("schemaforge.mysql.migration.outputDir", "target/mysql-migration-m2-live-pilot"));
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank();
        }

        void validate() {
            assertNotNull(database);
            if (!confirmDestructive) {
                throw new IllegalStateException(
                        "MySQL M2 live pilot requires schemaforge.mysql.migration.confirmDestructive=true");
            }
            if (!database.matches("[A-Za-z][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("invalid pilot database identifier: " + database);
            }
            if (!database.toUpperCase(Locale.ROOT).startsWith("SCHEMAFORGE_")) {
                throw new IllegalStateException(
                        "refusing destructive pilot outside a dedicated SCHEMAFORGE_* database: " + database);
            }
            if (statementTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("statement timeout must be positive");
            }
        }

        private static String property(String name, String fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
