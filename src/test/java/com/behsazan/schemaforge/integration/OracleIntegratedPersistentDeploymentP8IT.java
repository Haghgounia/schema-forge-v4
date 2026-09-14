package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ORA-P8 persistent integrated deployment gate.
 *
 * <p>Executes the single dependency-closed integrated Oracle script emitted by ORA-P7. The gate
 * cleans only objects declared by that script, requires explicit destructive confirmation, executes
 * through JDBC using the existing Oracle statement splitter, and deliberately leaves the successful
 * deployment in place for ORA-P9 catalog convergence.</p>
 */
class OracleIntegratedPersistentDeploymentP8IT {
    private static final String SCRIPT = "schemaforge.oracle.p8.integratedScript";
    private static final String JDBC_URL = "schemaforge.oracle.p8.jdbc.url";
    private static final String JDBC_USER = "schemaforge.oracle.p8.jdbc.user";
    private static final String JDBC_PASSWORD = "schemaforge.oracle.p8.jdbc.password";
    private static final String EXPECTED_SCHEMA = "schemaforge.oracle.p8.expectedSchema";
    private static final String OUTPUT_DIR = "schemaforge.oracle.p8.outputDir";
    private static final String CONFIRM_DESTRUCTIVE = "schemaforge.oracle.p8.confirmDestructive";
    private static final String CLEAN_BEFORE = "schemaforge.oracle.p8.cleanBefore";
    private static final String STATEMENT_TIMEOUT = "schemaforge.oracle.p8.statementTimeoutSeconds";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+"
                    + "((?:\"[^\"]+\"|[A-Z0-9_$#]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$#]+))?)");
    private static final Pattern CREATE_SEQUENCE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+SEQUENCE\\s+"
                    + "((?:\"[^\"]+\"|[A-Z0-9_$#]+)(?:\\s*\\.\\s*(?:\"[^\"]+\"|[A-Z0-9_$#]+))?)");

    @Test
    void deploysOracleClosedSubsetPersistentlyForCatalogConvergence() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "ORA-P8 live gate disabled; provide integrated script, JDBC URL and user");
        config.validate();

        Class.forName("oracle.jdbc.OracleDriver");
        DriverManager.setLoginTimeout(15);

        String script = Files.readString(config.script(), StandardCharsets.UTF_8);
        List<OracleSqlDirectoryExecutionTest.SqlUnit> units =
                new OracleSqlDirectoryExecutionTest.OracleStatementSplitter().split(script);
        assertTrue(!units.isEmpty(), "ORA-P8 integrated script contains no executable SQL");

        Set<String> tables = new LinkedHashSet<>();
        Set<String> sequences = new LinkedHashSet<>();
        for (OracleSqlDirectoryExecutionTest.SqlUnit unit : units) {
            Matcher table = CREATE_TABLE.matcher(stripLeadingComments(unit.sql()));
            if (table.find()) tables.add(normalizeName(table.group(1)));
            Matcher sequence = CREATE_SEQUENCE.matcher(stripLeadingComments(unit.sql()));
            if (sequence.find()) sequences.add(normalizeName(sequence.group(1)));
        }
        assertTrue(!tables.isEmpty(), "ORA-P8 integrated script contains no CREATE TABLE statements");

        int executed = 0;
        List<String> failures = new ArrayList<>();
        String product;
        String version;
        String currentUser;

        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            connection.setAutoCommit(true);
            DatabaseMetaData metadata = connection.getMetaData();
            product = metadata.getDatabaseProductName();
            version = metadata.getDatabaseProductVersion();
            assertTrue(product.toLowerCase(Locale.ROOT).contains("oracle"),
                    "ORA-P8 must run against Oracle; actual=" + product);
            currentUser = querySingle(connection, "SELECT USER FROM DUAL").toUpperCase(Locale.ROOT);
            assertEquals(config.schema(), currentUser,
                    "ORA-P8 destructive safety requires connected Oracle user == expected schema");

            if (config.cleanBefore()) {
                for (String table : tables) dropTableIfExists(connection, table, config.schema());
                for (String sequence : sequences) dropSequenceIfExists(connection, sequence, config.schema());
            }

            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(config.statementTimeoutSeconds());
                int index = 0;
                for (OracleSqlDirectoryExecutionTest.SqlUnit unit : units) {
                    index++;
                    try {
                        statement.execute(unit.sql());
                        executed++;
                    } catch (SQLException error) {
                        failures.add(index + ",ORA-" + String.format(Locale.ROOT, "%05d", Math.abs(error.getErrorCode()))
                                + "," + oneLine(error.getMessage()) + "," + oneLine(unit.sql()));
                    }
                }
            }

            assertEquals(0, failures.size(), "ORA-P8 integrated deployment SQL failures: " + failures);
            long liveCreatedTables = tables.stream().filter(table -> tableExists(connection, table)).count();
            assertEquals(tables.size(), liveCreatedTables,
                    "ORA-P8 all integrated CREATE TABLE targets must persist for ORA-P9");
        }

        Files.createDirectories(config.outputDir());
        String stamp = LocalDateTime.now().format(TS);
        Path summary = config.outputDir().resolve("oracle-p8-persistent-deployment-summary_" + stamp + ".txt");
        Path errors = config.outputDir().resolve("oracle-p8-persistent-deployment-errors_" + stamp + ".csv");
        Files.writeString(errors, errorCsv(failures), StandardCharsets.UTF_8);
        String nl = System.lineSeparator();
        String text = "SchemaForge Oracle ORA-P8 persistent integrated deployment" + nl
                + "=========================================================" + nl
                + "Database product            : " + product + nl
                + "Database version            : " + version + nl
                + "Expected schema/user        : " + config.schema() + nl
                + "Connected user              : " + currentUser + nl
                + "Integrated script           : " + config.script() + nl
                + "CREATE TABLE targets        : " + tables.size() + nl
                + "CREATE SEQUENCE targets     : " + sequences.size() + nl
                + "Executable statements       : " + units.size() + nl
                + "Statements succeeded        : " + executed + nl
                + "Statements failed           : " + failures.size() + nl
                + "Clean before                : " + config.cleanBefore() + nl
                + "Persistent after gate       : true" + nl
                + "Ready for ORA-P9            : " + failures.isEmpty() + nl
                + "Errors                      : " + errors + nl;
        Files.writeString(summary, text, StandardCharsets.UTF_8);
        System.out.println(text);
    }

    private static boolean tableExists(Connection connection, String qualified) {
        String table = unqualified(qualified);
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='"
                     + table.replace("'", "''") + "'")) {
            return rows.next() && rows.getInt(1) == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to verify ORA-P8 table " + qualified, error);
        }
    }

    private static void dropTableIfExists(Connection connection, String qualified, String expectedSchema) throws Exception {
        verifyOwner(qualified, expectedSchema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + qualified + " CASCADE CONSTRAINTS PURGE");
        } catch (SQLException error) {
            if (error.getErrorCode() != 942) throw error;
        }
    }

    private static void dropSequenceIfExists(Connection connection, String qualified, String expectedSchema) throws Exception {
        verifyOwner(qualified, expectedSchema);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SEQUENCE " + qualified);
        } catch (SQLException error) {
            if (error.getErrorCode() != 2289) throw error;
        }
    }

    private static void verifyOwner(String qualified, String expectedSchema) {
        String clean = qualified.replace("\"", "");
        int dot = clean.indexOf('.');
        if (dot >= 0) {
            String owner = clean.substring(0, dot).trim();
            if (!owner.equalsIgnoreCase(expectedSchema)) {
                throw new IllegalStateException("ORA-P8 refuses destructive cleanup outside expected schema: " + qualified);
            }
        }
    }

    private static String querySingle(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("No row returned for: " + sql);
            return rows.getString(1);
        }
    }

    private static String normalizeName(String value) {
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String unqualified(String qualified) {
        String clean = qualified.replace("\"", "");
        int dot = clean.lastIndexOf('.');
        return (dot < 0 ? clean : clean.substring(dot + 1)).toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingComments(String sql) {
        String value = sql == null ? "" : sql.stripLeading();
        boolean changed;
        do {
            changed = false;
            if (value.startsWith("--")) {
                int newline = value.indexOf('\n');
                value = newline < 0 ? "" : value.substring(newline + 1).stripLeading();
                changed = true;
            } else if (value.startsWith("/*")) {
                int end = value.indexOf("*/", 2);
                if (end >= 0) {
                    value = value.substring(end + 2).stripLeading();
                    changed = true;
                }
            }
        } while (changed);
        return value;
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String errorCsv(List<String> failures) {
        StringBuilder out = new StringBuilder("detail\n");
        for (String failure : failures) out.append('"').append(failure.replace("\"", "\"\"")).append("\"\n");
        return out.toString();
    }

    private record Config(Path script, String url, String user, String password, String schema,
                          Path outputDir, boolean confirmDestructive, boolean cleanBefore,
                          int statementTimeoutSeconds) {
        static Config fromSystemProperties() {
            String script = System.getProperty(SCRIPT, "").trim();
            String url = System.getProperty(JDBC_URL, "").trim();
            String user = System.getProperty(JDBC_USER, "").trim();
            String password = System.getProperty(JDBC_PASSWORD);
            if (password == null || password.isBlank()) password = System.getenv("ORACLE_JDBC_PASSWORD");
            if (password == null) password = "";
            String schema = System.getProperty(EXPECTED_SCHEMA, user).trim().toUpperCase(Locale.ROOT);
            Path output = Path.of(System.getProperty(OUTPUT_DIR,
                    "target/oracle-p8-persistent-deployment")).toAbsolutePath().normalize();
            boolean confirm = Boolean.parseBoolean(System.getProperty(CONFIRM_DESTRUCTIVE, "false"));
            boolean clean = Boolean.parseBoolean(System.getProperty(CLEAN_BEFORE, "true"));
            int timeout = Integer.parseInt(System.getProperty(STATEMENT_TIMEOUT, "60"));
            return new Config(script.isEmpty() ? Path.of(".") : Path.of(script).toAbsolutePath().normalize(),
                    url, user, password, schema, output, confirm, clean, timeout);
        }

        boolean enabled() {
            return Files.isRegularFile(script) && !url.isBlank() && !user.isBlank();
        }

        void validate() {
            if (!Files.isRegularFile(script)) throw new IllegalArgumentException("ORA-P8 integrated script not found: " + script);
            if (!confirmDestructive) throw new IllegalArgumentException("ORA-P8 clean-before requires -D" + CONFIRM_DESTRUCTIVE + "=true");
            if (schema.isBlank()) throw new IllegalArgumentException("ORA-P8 expected schema is required");
            if (!schema.equals(user.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("ORA-P8 user must equal expected schema for destructive safety: user="
                        + user + " schema=" + schema);
            }
            if (statementTimeoutSeconds < 1) throw new IllegalArgumentException("ORA-P8 statement timeout must be positive");
        }
    }
}
