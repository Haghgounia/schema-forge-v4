package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisIssue;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlan;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.deployment.IntegratedSqlScript;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds dedicated integrated pilots for real self-references and historical aggregate cycle candidates.
 *
 * <p>This runner is intentionally test-only. It never changes the production one-version-per-table rule.
 * Historical selection is used only to determine whether a coverage edge can coexist in one deployable
 * canonical schema. A cycle that cannot coexist is classified as {@code HISTORICAL_AGGREGATE_ONLY} and
 * is not rendered for database execution.</p>
 */
class CanonicalJsonSpecialDependencyPilotIT {
    private static final String INPUT_DIR = "schemaforge.special.pilot.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.special.pilot.outputDir";
    private static final String PLATFORMS = "schemaforge.special.pilot.platforms";
    private static final String MAX_TABLES = "schemaforge.special.pilot.maxTables";
    private static final String MAX_CYCLE_COMBINATIONS = "schemaforge.special.pilot.maxCycleCombinations";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final HistoricalDependencyCoverage coverageAnalyzer = new HistoricalDependencyCoverage();
    private final SpecialDependencyPilotSelector selector = new SpecialDependencyPilotSelector();
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void buildsDedicatedSelfReferenceAndCyclePilots() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        Files.createDirectories(outputRoot);
        int maxTables = integerProperty(MAX_TABLES, 20, 1, 50);
        int maxCycleCombinations = integerProperty(MAX_CYCLE_COMBINATIONS, 20_000, 1, 1_000_000);
        List<DatabasePlatform> platforms = platforms();

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonSpecialDependencyPilotIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertFalse(snapshots.isEmpty(), "No canonical JSON snapshots found under " + inputRoot);

        List<HistoricalDependencyCoverage.Definition> definitions = new ArrayList<>();
        List<SpecialDependencyPilotSelector.TableOccurrence> occurrences = new ArrayList<>();
        List<String> snapshotErrors = new ArrayList<>();
        for (Path snapshotPath : snapshots) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                DatabaseSchema schema = mapper.toDomain(snapshot);
                String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
                String source = snapshot.source() == null || snapshot.source().relativePath() == null
                        ? "" : snapshot.source().relativePath();
                definitions.add(new HistoricalDependencyCoverage.Definition(relativeSnapshot, source, schema));
                for (Table table : schema.tables()) {
                    occurrences.add(new SpecialDependencyPilotSelector.TableOccurrence(
                            SpecialDependencyPilotSelector.tableKey(table.qualifiedName()),
                            table, schema.sequences(), relativeSnapshot, source));
                }
            } catch (Exception exception) {
                snapshotErrors.add(normalize(inputRoot.relativize(snapshotPath)) + " -> "
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }
        assertTrue(snapshotErrors.isEmpty(),
                "Snapshot failures: " + String.join(" | ", snapshotErrors.stream().limit(5).toList()));

        HistoricalDependencyCoverage.Result coverage = coverageAnalyzer.analyze(definitions);
        Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> byTable =
                SpecialDependencyPilotSelector.groupByTable(occurrences);

        SpecialDependencyPilotSelector.SelfReferenceAssessment selfAssessment =
                selector.assessSelfReference(byTable, maxTables, platforms);
        SpecialDependencyPilotSelector.SelfReferenceSelection selfSelection = selfAssessment.selection();
        if (selfAssessment.deployable()) {
            renderSelection(outputRoot.resolve("self-reference"), "integrated-self-reference",
                    selfSelection.selected(), platforms);
        }

        List<SpecialDependencyPilotSelector.CycleAssessment> cycles = selector.assessCycles(
                byTable, coverage.cycles(), maxTables, maxCycleCombinations, platforms);
        for (SpecialDependencyPilotSelector.CycleAssessment cycle : cycles) {
            if (!cycle.deployable()) continue;
            String cycleName = String.format(Locale.ROOT, "cycle-%02d", cycle.ordinal());
            renderSelection(outputRoot.resolve(cycleName), "integrated-" + cycleName,
                    cycle.selected(), platforms);
        }

        assertTrue(cycles.stream().noneMatch(cycle ->
                        cycle.status() == SpecialDependencyPilotSelector.CycleStatus.INCONCLUSIVE_COMBINATION_LIMIT),
                "Cycle coverage is inconclusive because the combination limit was reached; increase -D"
                        + MAX_CYCLE_COMBINATIONS);

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path selectedReport = outputRoot.resolve("special-dependency-selected_" + timestamp + ".csv");
        Path cycleReport = outputRoot.resolve("special-dependency-cycles_" + timestamp + ".csv");
        Path issueReport = outputRoot.resolve("special-dependency-fk-issues_" + timestamp + ".csv");
        Path summaryReport = outputRoot.resolve("special-dependency-summary_" + timestamp + ".txt");
        Files.writeString(selectedReport, selectedCsv(selfAssessment, cycles), StandardCharsets.UTF_8);
        Files.writeString(cycleReport, cycleCsv(cycles), StandardCharsets.UTF_8);
        Files.writeString(issueReport, issuesCsv(selfAssessment, cycles), StandardCharsets.UTF_8);
        String summary = summary(
                inputRoot, outputRoot, snapshots.size(), coverage, selfAssessment, cycles,
                platforms, maxTables, maxCycleCombinations);
        Files.writeString(summaryReport, summary, StandardCharsets.UTF_8);

        System.out.print(summary);
        System.out.println("Selected report : " + selectedReport);
        System.out.println("Cycle report    : " + cycleReport);
        System.out.println("FK issues report: " + issueReport);
        System.out.println("Summary report  : " + summaryReport);
    }

    private void renderSelection(
            Path pilotRoot,
            String baseName,
            List<SpecialDependencyPilotSelector.TableOccurrence> selected,
            List<DatabasePlatform> platforms) throws Exception {
        Files.createDirectories(pilotRoot);
        DatabaseSchema schema = SpecialDependencyPilotSelector.buildSchema(selected, baseName.toUpperCase(Locale.ROOT));
        IntegratedSchemaDeploymentPlan plan = planner.plan(schema);
        for (DatabasePlatform platform : platforms) {
            IntegratedSqlScript script = new IntegratedSqlRenderer(DialectFactory.create(platform)).render(schema, plan);
            Path platformDir = Files.createDirectories(pilotRoot.resolve(platform.commandLineName()));
            Path sqlFile = platformDir.resolve(baseName + "." + platform.commandLineName() + ".sql");
            Files.writeString(sqlFile, script.combinedSql() + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    private static String selectedCsv(
            SpecialDependencyPilotSelector.SelfReferenceAssessment selfAssessment,
            List<SpecialDependencyPilotSelector.CycleAssessment> cycles) {
        SpecialDependencyPilotSelector.SelfReferenceSelection selfSelection = selfAssessment.selection();
        List<String> lines = new ArrayList<>();
        lines.add("pilot_type,pilot_id,table,snapshot,source,physical_foreign_keys,selection_mode,omitted_external_physical_fks");
        if (selfSelection != null) {
            for (SpecialDependencyPilotSelector.TableOccurrence occurrence : selfSelection.selected()) {
                lines.add(csvLine("SELF_REFERENCE", "self-reference", occurrence.table().qualifiedName().toString(),
                        occurrence.snapshot(), occurrence.source(),
                        Integer.toString(SpecialDependencyPilotSelector.physicalForeignKeyCount(occurrence.table())),
                        selfSelection.mode().name(),
                        Integer.toString(selfSelection.omittedExternalPhysicalForeignKeys())));
            }
        }
        for (SpecialDependencyPilotSelector.CycleAssessment cycle : cycles) {
            if (!cycle.deployable()) continue;
            String id = String.format(Locale.ROOT, "cycle-%02d", cycle.ordinal());
            for (SpecialDependencyPilotSelector.TableOccurrence occurrence : cycle.selected()) {
                lines.add(csvLine("CYCLE", id, occurrence.table().qualifiedName().toString(),
                        occurrence.snapshot(), occurrence.source(),
                        Integer.toString(SpecialDependencyPilotSelector.physicalForeignKeyCount(occurrence.table())),
                        "FULL_CLOSURE", "0"));
            }
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String cycleCsv(List<SpecialDependencyPilotSelector.CycleAssessment> cycles) {
        List<String> lines = new ArrayList<>();
        lines.add("cycle_id,members,status,combinations_evaluated,selected_tables,reason");
        for (SpecialDependencyPilotSelector.CycleAssessment cycle : cycles) {
            lines.add(csvLine(
                    String.format(Locale.ROOT, "cycle-%02d", cycle.ordinal()),
                    String.join("|", cycle.members()),
                    cycle.status().name(),
                    Integer.toString(cycle.combinationsEvaluated()),
                    cycle.selected().stream().map(occurrence -> occurrence.table().qualifiedName().toString())
                            .reduce((left, right) -> left + "|" + right).orElse(""),
                    cycle.reason()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String issuesCsv(
            SpecialDependencyPilotSelector.SelfReferenceAssessment selfAssessment,
            List<SpecialDependencyPilotSelector.CycleAssessment> cycles) {
        SpecialDependencyPilotSelector.SelfReferenceSelection selfSelection = selfAssessment.selection();
        List<String> lines = new ArrayList<>();
        lines.add("pilot_type,pilot_id,severity,code,table,foreign_key,referenced_table,message");
        if (selfSelection != null) {
            appendIssues(lines, "SELF_REFERENCE", "self-reference", selfSelection.analysis());
        }
        for (SpecialDependencyPilotSelector.CycleAssessment cycle : cycles) {
            if (cycle.analysis() != null) {
                appendIssues(lines, "CYCLE", String.format(Locale.ROOT, "cycle-%02d", cycle.ordinal()),
                        cycle.analysis());
            }
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static void appendIssues(
            List<String> lines,
            String pilotType,
            String pilotId,
            ForeignKeyAnalysisResult analysis) {
        for (ForeignKeyAnalysisIssue issue : analysis.issues()) {
            lines.add(csvLine(pilotType, pilotId, issue.severity().name(), issue.code().name(),
                    issue.table(), issue.foreignKey(), issue.referencedTable(), issue.message()));
        }
    }

    private static String summary(
            Path inputRoot,
            Path outputRoot,
            int snapshots,
            HistoricalDependencyCoverage.Result coverage,
            SpecialDependencyPilotSelector.SelfReferenceAssessment selfAssessment,
            List<SpecialDependencyPilotSelector.CycleAssessment> cycles,
            List<DatabasePlatform> platforms,
            int maxTables,
            int maxCycleCombinations) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge special dependency pilot").append(System.lineSeparator());
        text.append("====================================").append(System.lineSeparator());
        text.append("Input directory                  : ").append(inputRoot).append(System.lineSeparator());
        text.append("Output directory                 : ").append(outputRoot).append(System.lineSeparator());
        text.append("Snapshots discovered             : ").append(snapshots).append(System.lineSeparator());
        text.append("Snapshots loaded                 : ").append(coverage.snapshotDefinitions()).append(System.lineSeparator());
        text.append("Distinct table names             : ").append(coverage.distinctTables()).append(System.lineSeparator());
        text.append("Duplicate occurrences            : ").append(coverage.duplicateOccurrences()).append(System.lineSeparator());
        text.append("Distinct physical FK relations   : ").append(coverage.distinctPhysicalRelations()).append(System.lineSeparator());
        text.append("Self-reference definitions       : ").append(coverage.selfReferenceDefinitions()).append(System.lineSeparator());
        text.append("Distinct self-reference relations: ").append(coverage.distinctSelfReferenceRelations()).append(System.lineSeparator());
        text.append("Aggregate cycle candidate groups : ").append(coverage.aggregateCycleGroups()).append(System.lineSeparator());
        text.append("Max selected tables              : ").append(maxTables).append(System.lineSeparator());
        text.append("Max cycle combinations           : ").append(maxCycleCombinations).append(System.lineSeparator());
        text.append("Platforms                        : ")
                .append(platforms.stream().map(DatabasePlatform::commandLineName)
                        .reduce((left, right) -> left + "," + right).orElse(""))
                .append(System.lineSeparator());
        text.append(System.lineSeparator());

        SpecialDependencyPilotSelector.SelfReferenceSelection selfSelection = selfAssessment.selection();
        text.append("Self-reference status            : ").append(selfAssessment.status()).append(System.lineSeparator());
        text.append("Self-reference reason            : ").append(selfAssessment.reason()).append(System.lineSeparator());
        if (selfSelection != null) {
            text.append("Self-reference mode              : ").append(selfSelection.mode()).append(System.lineSeparator());
            text.append("Self-reference seed              : ").append(selfSelection.seedTable()).append(System.lineSeparator());
            text.append("Self-reference pilot tables      : ").append(selfSelection.selected().size()).append(System.lineSeparator());
            text.append("Self-reference resolved FKs      : ")
                    .append(selfSelection.analysis().resolvedPhysicalForeignKeys()).append(System.lineSeparator());
            text.append("Self-reference relations in pilot: ")
                    .append(selfSelection.analysis().selfReferences()).append(System.lineSeparator());
            text.append("Self-reference omitted external FKs: ")
                    .append(selfSelection.omittedExternalPhysicalForeignKeys()).append(System.lineSeparator());
        }
        text.append(System.lineSeparator());

        long deployableCycles = cycles.stream().filter(SpecialDependencyPilotSelector.CycleAssessment::deployable).count();
        long aggregateOnly = cycles.stream().filter(cycle ->
                cycle.status() == SpecialDependencyPilotSelector.CycleStatus.HISTORICAL_AGGREGATE_ONLY).count();
        text.append("Deployable cycle pilots          : ").append(deployableCycles).append(System.lineSeparator());
        text.append("Historical-aggregate-only cycles : ").append(aggregateOnly).append(System.lineSeparator());
        for (SpecialDependencyPilotSelector.CycleAssessment cycle : cycles) {
            text.append(String.format(Locale.ROOT, "Cycle %02d status                  : %s%n",
                    cycle.ordinal(), cycle.status()));
            text.append(String.format(Locale.ROOT, "Cycle %02d members                 : %s%n",
                    cycle.ordinal(), String.join(" <-> ", cycle.members())));
            text.append(String.format(Locale.ROOT, "Cycle %02d combinations            : %d%n",
                    cycle.ordinal(), cycle.combinationsEvaluated()));
            if (cycle.deployable()) {
                text.append(String.format(Locale.ROOT, "Cycle %02d selected tables         : %d%n",
                        cycle.ordinal(), cycle.selected().size()));
                text.append(String.format(Locale.ROOT, "Cycle %02d resolved FKs            : %d%n",
                        cycle.ordinal(), cycle.analysis().resolvedPhysicalForeignKeys()));
            }
            text.append(String.format(Locale.ROOT, "Cycle %02d reason                  : %s%n",
                    cycle.ordinal(), cycle.reason()));
        }
        text.append("Database execution                : NOT RUN").append(System.lineSeparator());
        return text.toString();
    }

    private static List<DatabasePlatform> platforms() {
        String configured = System.getProperty(PLATFORMS, "oracle,postgresql,sqlserver");
        List<DatabasePlatform> result = new ArrayList<>();
        for (String token : configured.split(",")) {
            if (token.isBlank()) continue;
            DatabasePlatform platform = DatabasePlatform.parse(token);
            if (platform == DatabasePlatform.DB2_ZOS) {
                throw new IllegalArgumentException("Special dependency pilot currently targets oracle, postgresql, sqlserver");
            }
            if (!result.contains(platform)) result.add(platform);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No special dependency pilot platform selected");
        return List.copyOf(result);
    }

    private static int integerProperty(String property, int defaultValue, int min, int max) {
        int value = Integer.parseInt(System.getProperty(property, Integer.toString(defaultValue)));
        if (value < min || value > max) {
            throw new IllegalArgumentException(property + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Input directory does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String configured = trimToNull(System.getProperty(OUTPUT_DIR));
        return configured == null
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-special-dependency-pilot")
                        .toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String text = value == null ? "" : value;
            escaped.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
