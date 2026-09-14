package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceFinding;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import com.behsazan.schemaforge.metadata.repository.JdbcOracleMetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.repository.OracleMetadataRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * ORA-P10 live qualification for read-only auditing of Oracle objects created outside SchemaForge.
 *
 * <p>The fixture DDL is executed directly through JDBC, deliberately bypassing SchemaForge DDL
 * generation. For destructive safety this test requires a dedicated Oracle user/schema whose name
 * starts with {@code SFORGE_P10_}; the connected user must equal that schema. The production Oracle
 * metadata repository and {@link SchemaConformanceAuditService} then audit one table and the whole
 * dedicated schema. The audit path itself is read-only.</p>
 */
class OracleExternalDatabaseAuditP10IT {
    private static final String URL = "schemaforge.oracle.p10.jdbc.url";
    private static final String USER = "schemaforge.oracle.p10.jdbc.user";
    private static final String PASSWORD = "schemaforge.oracle.p10.jdbc.password";
    private static final String SCHEMA = "schemaforge.oracle.p10.audit.schema";
    private static final String OUTPUT_DIR = "schemaforge.oracle.p10.audit.outputDir";
    private static final String CONFIRM_DESTRUCTIVE = "schemaforge.oracle.p10.audit.confirmDestructive";
    private static final String CLEANUP = "schemaforge.oracle.p10.audit.cleanup";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    @Test
    void auditsExternallyCreatedOracleTableAndSchemaWithoutMutation() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "ORA-P10 Oracle live audit disabled; provide " + URL + " and " + USER);
        config.validate();

        Class.forName("oracle.jdbc.OracleDriver");
        DriverManager.setLoginTimeout(15);

        Throwable failure = null;
        boolean cleanupSucceeded = false;
        SchemaConformanceReport childReport = null;
        SchemaConformanceReport schemaReport = null;
        Set<String> beforeTables = Set.of();
        Set<String> afterTables = Set.of();
        int liveForeignKeys = -1;

        try {
            verifyDedicatedSchema(config);
            createExternalFixture(config);

            DriverManagerDataSource dataSource = new DriverManagerDataSource(config.url(), config.user(), config.password());
            OracleMetadataRepository oracleRepository = new JdbcOracleMetadataRepository(
                    new NamedParameterJdbcTemplate(dataSource));
            MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
            when(resolver.resolve(DatabasePlatform.ORACLE)).thenReturn(oracleRepository);
            SchemaConformanceAuditService service = new SchemaConformanceAuditService(resolver);

            beforeTables = normalized(oracleRepository.findTableNames(config.schema()));
            assertEquals(Set.of("P10_CHILD", "P10_NO_PK", "P10_PARENT"), beforeTables,
                    "Dedicated fixture schema must contain only the three direct-JDBC tables");

            var child = oracleRepository.findTable(config.schema(), "P10_CHILD").orElseThrow();
            liveForeignKeys = child.foreignKeys().size();
            assertEquals(1, liveForeignKeys, "Valid direct-JDBC FK must be visible through Oracle metadata");
            assertEquals("FK_P10_CHILD_PARENT", child.foreignKeys().getFirst().name().normalized());

            childReport = service.auditTable(DatabasePlatform.ORACLE, config.schema(), "P10_CHILD");
            assertEquals(SchemaConformanceScope.TABLE, childReport.scope());
            assertEquals(1, childReport.summary().tablesScanned());
            assertEquals(SchemaConformanceAuditService.TABLE_RULE_FAMILIES, childReport.ruleFamilies());
            assertFalse(hasCode(childReport, "FK_REFERENCED_COLUMN_NOT_FOUND"));
            assertFalse(hasCode(childReport, "FK_TARGET_NOT_UNIQUE"));

            schemaReport = service.auditSchema(DatabasePlatform.ORACLE, config.schema());
            assertEquals(SchemaConformanceScope.SCHEMA, schemaReport.scope());
            assertEquals(3, schemaReport.summary().tablesScanned());
            assertTrue(hasCode(schemaReport, "TABLE_PRIMARY_KEY_MISSING"),
                    "Audit must detect the external table without a primary key");
            assertTrue(hasCode(schemaReport, "NUMERIC_PRECISION_UNSPECIFIED"),
                    "Audit must execute Oracle datatype compatibility rules on live NUMBER metadata");
            assertFalse(hasCode(schemaReport, "FK_REFERENCED_COLUMN_NOT_FOUND"));
            assertFalse(hasCode(schemaReport, "FK_TARGET_NOT_UNIQUE"));

            afterTables = normalized(oracleRepository.findTableNames(config.schema()));
            assertEquals(beforeTables, afterTables, "Read-only audit must not add/drop/rename tables");
            assertTrue(oracleRepository.findTable(config.schema(), "P10_CHILD").orElseThrow()
                    .foreignKeys().stream().anyMatch(fk -> "FK_P10_CHILD_PARENT".equals(fk.name().normalized())),
                    "Read-only audit must not alter the valid FK");
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            if (config.cleanup()) {
                try {
                    cleanupFixture(config);
                    cleanupSucceeded = true;
                } catch (Throwable cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            }
            writeEvidence(config, childReport, schemaReport, beforeTables, afterTables,
                    liveForeignKeys, cleanupSucceeded, failure);
        }
    }

    private static void verifyDedicatedSchema(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT USER FROM DUAL")) {
            assertTrue(rows.next());
            String actual = rows.getString(1).toUpperCase(Locale.ROOT);
            assertEquals(config.schema(), actual,
                    "ORA-P10 destructive fixture safety requires connected Oracle user == audit schema");
        }
    }

    private static void createExternalFixture(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(true);
            dropTableIfExists(connection, "P10_CHILD");
            dropTableIfExists(connection, "P10_NO_PK");
            dropTableIfExists(connection, "P10_PARENT");
            assertNoOtherUserTables(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE P10_PARENT ("
                        + "ID NUMBER(19,0) NOT NULL, CODE VARCHAR2(20 CHAR) NOT NULL, "
                        + "CONSTRAINT PK_P10_PARENT PRIMARY KEY (ID), "
                        + "CONSTRAINT UK_P10_PARENT_CODE UNIQUE (CODE))");
                statement.execute("CREATE TABLE P10_CHILD ("
                        + "ID NUMBER(19,0) NOT NULL, PARENT_ID NUMBER(19,0) NOT NULL, NOTE VARCHAR2(30 CHAR), "
                        + "CONSTRAINT PK_P10_CHILD PRIMARY KEY (ID), "
                        + "CONSTRAINT FK_P10_CHILD_PARENT FOREIGN KEY (PARENT_ID) REFERENCES P10_PARENT (ID))");
                // NUMBER without explicit precision is valid Oracle DDL and deliberately exercises
                // the existing non-blocking datatype compatibility warning during live audit.
                statement.execute("CREATE TABLE P10_NO_PK (LEGACY_CODE NUMBER, DESCRIPTION VARCHAR2(40 CHAR))");
            }
        }
    }

    private static void assertNoOtherUserTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME")) {
            Set<String> names = new LinkedHashSet<>();
            while (rows.next()) names.add(rows.getString(1).toUpperCase(Locale.ROOT));
            if (!names.isEmpty()) {
                throw new IllegalStateException("ORA-P10 requires a dedicated empty schema; unexpected tables=" + names);
            }
        }
    }

    private static void cleanupFixture(Config config) throws Exception {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(true);
            dropTableIfExists(connection, "P10_CHILD");
            dropTableIfExists(connection, "P10_NO_PK");
            dropTableIfExists(connection, "P10_PARENT");
        }
    }

    private static void dropTableIfExists(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
        } catch (SQLException error) {
            if (error.getErrorCode() != 942) throw error;
        }
    }

    private static void writeEvidence(
            Config config,
            SchemaConformanceReport childReport,
            SchemaConformanceReport schemaReport,
            Set<String> beforeTables,
            Set<String> afterTables,
            int liveForeignKeys,
            boolean cleanupSucceeded,
            Throwable failure) throws Exception {
        Files.createDirectories(config.outputDir());
        String stamp = LocalDateTime.now().format(TS);
        Path summary = config.outputDir().resolve("oracle-p10-external-audit-summary_" + stamp + ".txt");
        Path findings = config.outputDir().resolve("oracle-p10-external-audit-findings_" + stamp + ".csv");

        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge Oracle ORA-P10 external database audit qualification").append(nl);
        text.append("=============================================================").append(nl);
        text.append("Fixture creation path       : DIRECT_JDBC_OUTSIDE_SCHEMAFORGE").append(nl);
        text.append("Audit service               : SchemaConformanceAuditService").append(nl);
        text.append("Target schema/user          : ").append(config.schema()).append(nl);
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
            text.append("Detected datatype warning   : ").append(hasCode(schemaReport, "NUMERIC_PRECISION_UNSPECIFIED")).append(nl);
            text.append("Valid FK integrity errors   : ")
                    .append(countCodes(schemaReport, Set.of("FK_REFERENCED_COLUMN_NOT_FOUND", "FK_TARGET_NOT_UNIQUE")))
                    .append(nl);
        }
        text.append("Audit mutation              : NONE_EXPECTED").append(nl);
        text.append("Cleanup requested           : ").append(config.cleanup()).append(nl);
        text.append("Cleanup succeeded           : ").append(!config.cleanup() || cleanupSucceeded).append(nl);
        text.append("Failure                     : ").append(failure == null ? "" : rootMessage(failure)).append(nl);
        text.append("Decision rule               : valid materialized FK behavior qualifies Oracle FK capability; ")
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

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + (current.getMessage() == null ? "" : current.getMessage());
    }

    private record Config(String url, String user, String password, String schema,
                          Path outputDir, boolean confirmDestructive, boolean cleanup) {
        static Config fromSystemProperties() {
            String url = System.getProperty(URL, "").trim();
            String user = System.getProperty(USER, "").trim();
            String password = System.getProperty(PASSWORD);
            if (password == null || password.isBlank()) password = System.getenv("ORACLE_JDBC_PASSWORD");
            if (password == null) password = "";
            String schema = System.getProperty(SCHEMA, user).trim().toUpperCase(Locale.ROOT);
            Path output = Path.of(System.getProperty(OUTPUT_DIR,
                    "target/oracle-p10-external-audit")).toAbsolutePath().normalize();
            boolean confirm = Boolean.parseBoolean(System.getProperty(CONFIRM_DESTRUCTIVE, "false"));
            boolean cleanup = Boolean.parseBoolean(System.getProperty(CLEANUP, "true"));
            return new Config(url, user, password, schema, output, confirm, cleanup);
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank();
        }

        void validate() {
            if (!confirmDestructive) {
                throw new IllegalArgumentException("ORA-P10 fixture creates/drops isolated tables; set -D"
                        + CONFIRM_DESTRUCTIVE + "=true");
            }
            if (!schema.matches("[A-Z][A-Z0-9_$#]{0,29}")) {
                throw new IllegalArgumentException("Unsafe ORA-P10 audit schema identifier: " + schema);
            }
            if (!schema.startsWith("SFORGE_P10_")) {
                throw new IllegalArgumentException("ORA-P10 audit schema/user must start with SFORGE_P10_: " + schema);
            }
            if (!schema.equals(user.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("ORA-P10 connected user must equal audit schema/user for safety: user="
                        + user + " schema=" + schema);
            }
        }
    }
}
