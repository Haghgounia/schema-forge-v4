package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisCode;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisIssue;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisSeverity;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalyzer;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlan;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.deployment.IntegratedSqlScript;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.validation.mariadb.MariaDbDdlSanityChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * M10.2 readiness gate for the no-guess, auto-selected MariaDB integrated cohort.
 *
 * <p>The runner deliberately does not connect to MariaDB and does not drop foreign keys that
 * reference unresolved historical tables. It loads exactly the snapshots approved by M10.1,
 * builds one unique logical table set, classifies FK blockers, validates MariaDB local rendering,
 * and emits a complete integrated script only when the selected cohort is fully deployable.</p>
 */
class CanonicalJsonMariaDbIntegratedReadinessIT {
    private static final String INPUT_DIR = "schemaforge.m10.mariadb.inputDir";
    private static final String AUTO_SELECTED = "schemaforge.m10.mariadb.autoSelected";
    private static final String MANIFEST = "schemaforge.m10.mariadb.manifest";
    private static final String OUTPUT_DIR = "schemaforge.m10.mariadb.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.m10.mariadb.cleanOutput";
    private static final String FAIL_ON_CONTRACT_MISMATCH = "schemaforge.m10.mariadb.failOnContractMismatch";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void auditsAutoSelectedIntegratedMariaDbReadiness() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path autoSelectedFile = requiredFile(AUTO_SELECTED);
        Path manifestFile = requiredFile(MANIFEST);
        Path outputRoot = outputDirectory(inputRoot);
        prepareOutput(outputRoot);

        List<Map<String, String>> selectedRows = readCsv(autoSelectedFile);
        List<Map<String, String>> manifestRows = readCsv(manifestFile);
        assertFalse(selectedRows.isEmpty(), "M10.1 auto-selected CSV is empty: " + autoSelectedFile);

        Map<String, Map<String, String>> manifestByTable = new LinkedHashMap<>();
        Set<String> reviewTables = new LinkedHashSet<>();
        for (Map<String, String> row : manifestRows) {
            String table = key(required(row, "table"));
            Map<String, String> previous = manifestByTable.putIfAbsent(table, row);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate table in M10.1 manifest: " + table);
            }
            if ("REQUIRES_REVIEW".equals(required(row, "status"))) {
                reviewTables.add(table);
            }
        }

        List<String> contractMismatches = new ArrayList<>();
        Map<String, Table> selectedTables = new LinkedHashMap<>();
        Map<String, String> snapshotByTable = new LinkedHashMap<>();

        for (Map<String, String> row : selectedRows) {
            String tableName = required(row, "table");
            String tableKey = key(tableName);
            String status = required(row, "status");
            if (!("AUTO_SELECTED_UNIQUE".equals(status) || "AUTO_SELECTED_EXACT_DUPLICATE".equals(status))) {
                contractMismatches.add(tableName + ",UNEXPECTED_SELECTION_STATUS," + status);
                continue;
            }
            Map<String, String> manifestRow = manifestByTable.get(tableKey);
            if (manifestRow == null) {
                contractMismatches.add(tableName + ",MISSING_FROM_MANIFEST,");
                continue;
            }
            if (!status.equals(required(manifestRow, "status"))) {
                contractMismatches.add(tableName + ",STATUS_MISMATCH," + status + " vs " + manifestRow.get("status"));
                continue;
            }
            String snapshotRelative = required(row, "selected_snapshot");
            if (!snapshotRelative.equals(required(manifestRow, "selected_snapshot"))) {
                contractMismatches.add(tableName + ",SNAPSHOT_MISMATCH," + snapshotRelative + " vs "
                        + manifestRow.get("selected_snapshot"));
                continue;
            }

            Path snapshotPath = inputRoot.resolve(snapshotRelative.replace('/', java.io.File.separatorChar))
                    .toAbsolutePath().normalize();
            if (!snapshotPath.startsWith(inputRoot) || !Files.isRegularFile(snapshotPath)) {
                contractMismatches.add(tableName + ",SNAPSHOT_NOT_FOUND," + snapshotRelative);
                continue;
            }

            CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
            DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
            Table selectedTable = schema.tables().stream()
                    .filter(table -> key(table.qualifiedName()).equals(tableKey))
                    .findFirst()
                    .orElse(null);
            if (selectedTable == null) {
                contractMismatches.add(tableName + ",TABLE_NOT_IN_SELECTED_SNAPSHOT," + snapshotRelative);
                continue;
            }
            Table previous = selectedTables.putIfAbsent(tableKey, selectedTable);
            if (previous != null) {
                contractMismatches.add(tableName + ",DUPLICATE_SELECTED_TABLE," + snapshotRelative);
                continue;
            }
            snapshotByTable.put(tableKey, snapshotRelative);
        }

        long expectedAutoSelected = manifestRows.stream()
                .filter(row -> {
                    String status = row.getOrDefault("status", "");
                    return "AUTO_SELECTED_UNIQUE".equals(status)
                            || "AUTO_SELECTED_EXACT_DUPLICATE".equals(status);
                })
                .count();
        if (selectedRows.size() != expectedAutoSelected) {
            contractMismatches.add("<GLOBAL>,AUTO_SELECTED_COUNT_MISMATCH," + selectedRows.size()
                    + " vs manifest " + expectedAutoSelected);
        }

        DatabaseSchema integrated = buildSchema(selectedTables.values().stream()
                .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                .toList());
        ForeignKeyAnalysisResult fkAnalysis = analyzer.analyze(integrated);

        List<ClassifiedFkIssue> classifiedFkIssues = new ArrayList<>();
        long reviewDependencyErrors = 0;
        long unexpectedMissingTargetErrors = 0;
        long otherFkErrors = 0;
        for (ForeignKeyAnalysisIssue issue : fkAnalysis.issues()) {
            String category = classifyIssue(issue, reviewTables, manifestByTable.keySet());
            classifiedFkIssues.add(new ClassifiedFkIssue(issue, category));
            if (issue.severity() == ForeignKeyAnalysisSeverity.ERROR) {
                switch (category) {
                    case "REVIEW_DEPENDENCY" -> reviewDependencyErrors++;
                    case "UNEXPECTED_MISSING_TARGET" -> unexpectedMissingTargetErrors++;
                    default -> otherFkErrors++;
                }
            }
        }

        DdlGenerator generator = new DdlGenerator(DialectFactory.create(DatabasePlatform.MARIADB), integrated);
        MariaDbDdlSanityChecker sanityChecker = new MariaDbDdlSanityChecker();
        List<RenderFailure> renderFailures = new ArrayList<>();
        int renderedTables = 0;
        long renderedStatements = 0;
        for (Table table : integrated.tables().stream()
                .sorted(Comparator.comparing(t -> key(t.qualifiedName())))
                .toList()) {
            try {
                List<String> statements = new ArrayList<>();
                statements.add(generator.renderIntegratedCreateTable(table));
                statements.addAll(generator.renderIntegratedTableLocalStatements(table));
                String script = String.join(System.lineSeparator(), statements);
                List<MariaDbDdlSanityChecker.Issue> issues = sanityChecker.inspect(script);
                if (!issues.isEmpty()) {
                    String details = issues.stream().limit(5)
                            .map(issue -> issue.code() + ": " + issue.message())
                            .reduce((a, b) -> a + " | " + b).orElse("");
                    renderFailures.add(new RenderFailure(
                            table.qualifiedName().toString(), snapshotByTable.get(key(table.qualifiedName())),
                            "SANITY_CHECK", details));
                } else {
                    renderedTables++;
                    renderedStatements += statements.size();
                }
            } catch (RuntimeException exception) {
                renderFailures.add(new RenderFailure(
                        table.qualifiedName().toString(), snapshotByTable.get(key(table.qualifiedName())),
                        exception.getClass().getSimpleName(), safeMessage(exception)));
            }
        }

        boolean fullIntegratedReady = contractMismatches.isEmpty()
                && fkAnalysis.deployable()
                && renderFailures.isEmpty();

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path summaryPath = outputRoot.resolve("m10.2-mariadb-readiness-summary_" + timestamp + ".txt");
        Path fkIssuesPath = outputRoot.resolve("m10.2-mariadb-fk-issues_" + timestamp + ".csv");
        Path renderFailuresPath = outputRoot.resolve("m10.2-mariadb-render-failures_" + timestamp + ".csv");
        Path contractMismatchPath = outputRoot.resolve("m10.2-mariadb-contract-mismatches_" + timestamp + ".csv");
        Path selectedPath = outputRoot.resolve("m10.2-mariadb-selected_" + timestamp + ".csv");
        Path scriptPath = outputRoot.resolve("mariadb").resolve("integrated-auto-selected.mariadb.sql");

        Files.createDirectories(scriptPath.getParent());
        Files.writeString(fkIssuesPath, fkIssuesCsv(classifiedFkIssues), StandardCharsets.UTF_8);
        Files.writeString(renderFailuresPath, renderFailuresCsv(renderFailures), StandardCharsets.UTF_8);
        Files.writeString(contractMismatchPath, contractMismatchesCsv(contractMismatches), StandardCharsets.UTF_8);
        Files.writeString(selectedPath, selectedCsv(selectedRows), StandardCharsets.UTF_8);

        int integratedStatementCount = 0;
        if (fullIntegratedReady) {
            IntegratedSchemaDeploymentPlan plan = planner.plan(integrated);
            IntegratedSqlScript script = new IntegratedSqlRenderer(DialectFactory.create(DatabasePlatform.MARIADB))
                    .render(integrated, plan);
            sanityChecker.requireValid(script.combinedSql(), "M10.2 integrated auto-selected MariaDB script");
            Files.writeString(scriptPath, script.combinedSql() + System.lineSeparator(), StandardCharsets.UTF_8);
            integratedStatementCount = script.renderedChunkCount();
        } else {
            Files.writeString(scriptPath,
                    "-- M10.2 integrated script intentionally not emitted as executable SQL.\n"
                            + "-- fullIntegratedReady=false; see readiness reports in the parent directory.\n",
                    StandardCharsets.UTF_8);
        }

        String summary = summary(
                inputRoot, autoSelectedFile, manifestFile,
                selectedRows.size(), manifestRows.size(), reviewTables.size(),
                integrated.tables().size(), fkAnalysis,
                reviewDependencyErrors, unexpectedMissingTargetErrors, otherFkErrors,
                renderedTables, renderedStatements, renderFailures.size(),
                contractMismatches.size(), fullIntegratedReady, integratedStatementCount,
                summaryPath, fkIssuesPath, renderFailuresPath, contractMismatchPath, selectedPath, scriptPath);
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        boolean failOnContractMismatch = Boolean.parseBoolean(
                System.getProperty(FAIL_ON_CONTRACT_MISMATCH, "true"));
        if (failOnContractMismatch) {
            assertEquals(0, contractMismatches.size(),
                    "M10.2 selection contract mismatch; see " + contractMismatchPath);
        }
    }

    private static String classifyIssue(
            ForeignKeyAnalysisIssue issue,
            Set<String> reviewTables,
            Set<String> manifestTables) {
        if (issue.severity() != ForeignKeyAnalysisSeverity.ERROR) {
            return issue.code().name();
        }
        if (issue.code() == ForeignKeyAnalysisCode.MISSING_REFERENCED_TABLE) {
            String referenced = key(issue.referencedTable());
            if (reviewTables.contains(referenced)) return "REVIEW_DEPENDENCY";
            if (!manifestTables.contains(referenced)) return "UNEXPECTED_MISSING_TARGET";
            return "MISSING_SELECTED_TARGET";
        }
        return issue.code().name();
    }

    private static DatabaseSchema buildSchema(List<Table> tables) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("M10_AUTO_SELECTED_MARIADB");
        tables.forEach(builder::addTable);
        return builder.build();
    }

    private static String summary(
            Path inputRoot,
            Path autoSelectedFile,
            Path manifestFile,
            int selectedRows,
            int manifestRows,
            int reviewTables,
            int integratedTables,
            ForeignKeyAnalysisResult fkAnalysis,
            long reviewDependencyErrors,
            long unexpectedMissingTargetErrors,
            long otherFkErrors,
            int renderedTables,
            long renderedStatements,
            int renderFailures,
            int contractMismatches,
            boolean fullIntegratedReady,
            int integratedStatementCount,
            Path summaryPath,
            Path fkIssuesPath,
            Path renderFailuresPath,
            Path contractMismatchPath,
            Path selectedPath,
            Path scriptPath) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge M10.2 MariaDB integrated readiness").append(nl);
        text.append("=============================================").append(nl);
        text.append("Canonical input               : ").append(inputRoot).append(nl);
        text.append("M10.1 auto-selected           : ").append(autoSelectedFile).append(nl);
        text.append("M10.1 manifest                : ").append(manifestFile).append(nl);
        text.append("Manifest rows                 : ").append(manifestRows).append(nl);
        text.append("Review tables                 : ").append(reviewTables).append(nl);
        text.append("Selected rows                 : ").append(selectedRows).append(nl);
        text.append("Integrated selected tables    : ").append(integratedTables).append(nl);
        text.append("Contract mismatches           : ").append(contractMismatches).append(nl);
        text.append(nl);
        text.append("Foreign-key readiness").append(nl);
        text.append("---------------------").append(nl);
        text.append("Foreign keys                  : ").append(fkAnalysis.foreignKeys()).append(nl);
        text.append("Physical foreign keys         : ").append(fkAnalysis.physicalForeignKeys()).append(nl);
        text.append("Logical foreign keys          : ").append(fkAnalysis.logicalForeignKeys()).append(nl);
        text.append("Resolved physical FKs         : ").append(fkAnalysis.resolvedPhysicalForeignKeys()).append(nl);
        text.append("FK error count                : ").append(fkAnalysis.errorCount()).append(nl);
        text.append("Review dependency errors      : ").append(reviewDependencyErrors).append(nl);
        text.append("Unexpected missing targets    : ").append(unexpectedMissingTargetErrors).append(nl);
        text.append("Other FK errors               : ").append(otherFkErrors).append(nl);
        text.append("Cycle groups                  : ").append(fkAnalysis.cycleGroups()).append(nl);
        text.append(nl);
        text.append("MariaDB local rendering").append(nl);
        text.append("-----------------------").append(nl);
        text.append("Tables rendered cleanly       : ").append(renderedTables).append(nl);
        text.append("Local statements rendered     : ").append(renderedStatements).append(nl);
        text.append("Render/mapping failures       : ").append(renderFailures).append(nl);
        text.append(nl);
        text.append("Full integrated ready         : ").append(fullIntegratedReady).append(nl);
        text.append("Integrated statement count    : ").append(integratedStatementCount).append(nl);
        text.append("Decision rule                 : NO_GUESS; unresolved review dependencies are not dropped").append(nl);
        text.append(nl);
        text.append("Outputs").append(nl);
        text.append("-------").append(nl);
        text.append("Summary                       : ").append(summaryPath).append(nl);
        text.append("FK issues                     : ").append(fkIssuesPath).append(nl);
        text.append("Render failures               : ").append(renderFailuresPath).append(nl);
        text.append("Contract mismatches           : ").append(contractMismatchPath).append(nl);
        text.append("Selected evidence             : ").append(selectedPath).append(nl);
        text.append("Integrated SQL candidate      : ").append(scriptPath).append(nl);
        return text.toString();
    }

    private static String fkIssuesCsv(List<ClassifiedFkIssue> issues) {
        StringBuilder csv = new StringBuilder("severity,code,category,table,foreign_key,referenced_table,message\n");
        for (ClassifiedFkIssue classified : issues) {
            ForeignKeyAnalysisIssue issue = classified.issue();
            csv.append(csv(issue.severity().name())).append(',')
                    .append(csv(issue.code().name())).append(',')
                    .append(csv(classified.category())).append(',')
                    .append(csv(issue.table())).append(',')
                    .append(csv(issue.foreignKey())).append(',')
                    .append(csv(issue.referencedTable())).append(',')
                    .append(csv(issue.message())).append('\n');
        }
        return csv.toString();
    }

    private static String renderFailuresCsv(List<RenderFailure> failures) {
        StringBuilder csv = new StringBuilder("table,selected_snapshot,failure_type,message\n");
        for (RenderFailure failure : failures) {
            csv.append(csv(failure.table())).append(',')
                    .append(csv(failure.snapshot())).append(',')
                    .append(csv(failure.failureType())).append(',')
                    .append(csv(failure.message())).append('\n');
        }
        return csv.toString();
    }

    private static String contractMismatchesCsv(List<String> mismatches) {
        StringBuilder csv = new StringBuilder("table,code,message\n");
        for (String mismatch : mismatches) {
            List<String> parts = splitThree(mismatch);
            csv.append(csv(parts.get(0))).append(',')
                    .append(csv(parts.get(1))).append(',')
                    .append(csv(parts.get(2))).append('\n');
        }
        return csv.toString();
    }

    private static List<String> splitThree(String value) {
        int first = value.indexOf(',');
        int second = first < 0 ? -1 : value.indexOf(',', first + 1);
        if (first < 0) return List.of(value, "", "");
        if (second < 0) return List.of(value.substring(0, first), value.substring(first + 1), "");
        return List.of(value.substring(0, first), value.substring(first + 1, second), value.substring(second + 1));
    }

    private static String selectedCsv(List<Map<String, String>> rows) {
        StringBuilder csv = new StringBuilder("table,status,selected_snapshot,selected_source,selected_source_sha256,exact_logical_sha256\n");
        rows.stream().sorted(Comparator.comparing(row -> key(row.get("table")))).forEach(row -> csv
                .append(csv(row.get("table"))).append(',')
                .append(csv(row.get("status"))).append(',')
                .append(csv(row.get("selected_snapshot"))).append(',')
                .append(csv(row.get("selected_source"))).append(',')
                .append(csv(row.get("selected_source_sha256"))).append(',')
                .append(csv(row.get("exact_logical_sha256"))).append('\n'));
        return csv.toString();
    }

    private static List<Map<String, String>> readCsv(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> headers = parseCsvLine(lines.getFirst());
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = parseCsvLine(lines.get(i));
            if (values.size() != headers.size()) {
                throw new IllegalArgumentException("Malformed CSV row " + (i + 1) + " in " + file
                        + ": expected " + headers.size() + " cells but found " + values.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), values.get(j));
            result.add(row);
        }
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV value: " + line);
        values.add(current.toString());
        return values;
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required CSV column is blank: " + column);
        }
        return value.trim();
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Directory does not exist: " + path);
        return path;
    }

    private static Path requiredFile(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-m10.2-mariadb-readiness")
                .toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static void prepareOutput(Path outputRoot) throws Exception {
        boolean clean = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        if (clean && Files.exists(outputRoot)) {
            try (var paths = Files.walk(outputRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
        Files.createDirectories(outputRoot);
    }

    private static String key(QualifiedName name) {
        return key(name.toString());
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.toString() : message.replace('\r', ' ').replace('\n', ' ');
    }

    private record ClassifiedFkIssue(ForeignKeyAnalysisIssue issue, String category) {}

    private record RenderFailure(String table, String snapshot, String failureType, String message) {}
}
