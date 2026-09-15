package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceFinding;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import com.behsazan.schemaforge.metadata.repository.Db2LuwMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.JdbcDb2LuwMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB2LUW-P11 live qualification for read-only auditing of Db2 LUW objects created outside SchemaForge.
 *
 * <p>The fixture schema and tables are created directly through JDBC, deliberately bypassing
 * SchemaForge DDL generation. The production {@link JdbcDb2LuwMetadataRepository} and
 * {@link SchemaConformanceAuditService} then audit one table and the whole isolated schema.
 * The audit path itself is read-only.</p>
 */
class Db2LuwExternalDatabaseAuditP11IT {
    private static final String URL = "schemaforge.db2luw.p11.jdbc.url";
    private static final String USER = "schemaforge.db2luw.p11.jdbc.user";
    private static final String PASSWORD = "schemaforge.db2luw.p11.jdbc.password";
    private static final String DRIVER = "schemaforge.db2luw.p11.jdbc.driver";
    private static final String SCHEMA = "schemaforge.db2luw.p11.audit.schema";
    private static final String OUTPUT_DIR = "schemaforge.db2luw.p11.audit.outputDir";
    private static final String CONFIRM_DESTRUCTIVE = "schemaforge.db2luw.p11.audit.confirmDestructive";
    private static final String CLEANUP = "schemaforge.db2luw.p11.audit.cleanup";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    @Test
    void auditsExternallyCreatedDb2LuwTableAndSchemaWithoutMutation() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "DB2LUW-P11 live audit disabled; provide " + URL + " and " + USER);
        config.validate();

        Class.forName(config.driver());
        DriverManager.setLoginTimeout(15);

        Throwable failure = null;
        boolean cleanupSucceeded = false;
        SchemaConformanceReport childReport = null;
        SchemaConformanceReport schemaReport = null;
        Set<String> beforeTables = Set.of();
        Set<String> afterTables = Set.of();
        int liveForeignKeys = -1;
        ServerInfo serverInfo = null;

        try {
            serverInfo = verifyDb2LuwServer(config);
            createExternalFixture(config);

            // Db2 LUW can be comparatively expensive to establish repeatedly through JCC,
            // especially in a local container.  DriverManagerDataSource would open a new physical
            // connection for every JdbcTemplate operation, which makes this read-only qualification
            // sensitive to connection-handshake timeouts rather than metadata correctness.  Keep the
            // audit phase on one explicitly owned connection; suppressClose lets JdbcTemplate release
            // logical handles without closing the physical connection between catalog queries.
            try (Connection auditConnection = DriverManager.getConnection(
                    config.url(), config.user(), config.password())) {
                auditConnection.setAutoCommit(true);
                SingleConnectionDataSource dataSource = new SingleConnectionDataSource(auditConnection, true);
                Db2LuwMetadataRepository db2Repository = new JdbcDb2LuwMetadataRepository(
                        new NamedParameterJdbcTemplate(dataSource));
                MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
                when(resolver.resolve(DatabasePlatform.DB2_LUW)).thenReturn(db2Repository);
                SchemaConformanceAuditService service = new SchemaConformanceAuditService(resolver);

                beforeTables = normalized(db2Repository.findTableNames(config.schema()));
                assertEquals(Set.of("DBP11_CHILD", "DBP11_NO_PK", "DBP11_PARENT"), beforeTables,
                        "Fixture schema must contain only the three direct-JDBC tables");

                var child = db2Repository.findTable(config.schema(), "DBP11_CHILD").orElseThrow();
                liveForeignKeys = child.foreignKeys().size();
                assertEquals(1, liveForeignKeys, "Valid direct-JDBC FK must be visible through Db2 LUW metadata");
                assertEquals("FK_DBP11_CHILD_PARENT", child.foreignKeys().getFirst().name().normalized());

                childReport = service.auditTable(DatabasePlatform.DB2_LUW, config.schema(), "DBP11_CHILD");
                assertEquals(SchemaConformanceScope.TABLE, childReport.scope());
                assertEquals(1, childReport.summary().tablesScanned());
                assertEquals(SchemaConformanceAuditService.TABLE_RULE_FAMILIES, childReport.ruleFamilies());
                assertFalse(hasCode(childReport, "FK_REFERENCED_COLUMN_NOT_FOUND"));
                assertFalse(hasCode(childReport, "FK_TARGET_NOT_UNIQUE"));

                schemaReport = service.auditSchema(DatabasePlatform.DB2_LUW, config.schema());
                assertEquals(SchemaConformanceScope.SCHEMA, schemaReport.scope());
                assertEquals(3, schemaReport.summary().tablesScanned());
                assertTrue(hasCode(schemaReport, "TABLE_PRIMARY_KEY_MISSING"),
                        "Audit must detect the external table without a primary key");
                assertTrue(schemaReport.ruleFamilies().contains(SchemaConformanceAuditService.DATATYPE_COMPATIBILITY),
                        "Audit must execute Db2 LUW datatype compatibility rules");
                assertFalse(hasCode(schemaReport, "FK_REFERENCED_COLUMN_NOT_FOUND"));
                assertFalse(hasCode(schemaReport, "FK_TARGET_NOT_UNIQUE"));

                afterTables = normalized(db2Repository.findTableNames(config.schema()));
                assertEquals(beforeTables, afterTables, "Read-only audit must not add/drop/rename tables");
                assertTrue(db2Repository.findTable(config.schema(), "DBP11_CHILD").orElseThrow()
                        .foreignKeys().stream()
                        .anyMatch(fk -> "FK_DBP11_CHILD_PARENT".equals(fk.name().normalized())),
                        "Read-only audit must not alter the valid FK");
            }
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            if (config.cleanup()) {
                try {
                    dropFixtureSchema(config);
                    cleanupSucceeded = true;
                } catch (Throwable cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            }
            writeEvidence(config, serverInfo, childReport, schemaReport, beforeTables, afterTables,
                    liveForeignKeys, cleanupSucceeded, failure);
        }
    }

    private static ServerInfo verifyDb2LuwServer(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName();
            String version = metadata.getDatabaseProductVersion();
            String normalized = product == null ? "" : product.toUpperCase(Locale.ROOT);
            assertTrue(normalized.contains("DB2"),
                    "DB2LUW-P11 must run against Db2; connected product=" + product);

            String serviceLevel;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO FETCH FIRST 1 ROW ONLY WITH UR")) {
                assertTrue(rs.next(), "Db2 LUW ENV_INST_INFO must expose a service level");
                serviceLevel = rs.getString(1);
            }
            assertTrue(serviceLevel != null && !serviceLevel.isBlank(),
                    "Db2 LUW service level must be available through SYSIBMADM.ENV_INST_INFO");
            return new ServerInfo(product, version, serviceLevel.trim());
        }
    }

    private static void createExternalFixture(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            dropFixtureSchema(connection, config.schema());
            String authorization = scalar(connection, "VALUES CURRENT USER");
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + q(config.schema())
                        + " AUTHORIZATION " + safeAuthId(authorization));
                statement.execute("CREATE TABLE " + qq(config.schema(), "DBP11_PARENT") + " ("
                        + q("ID") + " BIGINT NOT NULL, "
                        + q("CODE") + " VARCHAR(20) NOT NULL, "
                        + "CONSTRAINT " + q("PK_DBP11_PARENT") + " PRIMARY KEY (" + q("ID") + "), "
                        + "CONSTRAINT " + q("UK_DBP11_PARENT_CODE") + " UNIQUE (" + q("CODE") + "))");
                statement.execute("CREATE TABLE " + qq(config.schema(), "DBP11_CHILD") + " ("
                        + q("ID") + " BIGINT NOT NULL, "
                        + q("PARENT_ID") + " BIGINT NOT NULL, "
                        + q("NOTE") + " VARCHAR(30), "
                        + "CONSTRAINT " + q("PK_DBP11_CHILD") + " PRIMARY KEY (" + q("ID") + "), "
                        + "CONSTRAINT " + q("FK_DBP11_CHILD_PARENT") + " FOREIGN KEY (" + q("PARENT_ID") + ") "
                        + "REFERENCES " + qq(config.schema(), "DBP11_PARENT") + " (" + q("ID") + "))");
                statement.execute("CREATE INDEX " + qq(config.schema(), "IX_DBP11_CHILD_PARENT")
                        + " ON " + qq(config.schema(), "DBP11_CHILD") + " (" + q("PARENT_ID") + ")");
                statement.execute("CREATE TABLE " + qq(config.schema(), "DBP11_NO_PK") + " ("
                        + q("LEGACY_CODE") + " DECIMAL(18,0), "
                        + q("DESCRIPTION") + " VARCHAR(40))");
            }
        }
    }

    private static void dropFixtureSchema(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            dropFixtureSchema(connection, config.schema());
        }
    }

    private static void dropFixtureSchema(Connection connection, String schema) throws Exception {
        if (!schemaExists(connection, schema)) return;
        dropTableIfExists(connection, schema, "DBP11_CHILD");
        dropTableIfExists(connection, schema, "DBP11_NO_PK");
        dropTableIfExists(connection, schema, "DBP11_PARENT");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + q(schema) + " RESTRICT");
        }
    }

    private static void dropTableIfExists(Connection connection, String schema, String table) throws Exception {
        if (!tableExists(connection, schema, table)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + qq(schema, table));
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM SYSCAT.SCHEMATA WHERE UPPER(SCHEMANAME)=UPPER(?) WITH UR")) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM SYSCAT.TABLES "
                        + "WHERE TYPE IN ('T','U') AND UPPER(TABSCHEMA)=UPPER(?) AND UPPER(TABNAME)=UPPER(?) WITH UR")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("No scalar result for: " + sql);
            return rs.getString(1);
        }
    }

    private static void writeEvidence(
            Config config,
            ServerInfo serverInfo,
            SchemaConformanceReport childReport,
            SchemaConformanceReport schemaReport,
            Set<String> beforeTables,
            Set<String> afterTables,
            int liveForeignKeys,
            boolean cleanupSucceeded,
            Throwable failure) throws Exception {
        Files.createDirectories(config.outputDir());
        String stamp = LocalDateTime.now().format(TS);
        Path summary = config.outputDir().resolve("db2luw-p11-external-audit-summary_" + stamp + ".txt");
        Path findings = config.outputDir().resolve("db2luw-p11-external-audit-findings_" + stamp + ".csv");

        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge Db2 LUW DB2LUW-P11 external database audit qualification").append(nl);
        text.append("=======================================================================").append(nl);
        text.append("Fixture creation path       : DIRECT_JDBC_OUTSIDE_SCHEMAFORGE").append(nl);
        text.append("Audit service               : SchemaConformanceAuditService").append(nl);
        text.append("Database product            : ").append(serverInfo == null ? "" : serverInfo.product()).append(nl);
        text.append("Database version            : ").append(serverInfo == null ? "" : serverInfo.version()).append(nl);
        text.append("Db2 LUW service level       : ").append(serverInfo == null ? "" : serverInfo.serviceLevel()).append(nl);
        text.append("Target schema               : ").append(config.schema()).append(nl);
        text.append("Tables before audit         : ").append(beforeTables.size()).append(nl);
        text.append("Tables after audit          : ").append(afterTables.size()).append(nl);
        text.append("Table set unchanged         : ").append(beforeTables.equals(afterTables)).append(nl);
        text.append("Live valid foreign keys     : ").append(liveForeignKeys).append(nl);
        text.append("Child audit executed        : ").append(childReport != null).append(nl);
        text.append("Schema audit executed       : ").append(schemaReport != null).append(nl);
        if (childReport != null) {
            text.append("Child findings              : ").append(childReport.summary().findingCount()).append(nl);
            text.append("Child errors                : ").append(childReport.summary().errorCount()).append(nl);
        }
        if (schemaReport != null) {
            text.append("Schema tables scanned       : ").append(schemaReport.summary().tablesScanned()).append(nl);
            text.append("Schema findings             : ").append(schemaReport.summary().findingCount()).append(nl);
            text.append("Schema errors               : ").append(schemaReport.summary().errorCount()).append(nl);
            text.append("Detected missing PK         : ").append(hasCode(schemaReport, "TABLE_PRIMARY_KEY_MISSING")).append(nl);
            text.append("Datatype rules executed     : ")
                    .append(schemaReport.ruleFamilies().contains(SchemaConformanceAuditService.DATATYPE_COMPATIBILITY))
                    .append(nl);
            text.append("Valid FK integrity errors   : ")
                    .append(countCodes(schemaReport,
                            Set.of("FK_REFERENCED_COLUMN_NOT_FOUND", "FK_TARGET_NOT_UNIQUE")))
                    .append(nl);
        }
        text.append("Audit mutation              : NONE_EXPECTED").append(nl);
        text.append("Cleanup requested           : ").append(config.cleanup()).append(nl);
        text.append("Cleanup succeeded           : ").append(!config.cleanup() || cleanupSucceeded).append(nl);
        text.append("Failure                     : ").append(failure == null ? "" : rootMessage(failure)).append(nl);
        text.append("Decision rule               : valid materialized FK behavior qualifies Db2 LUW FK capability; ")
                .append("legacy documentation-only FK inconsistencies are non-blocking evidence issues.").append(nl);
        text.append(nl).append("Findings                    : ").append(findings).append(nl);
        Files.writeString(summary, text.toString(), StandardCharsets.UTF_8);

        StringBuilder csv = new StringBuilder("scope,rule_family,severity,code,path,message\n");
        appendFindings(csv, "TABLE", childReport);
        appendFindings(csv, "SCHEMA", schemaReport);
        Files.writeString(findings, csv.toString(), StandardCharsets.UTF_8);
        System.out.println(text);
    }

    private static void appendFindings(StringBuilder csv, String scope, SchemaConformanceReport report) {
        if (report == null) return;
        for (SchemaConformanceFinding finding : report.findings()) {
            csv.append(csv(scope)).append(',')
                    .append(csv(finding.ruleFamily())).append(',')
                    .append(csv(finding.severity())).append(',')
                    .append(csv(finding.code())).append(',')
                    .append(csv(finding.path())).append(',')
                    .append(csv(finding.message())).append('\n');
        }
    }

    private static boolean hasCode(SchemaConformanceReport report, String code) {
        return report != null && report.findings().stream().anyMatch(finding -> code.equals(finding.code()));
    }

    private static long countCodes(SchemaConformanceReport report, Set<String> codes) {
        if (report == null) return 0;
        return report.findings().stream().filter(finding -> codes.contains(finding.code())).count();
    }

    private static Set<String> normalized(List<String> names) {
        Set<String> result = new LinkedHashSet<>();
        names.stream().map(value -> value.toUpperCase(Locale.ROOT)).sorted().forEach(result::add);
        return Set.copyOf(result);
    }

    private static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String qq(String schema, String object) {
        return q(schema) + "." + q(object);
    }

    private static String safeAuthId(String authId) {
        String value = authId == null ? "" : authId.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9_$#@]*")) return q(value);
        return value;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": "
                + (current.getMessage() == null ? "" : current.getMessage());
    }

    private record ServerInfo(String product, String version, String serviceLevel) {
    }

    private record Config(String url, String user, String password, String driver, String schema,
                          Path outputDir, boolean confirmDestructive, boolean cleanup) {
        static Config fromSystemProperties() {
            String url = System.getProperty(URL, "").trim();
            String user = System.getProperty(USER, "").trim();
            String password = System.getProperty(PASSWORD);
            if (password == null || password.isBlank()) password = System.getenv("DB2LUW_JDBC_PASSWORD");
            if (password == null) password = "";
            String driver = System.getProperty(DRIVER, "com.ibm.db2.jcc.DB2Driver").trim();
            String schema = System.getProperty(SCHEMA, "SFORGE_DBP11_AUDIT").trim().toUpperCase(Locale.ROOT);
            Path output = Path.of(System.getProperty(OUTPUT_DIR,
                    "target/db2luw-p11-external-audit")).toAbsolutePath().normalize();
            boolean confirm = Boolean.parseBoolean(System.getProperty(CONFIRM_DESTRUCTIVE, "false"));
            boolean cleanup = Boolean.parseBoolean(System.getProperty(CLEANUP, "true"));
            return new Config(url, user, password, driver, schema, output, confirm, cleanup);
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank();
        }

        void validate() {
            if (!confirmDestructive) {
                throw new IllegalArgumentException("DB2LUW-P11 fixture creates/drops an isolated schema; set -D"
                        + CONFIRM_DESTRUCTIVE + "=true");
            }
            if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:db2:")) {
                throw new IllegalArgumentException("DB2LUW-P11 requires a jdbc:db2: URL");
            }
            if (!schema.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Unsafe DB2LUW-P11 audit schema identifier: " + schema);
            }
            if (!schema.startsWith("SFORGE_DBP11_")) {
                throw new IllegalArgumentException(
                        "DB2LUW-P11 destructive fixture schema must start with SFORGE_DBP11_: " + schema);
            }
            if (driver.isBlank()) {
                throw new IllegalArgumentException("DB2LUW-P11 JDBC driver must not be blank");
            }
        }
    }
}
