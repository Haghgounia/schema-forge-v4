package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.JdbcSqlServerMetadataRepository;
import com.behsazan.schemaforge.migration.ColumnChange;
import com.behsazan.schemaforge.migration.SchemaDiffEngine;
import com.behsazan.schemaforge.migration.TableMigrationPlan;
import com.behsazan.schemaforge.migration.TableObjectChange;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SS-P9 read-only convergence gate for the persistent SQL Server cohort deployed by SS-P8.
 *
 * <p>The gate reloads the exact SS-P7 selected canonical snapshots, reads the real SQL Server
 * catalog through {@link JdbcSqlServerMetadataRepository}, and runs the existing
 * {@link SchemaDiffEngine} table-by-table. It never mutates the database. Missing/extra tables
 * and every residual column/object change are emitted as evidence.</p>
 */
class SqlServerPersistentCatalogConvergenceP9IT {
    private static final String INPUT_DIR = "schemaforge.sqlserver.p9.inputDir";
    private static final String CLOSED_SELECTED = "schemaforge.sqlserver.p9.closedSelected";
    private static final String JDBC_URL = "schemaforge.sqlserver.p9.jdbc.url";
    private static final String JDBC_USER = "schemaforge.sqlserver.p9.jdbc.user";
    private static final String JDBC_PASSWORD = "schemaforge.sqlserver.p9.jdbc.password";
    private static final String EXPECTED_SCHEMA = "schemaforge.sqlserver.p9.expectedSchema";
    private static final String OUTPUT_DIR = "schemaforge.sqlserver.p9.outputDir";
    private static final String FAIL_ON_RESIDUAL = "schemaforge.sqlserver.p9.failOnResidual";
    private static final String FAIL_ON_EXTRA = "schemaforge.sqlserver.p9.failOnExtraTables";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final SchemaDiffEngine diffEngine = new SchemaDiffEngine();

    @Test
    void rereadsPersistentSqlServerCatalogAndReportsResidualDrift() throws Exception {
        Config config = Config.fromSystemProperties();
        Assumptions.assumeTrue(config.enabled(),
                "SS-P9 live gate disabled; provide JDBC URL/user, canonical input, and SS-P7 closed-selected CSV");
        config.validate();

        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        DriverManager.setLoginTimeout(15);

        Path inputRoot = config.inputRoot();
        Path selectedCsv = config.closedSelected();
        Path outputRoot = config.outputRoot();
        Files.createDirectories(outputRoot);

        List<Map<String, String>> selectedRows = readCsv(selectedCsv);
        Map<String, Table> expectedByTable = new LinkedHashMap<>();
        List<String> contractMismatches = new ArrayList<>();

        for (Map<String, String> row : selectedRows) {
            String tableName = required(row, "table");
            String tableKey = key(tableName);
            String cohortStatus = required(row, "cohort_status");
            if (!"SAFE_CLOSED_SUBSET".equals(cohortStatus)) {
                contractMismatches.add(tableName + ",UNEXPECTED_COHORT_STATUS," + cohortStatus);
                continue;
            }

            String snapshotRelative = required(row, "selected_snapshot");
            Path snapshotPath = inputRoot.resolve(snapshotRelative.replace('/', java.io.File.separatorChar))
                    .toAbsolutePath().normalize();
            if (!snapshotPath.startsWith(inputRoot) || !Files.isRegularFile(snapshotPath)) {
                contractMismatches.add(tableName + ",SNAPSHOT_NOT_FOUND," + snapshotRelative);
                continue;
            }

            CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
            DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
            Table expected = schema.tables().stream()
                    .filter(table -> key(table.qualifiedName().toString()).equals(tableKey))
                    .findFirst().orElse(null);
            if (expected == null) {
                contractMismatches.add(tableName + ",TABLE_NOT_IN_SNAPSHOT," + snapshotRelative);
                continue;
            }
            if (!expected.qualifiedName().schemaName().map(identifier -> identifier.value()).orElse("").equalsIgnoreCase(config.schema())) {
                contractMismatches.add(tableName + ",DATABASE_MISMATCH,expected schema="
                        + expected.qualifiedName().schemaName().map(identifier -> identifier.value()).orElse("") + " configured=" + config.schema());
                continue;
            }
            if (expectedByTable.putIfAbsent(tableKey, expected) != null) {
                contractMismatches.add(tableName + ",DUPLICATE_SELECTED_TABLE," + snapshotRelative);
            }
        }

        String product;
        String version;
        List<String> liveNames;
        List<String> missingTables;
        List<String> extraTables;
        List<Residual> residuals = new ArrayList<>();
        Counts expectedCounts = new Counts();
        Counts actualCounts = new Counts();
        int comparedTables = 0;

        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password())) {
            DatabaseMetaData meta = connection.getMetaData();
            product = meta.getDatabaseProductName();
            version = meta.getDatabaseProductVersion();
            assertTrue(product.toLowerCase(Locale.ROOT).contains("sql server"),
                    "SS-P9 must run against SQL Server; actual=" + product);

            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            JdbcSqlServerMetadataRepository repository =
                    new JdbcSqlServerMetadataRepository(new NamedParameterJdbcTemplate(dataSource));
            assertTrue(repository.schemaExists(config.schema()),
                    "Expected SQL Server schema does not exist: " + config.schema());

            liveNames = repository.findTableNames(config.schema()).stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            Set<String> liveKeys = new LinkedHashSet<>();
            liveNames.forEach(name -> liveKeys.add(key(config.schema() + "." + name)));
            Set<String> expectedKeys = new LinkedHashSet<>(expectedByTable.keySet());

            missingTables = expectedKeys.stream().filter(name -> !liveKeys.contains(name)).sorted().toList();
            extraTables = liveKeys.stream().filter(name -> !expectedKeys.contains(name)).sorted().toList();

            List<Table> expectedTables = expectedByTable.values().stream()
                    .sorted(Comparator.comparing(table -> key(table.qualifiedName().toString())))
                    .toList();
            int processed = 0;
            for (Table expected : expectedTables) {
                processed++;
                expectedCounts.add(expected);
                String tableName = expected.qualifiedName().name().value();
                Table live = repository.findTable(config.schema(), tableName).orElse(null);
                if (live != null) {
                    comparedTables++;
                    actualCounts.add(live);

                    TableMigrationPlan plan = diffEngine.diff(DatabasePlatform.SQLSERVER, live, expected);
                    for (ColumnChange change : plan.columnChanges()) {
                        residuals.add(Residual.column(expected.qualifiedName().toString(), change));
                    }
                    for (TableObjectChange change : plan.objectChanges()) {
                        residuals.add(Residual.object(expected.qualifiedName().toString(), change));
                    }
                }
                if (processed % 100 == 0 || processed == expectedTables.size()) {
                    System.out.println("SS-P9 SQL Server catalog: " + processed + " / " + expectedTables.size()
                            + ", compared=" + comparedTables + ", residuals=" + residuals.size());
                }
            }
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path summaryPath = outputRoot.resolve("sqlserver-p9-catalog-convergence-summary_" + timestamp + ".txt");
        Path residualPath = outputRoot.resolve("sqlserver-p9-residual-drift_" + timestamp + ".csv");
        Path tablePath = outputRoot.resolve("sqlserver-p9-table-set-drift_" + timestamp + ".csv");
        Path contractPath = outputRoot.resolve("sqlserver-p9-contract-mismatches_" + timestamp + ".csv");

        Files.writeString(residualPath, residualCsv(residuals), StandardCharsets.UTF_8);
        Files.writeString(tablePath, tableSetCsv(missingTables, extraTables), StandardCharsets.UTF_8);
        Files.writeString(contractPath, contractCsv(contractMismatches), StandardCharsets.UTF_8);

        long residualColumnChanges = residuals.stream().filter(r -> "COLUMN".equals(r.scope())).count();
        long residualObjectChanges = residuals.size() - residualColumnChanges;
        boolean cohortConverged = contractMismatches.isEmpty()
                && missingTables.isEmpty()
                && residuals.isEmpty();
        boolean exactDatabaseConverged = cohortConverged && extraTables.isEmpty();

        String summary = summary(config, product, version, selectedRows.size(), expectedByTable.size(),
                contractMismatches.size(), liveNames.size(), comparedTables, missingTables.size(), extraTables.size(),
                expectedCounts, actualCounts, residualColumnChanges, residualObjectChanges, residuals.size(),
                cohortConverged, exactDatabaseConverged, summaryPath, residualPath, tablePath, contractPath);
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        if (config.failOnResidual()) {
            assertEquals(0, contractMismatches.size(), "SS-P9 canonical selection contract mismatch; see " + contractPath);
            assertEquals(0, missingTables.size(), "SS-P9 expected tables are missing; see " + tablePath);
            assertEquals(0, residuals.size(), "SS-P9 residual SQL Server drift remains; see " + residualPath);
        }
        if (config.failOnExtraTables()) {
            assertEquals(0, extraTables.size(), "SS-P9 unexpected live tables remain; see " + tablePath);
        }
    }

    private static String summary(
            Config config, String product, String version, int selectedRows, int expectedTables,
            int contractMismatches, int liveTables, int comparedTables, int missingTables, int extraTables,
            Counts expected, Counts actual, long residualColumnChanges, long residualObjectChanges,
            int residualTotal, boolean cohortConverged, boolean exactDatabaseConverged,
            Path summaryPath, Path residualPath, Path tablePath, Path contractPath) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append("SchemaForge SQL Server SS-P9 persistent catalog convergence").append(nl);
        out.append("=========================================================").append(nl);
        out.append("Database product            : ").append(product).append(nl);
        out.append("Database version            : ").append(version).append(nl);
        out.append("Expected schema             : ").append(config.schema()).append(nl);
        out.append("Canonical input             : ").append(config.inputRoot()).append(nl);
        out.append("SS-P7 closed selected       : ").append(config.closedSelected()).append(nl);
        out.append("Selected rows               : ").append(selectedRows).append(nl);
        out.append("Expected tables loaded      : ").append(expectedTables).append(nl);
        out.append("Contract mismatches         : ").append(contractMismatches).append(nl);
        out.append(nl);
        out.append("Catalog table-set state").append(nl);
        out.append("-----------------------").append(nl);
        out.append("Live tables in database     : ").append(liveTables).append(nl);
        out.append("Expected tables compared    : ").append(comparedTables).append(nl);
        out.append("Missing expected tables     : ").append(missingTables).append(nl);
        out.append("Extra live tables           : ").append(extraTables).append(nl);
        out.append(nl);
        out.append("Expected vs live objects").append(nl);
        out.append("------------------------").append(nl);
        out.append("Columns                     : ").append(expected.columns).append(" / ").append(actual.columns).append(nl);
        out.append("Primary keys                : ").append(expected.primaryKeys).append(" / ").append(actual.primaryKeys).append(nl);
        out.append("Unique keys                 : ").append(expected.uniqueKeys).append(" / ").append(actual.uniqueKeys).append(nl);
        out.append("Checks                      : ").append(expected.checks).append(" / ").append(actual.checks).append(nl);
        out.append("Indexes                     : ").append(expected.indexes).append(" / ").append(actual.indexes).append(nl);
        out.append("Foreign keys                : ").append(expected.foreignKeys).append(" / ").append(actual.foreignKeys).append(nl);
        out.append(nl);
        out.append("Residual drift").append(nl);
        out.append("--------------").append(nl);
        out.append("Residual column changes     : ").append(residualColumnChanges).append(nl);
        out.append("Residual object changes     : ").append(residualObjectChanges).append(nl);
        out.append("Residual changes total      : ").append(residualTotal).append(nl);
        out.append("Cohort converged            : ").append(cohortConverged).append(nl);
        out.append("Exact database converged    : ").append(exactDatabaseConverged).append(nl);
        out.append("Read-only gate              : true").append(nl);
        out.append(nl);
        out.append("Outputs").append(nl);
        out.append("-------").append(nl);
        out.append("Summary                     : ").append(summaryPath).append(nl);
        out.append("Residual drift              : ").append(residualPath).append(nl);
        out.append("Table-set drift             : ").append(tablePath).append(nl);
        out.append("Contract mismatches         : ").append(contractPath).append(nl);
        return out.toString();
    }

    private static String residualCsv(List<Residual> rows) {
        StringBuilder out = new StringBuilder("table,scope,change_kind,object_type,object_name,risk,rationale\n");
        for (Residual row : rows) {
            csv(out, row.table()); out.append(',');
            csv(out, row.scope()); out.append(',');
            csv(out, row.changeKind()); out.append(',');
            csv(out, row.objectType()); out.append(',');
            csv(out, row.objectName()); out.append(',');
            csv(out, row.risk()); out.append(',');
            csv(out, row.rationale()); out.append('\n');
        }
        return out.toString();
    }

    private static String tableSetCsv(List<String> missing, List<String> extra) {
        StringBuilder out = new StringBuilder("status,table\n");
        for (String table : missing) { csv(out, "MISSING_EXPECTED"); out.append(','); csv(out, table); out.append('\n'); }
        for (String table : extra) { csv(out, "EXTRA_LIVE"); out.append(','); csv(out, table); out.append('\n'); }
        return out.toString();
    }

    private static String contractCsv(List<String> rows) {
        StringBuilder out = new StringBuilder("detail\n");
        for (String row : rows) { csv(out, row); out.append('\n'); }
        return out.toString();
    }

    private static void csv(StringBuilder out, String value) {
        String text = value == null ? "" : value;
        out.append('"').append(text.replace("\"", "\"\"")).append('"');
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> headers = parseCsvLine(stripBom(lines.getFirst()));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = parseCsvLine(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(headers.get(c), c < values.size() ? values.get(c) : "");
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString()); current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String required(Map<String, String> row, String field) {
        String value = row.getOrDefault(field, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Required CSV field is blank: " + field + " row=" + row);
        return value;
    }

    private static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\ufeff' ? text.substring(1) : text;
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record Residual(
            String table, String scope, String changeKind, String objectType,
            String objectName, String risk, String rationale) {
        static Residual column(String table, ColumnChange change) {
            return new Residual(table, "COLUMN", change.kind().name(), "COLUMN",
                    change.columnName().value(), change.risk().name(), change.rationale());
        }
        static Residual object(String table, TableObjectChange change) {
            return new Residual(table, "OBJECT", change.kind().name(), change.objectType().name(),
                    change.objectName() == null ? "" : change.objectName().value(),
                    change.risk().name(), change.rationale());
        }
    }

    private static final class Counts {
        long columns;
        long primaryKeys;
        long uniqueKeys;
        long checks;
        long indexes;
        long foreignKeys;

        void add(Table table) {
            columns += table.columns().size();
            primaryKeys += table.primaryKey().isPresent() ? 1 : 0;
            uniqueKeys += table.uniqueKeys().size();
            checks += table.checkConstraints().size();
            indexes += table.indexes().size();
            foreignKeys += table.foreignKeys().size();
        }
    }

    private record Config(
            Path inputRoot, Path closedSelected, String url, String user, String password,
            String schema, Path outputRoot, boolean failOnResidual, boolean failOnExtraTables) {

        static Config fromSystemProperties() {
            String input = System.getProperty(INPUT_DIR, "").trim();
            String selected = System.getProperty(CLOSED_SELECTED, "").trim();
            String url = System.getProperty(JDBC_URL, "").trim();
            String user = System.getProperty(JDBC_USER, "").trim();
            String password = System.getProperty(JDBC_PASSWORD,
                    System.getenv().getOrDefault("SQLSERVER_JDBC_PASSWORD", ""));
            String schema = System.getProperty(EXPECTED_SCHEMA, "TSTSHMA").trim();
            String output = System.getProperty(OUTPUT_DIR, "target/sqlserver-p9-catalog-convergence").trim();
            return new Config(
                    input.isEmpty() ? Path.of(".") : Path.of(input).toAbsolutePath().normalize(),
                    selected.isEmpty() ? Path.of(".") : Path.of(selected).toAbsolutePath().normalize(),
                    url, user, password, schema,
                    Path.of(output).toAbsolutePath().normalize(),
                    Boolean.parseBoolean(System.getProperty(FAIL_ON_RESIDUAL, "true")),
                    Boolean.parseBoolean(System.getProperty(FAIL_ON_EXTRA, "false")));
        }

        boolean enabled() {
            return !url.isBlank() && !user.isBlank()
                    && Files.isDirectory(inputRoot) && Files.isRegularFile(closedSelected);
        }

        void validate() {
            if (!Files.isDirectory(inputRoot)) throw new IllegalArgumentException("Canonical input directory not found: " + inputRoot);
            if (!Files.isRegularFile(closedSelected)) throw new IllegalArgumentException("SS-P7 closed-selected CSV not found: " + closedSelected);
            if (url.isBlank()) throw new IllegalArgumentException("Missing -D" + JDBC_URL);
            if (user.isBlank()) throw new IllegalArgumentException("Missing -D" + JDBC_USER);
            if (schema.isBlank()) throw new IllegalArgumentException("Missing -D" + EXPECTED_SCHEMA);
        }
    }
}
