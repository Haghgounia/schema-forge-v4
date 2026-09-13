package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
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
import com.behsazan.schemaforge.validation.postgresql.PostgreSqlDdlSanityChecker;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG-P7 derives a deterministic, dependency-closed PostgreSQL cohort from the Selection auto-selected
 * tables without mutating, dropping, or rewriting any canonical foreign key.
 *
 * <p>The cohort is a deployment pilot, not the final reconciled schema. Tables with local PostgreSQL
 * mapping/rendering failures are excluded first. The runner then repeatedly removes only FK owner
 * tables that still have blocking physical-FK errors. This process continues until the remaining
 * set is closed under all physical FK dependencies and {@link ForeignKeyAnalyzer} reports it as
 * deployable. Every exclusion and its reason is emitted as evidence.</p>
 */
class CanonicalJsonPostgreSqlClosedSubsetP7IT {
    private static final String INPUT_DIR = "schemaforge.postgresql.p7.inputDir";
    private static final String AUTO_SELECTED = "schemaforge.postgresql.p7.autoSelected";
    private static final String MANIFEST = "schemaforge.postgresql.p7.manifest";
    private static final String OUTPUT_DIR = "schemaforge.postgresql.p7.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.postgresql.p7.cleanOutput";
    private static final String FAIL_ON_INVARIANT = "schemaforge.postgresql.p7.failOnInvariant";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void derivesDependencyClosedPostgreSqlPilotCohort() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path autoSelectedFile = requiredFile(AUTO_SELECTED);
        Path manifestFile = requiredFile(MANIFEST);
        Path outputRoot = outputDirectory(inputRoot);
        prepareOutput(outputRoot);

        List<Map<String, String>> selectedRows = readCsv(autoSelectedFile);
        List<Map<String, String>> manifestRows = readCsv(manifestFile);
        assertFalse(selectedRows.isEmpty(), "Selection auto-selected CSV is empty: " + autoSelectedFile);

        Map<String, Map<String, String>> manifestByTable = new LinkedHashMap<>();
        for (Map<String, String> row : manifestRows) {
            String table = key(required(row, "table"));
            Map<String, String> previous = manifestByTable.putIfAbsent(table, row);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate table in Selection manifest: " + table);
            }
        }

        List<String> contractMismatches = new ArrayList<>();
        Map<String, Table> selectedTables = new LinkedHashMap<>();
        Map<String, String> snapshotByTable = new LinkedHashMap<>();
        Map<String, String> statusByTable = new LinkedHashMap<>();

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
            statusByTable.put(tableKey, status);
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

        DatabaseSchema fullSelectedSchema = buildSchema(selectedTables.values().stream()
                .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                .toList());

        DdlGenerator fullGenerator = new DdlGenerator(DialectFactory.create(DatabasePlatform.POSTGRESQL), fullSelectedSchema);
        PostgreSqlDdlSanityChecker sanityChecker = new PostgreSqlDdlSanityChecker();

        Map<String, Exclusion> exclusions = new LinkedHashMap<>();
        for (Table table : fullSelectedSchema.tables().stream()
                .sorted(Comparator.comparing(t -> key(t.qualifiedName())))
                .toList()) {
            String tableKey = key(table.qualifiedName());
            try {
                List<String> statements = new ArrayList<>();
                statements.add(fullGenerator.renderIntegratedCreateTable(table));
                statements.addAll(fullGenerator.renderIntegratedTableLocalStatements(table));
                String script = String.join(System.lineSeparator(), statements);
                List<PostgreSqlDdlSanityChecker.Issue> issues = sanityChecker.inspect(script);
                if (!issues.isEmpty()) {
                    String details = issues.stream().limit(5)
                            .map(issue -> issue.code() + ": " + issue.message())
                            .collect(Collectors.joining(" | "));
                    exclusions.put(tableKey, new Exclusion(
                            table.qualifiedName().toString(), snapshotByTable.get(tableKey), 0,
                            "LOCAL_RENDER_FAILURE", "SANITY_CHECK", "", details));
                }
            } catch (RuntimeException exception) {
                exclusions.put(tableKey, new Exclusion(
                        table.qualifiedName().toString(), snapshotByTable.get(tableKey), 0,
                        "LOCAL_RENDER_FAILURE", exception.getClass().getSimpleName(), "", safeMessage(exception)));
            }
        }

        LinkedHashSet<String> remaining = new LinkedHashSet<>(selectedTables.keySet());
        remaining.removeAll(exclusions.keySet());
        List<IterationStat> iterations = new ArrayList<>();

        int iteration = 1;
        while (true) {
            DatabaseSchema candidateSchema = buildSchema(remaining.stream()
                    .map(selectedTables::get)
                    .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                    .toList());
            ForeignKeyAnalysisResult analysis = analyzer.analyze(candidateSchema);
            List<ForeignKeyAnalysisIssue> errors = analysis.issues().stream()
                    .filter(issue -> issue.severity() == ForeignKeyAnalysisSeverity.ERROR)
                    .toList();
            if (errors.isEmpty()) {
                iterations.add(new IterationStat(iteration, remaining.size(), 0, 0));
                break;
            }

            Map<String, List<ForeignKeyAnalysisIssue>> errorsByOwner = new LinkedHashMap<>();
            for (ForeignKeyAnalysisIssue issue : errors) {
                String owner = key(issue.table());
                if (owner.isBlank()) {
                    throw new IllegalStateException("PG-P7 FK blocker has no owner table: " + issue);
                }
                errorsByOwner.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(issue);
            }

            int removed = 0;
            for (Map.Entry<String, List<ForeignKeyAnalysisIssue>> entry : errorsByOwner.entrySet()) {
                String ownerKey = entry.getKey();
                if (!remaining.remove(ownerKey)) continue;
                removed++;
                List<ForeignKeyAnalysisIssue> ownerIssues = entry.getValue();
                String codes = ownerIssues.stream().map(issue -> issue.code().name()).distinct().sorted()
                        .collect(Collectors.joining(";"));
                String references = ownerIssues.stream().map(ForeignKeyAnalysisIssue::referencedTable)
                        .filter(value -> value != null && !value.isBlank()).distinct().sorted()
                        .collect(Collectors.joining(";"));
                String details = ownerIssues.stream()
                        .map(issue -> issue.code().name() + ":" + issue.foreignKey() + ":" + issue.message())
                        .collect(Collectors.joining(" | "));
                Table ownerTable = selectedTables.get(ownerKey);
                exclusions.putIfAbsent(ownerKey, new Exclusion(
                        ownerTable == null ? entry.getKey() : ownerTable.qualifiedName().toString(),
                        snapshotByTable.get(ownerKey), iteration,
                        "FK_CLOSURE_PRUNE", codes, references, details));
            }

            iterations.add(new IterationStat(iteration, remaining.size() + removed, errors.size(), removed));
            if (removed == 0) {
                throw new IllegalStateException("PG-P7 cannot make progress while FK errors remain: " + errors.size());
            }
            iteration++;
            if (iteration > selectedTables.size() + 1) {
                throw new IllegalStateException("PG-P7 exceeded safe closure iteration limit");
            }
        }

        DatabaseSchema closedSchema = buildSchema(remaining.stream()
                .map(selectedTables::get)
                .sorted(Comparator.comparing(table -> key(table.qualifiedName())))
                .toList());
        ForeignKeyAnalysisResult closedAnalysis = analyzer.analyze(closedSchema);

        boolean finalDeployable = closedAnalysis.deployable();
        int integratedStatementCount = 0;
        String integratedSql = "";
        String integratedRenderFailure = "";
        if (contractMismatches.isEmpty() && finalDeployable) {
            try {
                IntegratedSchemaDeploymentPlan plan = planner.plan(closedSchema);
                IntegratedSqlScript script = new IntegratedSqlRenderer(DialectFactory.create(DatabasePlatform.POSTGRESQL))
                        .render(closedSchema, plan);
                sanityChecker.requireValid(script.combinedSql(), "PG-P7 PostgreSQL dependency-closed safe cohort");
                integratedSql = script.combinedSql() + System.lineSeparator();
                integratedStatementCount = script.renderedChunkCount();
            } catch (RuntimeException exception) {
                integratedRenderFailure = safeMessage(exception);
            }
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path summaryPath = outputRoot.resolve("postgresql-p7-closed-subset-summary_" + timestamp + ".txt");
        Path selectedPath = outputRoot.resolve("postgresql-p7-closed-subset-selected_" + timestamp + ".csv");
        Path exclusionsPath = outputRoot.resolve("postgresql-p7-closed-subset-exclusions_" + timestamp + ".csv");
        Path iterationsPath = outputRoot.resolve("postgresql-p7-closed-subset-iterations_" + timestamp + ".csv");
        Path contractMismatchPath = outputRoot.resolve("postgresql-p7-contract-mismatches_" + timestamp + ".csv");
        Path scriptPath = outputRoot.resolve("postgresql").resolve("integrated-closed-safe-subset.postgresql.sql");

        Files.createDirectories(scriptPath.getParent());
        Files.writeString(selectedPath, selectedCsv(remaining, statusByTable, snapshotByTable), StandardCharsets.UTF_8);
        Files.writeString(exclusionsPath, exclusionsCsv(exclusions), StandardCharsets.UTF_8);
        Files.writeString(iterationsPath, iterationsCsv(iterations), StandardCharsets.UTF_8);
        Files.writeString(contractMismatchPath, contractMismatchesCsv(contractMismatches), StandardCharsets.UTF_8);
        if (integratedSql.isEmpty()) {
            Files.writeString(scriptPath,
                    "-- PG-P7 closed-subset script intentionally not emitted as executable SQL.\n"
                            + "-- finalDeployable=" + finalDeployable + "; integratedRenderFailure="
                            + integratedRenderFailure.replace('\n', ' ').replace('\r', ' ') + "\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(scriptPath, integratedSql, StandardCharsets.UTF_8);
        }

        long localRenderExclusions = exclusions.values().stream()
                .filter(exclusion -> "LOCAL_RENDER_FAILURE".equals(exclusion.category())).count();
        long fkClosureExclusions = exclusions.values().stream()
                .filter(exclusion -> "FK_CLOSURE_PRUNE".equals(exclusion.category())).count();
        boolean closedSubsetReady = contractMismatches.isEmpty()
                && finalDeployable
                && integratedRenderFailure.isBlank()
                && !integratedSql.isBlank();

        String summary = summary(
                inputRoot, autoSelectedFile, manifestFile,
                selectedRows.size(), manifestRows.size(), contractMismatches.size(),
                localRenderExclusions, fkClosureExclusions, exclusions.size(), remaining.size(),
                iterations.size(), closedAnalysis, closedSubsetReady, integratedStatementCount,
                integratedRenderFailure, summaryPath, selectedPath, exclusionsPath,
                iterationsPath, contractMismatchPath, scriptPath);
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        boolean failOnInvariant = Boolean.parseBoolean(System.getProperty(FAIL_ON_INVARIANT, "true"));
        if (failOnInvariant) {
            assertEquals(0, contractMismatches.size(), "PG-P7 selection contract mismatch; see " + contractMismatchPath);
            assertTrue(finalDeployable, "PG-P7 closed subset must be FK-deployable");
            assertTrue(integratedRenderFailure.isBlank(), "PG-P7 integrated rendering failed: " + integratedRenderFailure);
            assertTrue(closedSubsetReady, "PG-P7 closed subset was not emitted as executable SQL");
        }
    }

    private static DatabaseSchema buildSchema(List<Table> tables) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("PG_P7_CLOSED_SAFE_POSTGRESQL");
        tables.forEach(builder::addTable);
        return builder.build();
    }

    private static String summary(
            Path inputRoot,
            Path autoSelectedFile,
            Path manifestFile,
            int selectedRows,
            int manifestRows,
            int contractMismatches,
            long localRenderExclusions,
            long fkClosureExclusions,
            int totalExclusions,
            int finalTables,
            int closureIterations,
            ForeignKeyAnalysisResult closedAnalysis,
            boolean closedSubsetReady,
            int integratedStatementCount,
            String integratedRenderFailure,
            Path summaryPath,
            Path selectedPath,
            Path exclusionsPath,
            Path iterationsPath,
            Path contractMismatchPath,
            Path scriptPath) {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge PostgreSQL PG-P7 dependency-closed safe cohort").append(nl);
        text.append("=======================================================").append(nl);
        text.append("Canonical input               : ").append(inputRoot).append(nl);
        text.append("Selection auto-selected           : ").append(autoSelectedFile).append(nl);
        text.append("Selection manifest                : ").append(manifestFile).append(nl);
        text.append("Manifest rows                 : ").append(manifestRows).append(nl);
        text.append("Initial selected tables       : ").append(selectedRows).append(nl);
        text.append("Contract mismatches           : ").append(contractMismatches).append(nl);
        text.append(nl);
        text.append("Deterministic pruning").append(nl);
        text.append("---------------------").append(nl);
        text.append("Local render exclusions       : ").append(localRenderExclusions).append(nl);
        text.append("FK closure exclusions         : ").append(fkClosureExclusions).append(nl);
        text.append("Total excluded tables         : ").append(totalExclusions).append(nl);
        text.append("Closure iterations            : ").append(closureIterations).append(nl);
        text.append("Final closed cohort tables    : ").append(finalTables).append(nl);
        text.append(nl);
        text.append("Final FK state").append(nl);
        text.append("--------------").append(nl);
        text.append("Foreign keys                  : ").append(closedAnalysis.foreignKeys()).append(nl);
        text.append("Physical foreign keys         : ").append(closedAnalysis.physicalForeignKeys()).append(nl);
        text.append("Resolved physical FKs         : ").append(closedAnalysis.resolvedPhysicalForeignKeys()).append(nl);
        text.append("FK error count                : ").append(closedAnalysis.errorCount()).append(nl);
        text.append("Cycle groups                  : ").append(closedAnalysis.cycleGroups()).append(nl);
        text.append(nl);
        text.append("Integrated PostgreSQL pilot").append(nl);
        text.append("-------------------------").append(nl);
        text.append("Closed subset ready           : ").append(closedSubsetReady).append(nl);
        text.append("Integrated statement count    : ").append(integratedStatementCount).append(nl);
        text.append("Integrated render failure     : ").append(integratedRenderFailure).append(nl);
        text.append("Decision rule                 : no FK/type rewrite; blocker owner tables are excluded transitively").append(nl);
        text.append("Scope                         : deployment pilot only; NOT the final reconciled schema").append(nl);
        text.append(nl);
        text.append("Outputs").append(nl);
        text.append("-------").append(nl);
        text.append("Summary                       : ").append(summaryPath).append(nl);
        text.append("Closed selected cohort        : ").append(selectedPath).append(nl);
        text.append("Exclusions                    : ").append(exclusionsPath).append(nl);
        text.append("Closure iterations            : ").append(iterationsPath).append(nl);
        text.append("Contract mismatches           : ").append(contractMismatchPath).append(nl);
        text.append("Integrated SQL                : ").append(scriptPath).append(nl);
        return text.toString();
    }

    private static String selectedCsv(
            Set<String> remaining,
            Map<String, String> statusByTable,
            Map<String, String> snapshotByTable) {
        StringBuilder csv = new StringBuilder("table,m10_1_status,selected_snapshot,cohort_status\n");
        remaining.stream().sorted().forEach(table -> csv
                .append(csv(table)).append(',')
                .append(csv(statusByTable.get(table))).append(',')
                .append(csv(snapshotByTable.get(table))).append(',')
                .append("SAFE_CLOSED_SUBSET").append('\n'));
        return csv.toString();
    }

    private static String exclusionsCsv(Map<String, Exclusion> exclusions) {
        StringBuilder csv = new StringBuilder(
                "table,selected_snapshot,iteration,category,codes,referenced_tables,details\n");
        exclusions.values().stream()
                .sorted(Comparator.comparingInt(Exclusion::iteration).thenComparing(exclusion -> key(exclusion.table())))
                .forEach(exclusion -> csv
                        .append(csv(exclusion.table())).append(',')
                        .append(csv(exclusion.snapshot())).append(',')
                        .append(exclusion.iteration()).append(',')
                        .append(csv(exclusion.category())).append(',')
                        .append(csv(exclusion.codes())).append(',')
                        .append(csv(exclusion.referencedTables())).append(',')
                        .append(csv(exclusion.details())).append('\n'));
        return csv.toString();
    }

    private static String iterationsCsv(List<IterationStat> iterations) {
        StringBuilder csv = new StringBuilder("iteration,tables_before,fk_errors,removed_tables\n");
        for (IterationStat iteration : iterations) {
            csv.append(iteration.iteration()).append(',')
                    .append(iteration.tablesBefore()).append(',')
                    .append(iteration.fkErrors()).append(',')
                    .append(iteration.removedTables()).append('\n');
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
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-postgresql-p7-closed-subset")
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

    private record Exclusion(
            String table,
            String snapshot,
            int iteration,
            String category,
            String codes,
            String referencedTables,
            String details) {}

    private record IterationStat(int iteration, int tablesBefore, int fkErrors, int removedTables) {}
}
