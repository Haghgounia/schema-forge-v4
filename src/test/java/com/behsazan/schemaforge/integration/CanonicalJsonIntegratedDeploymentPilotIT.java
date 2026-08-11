package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisIssue;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalyzer;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlan;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.deployment.IntegratedSqlScript;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates a small real integrated-deployment pilot directly from canonical JSON snapshots.
 *
 * <p>This is deliberately a test-only runner. Production integrated input still requires exactly
 * one definition per qualified table. Historical regression corpora may contain several versions;
 * for pilot purposes only, this runner may choose a deterministic FK-compatible closure and writes
 * every selected snapshot/source to a report so no version choice is hidden.</p>
 *
 * <p>No Word document is opened and no database connection is made. The output is one ordered
 * integrated SQL script per selected DBMS, ready for the subsequent small database pilot.</p>
 */
class CanonicalJsonIntegratedDeploymentPilotIT {
    private static final String INPUT_DIR = "schemaforge.integrated.pilot.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.integrated.pilot.outputDir";
    private static final String PLATFORMS = "schemaforge.integrated.pilot.platforms";
    private static final String SEED_TABLE = "schemaforge.integrated.pilot.seedTable";
    private static final String MAX_TABLES = "schemaforge.integrated.pilot.maxTables";
    private static final String TARGET_TABLES = "schemaforge.integrated.pilot.targetTables";
    private static final String MIN_PHYSICAL_FKS = "schemaforge.integrated.pilot.minPhysicalForeignKeys";
    private static final String MIN_CHAIN_DEPTH = "schemaforge.integrated.pilot.minFkChainDepth";
    private static final String ALLOW_DISCONNECTED_EXPANSION = "schemaforge.integrated.pilot.allowDisconnectedExpansion";
    private static final String ALLOW_HISTORICAL_SELECTION = "schemaforge.integrated.pilot.allowHistoricalSelection";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void generatesSmallIntegratedPilotFromCanonicalJson() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        Files.createDirectories(outputRoot);

        int maxTables = Integer.parseInt(System.getProperty(MAX_TABLES, "8"));
        if (maxTables < 2 || maxTables > 50) {
            throw new IllegalArgumentException(MAX_TABLES + " must be between 2 and 50");
        }
        int targetTables = Integer.parseInt(System.getProperty(TARGET_TABLES, "2"));
        int minPhysicalForeignKeys = Integer.parseInt(System.getProperty(MIN_PHYSICAL_FKS, "1"));
        int minChainDepth = Integer.parseInt(System.getProperty(MIN_CHAIN_DEPTH, "1"));
        boolean allowDisconnectedExpansion = Boolean.parseBoolean(
                System.getProperty(ALLOW_DISCONNECTED_EXPANSION, "false"));
        if (targetTables < 2 || targetTables > maxTables) {
            throw new IllegalArgumentException(TARGET_TABLES + " must be between 2 and " + maxTables);
        }
        if (minPhysicalForeignKeys < 1) {
            throw new IllegalArgumentException(MIN_PHYSICAL_FKS + " must be at least 1");
        }
        if (minChainDepth < 1) {
            throw new IllegalArgumentException(MIN_CHAIN_DEPTH + " must be at least 1");
        }
        PilotRequirements requirements = new PilotRequirements(
                targetTables, minPhysicalForeignKeys, minChainDepth, allowDisconnectedExpansion);
        boolean allowHistoricalSelection = Boolean.parseBoolean(
                System.getProperty(ALLOW_HISTORICAL_SELECTION, "false"));
        List<DatabasePlatform> platforms = platforms();

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonIntegratedDeploymentPilotIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertFalse(snapshots.isEmpty(), "No canonical JSON snapshots found under " + inputRoot);

        List<TableOccurrence> occurrences = new ArrayList<>();
        List<String> snapshotErrors = new ArrayList<>();
        for (Path snapshotPath : snapshots) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                DatabaseSchema schema = mapper.toDomain(snapshot);
                String source = snapshot.source() == null || snapshot.source().relativePath() == null
                        ? "" : snapshot.source().relativePath();
                String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
                for (Table table : schema.tables()) {
                    occurrences.add(new TableOccurrence(
                            tableKey(table.qualifiedName()), table, schema.sequences(),
                            relativeSnapshot, source));
                }
            } catch (Exception exception) {
                snapshotErrors.add(normalize(inputRoot.relativize(snapshotPath)) + " -> "
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }
        assertTrue(snapshotErrors.isEmpty(), "Snapshot failures: " + String.join(" | ", snapshotErrors.stream().limit(5).toList()));

        Map<String, List<TableOccurrence>> byTable = groupByTable(occurrences);
        long duplicateOccurrences = byTable.values().stream().mapToLong(group -> Math.max(0, group.size() - 1)).sum();
        if (duplicateOccurrences > 0 && !allowHistoricalSelection) {
            throw new IllegalStateException(
                    "INPUT_DUPLICATE_TABLE: historical input contains " + duplicateOccurrences
                            + " duplicate table occurrences. For this test-only pilot either provide a normal unique input"
                            + " directory or set -D" + ALLOW_HISTORICAL_SELECTION + "=true. Production integrated"
                            + " deployment never performs historical version selection.");
        }

        String requestedSeed = trimToNull(System.getProperty(SEED_TABLE));
        PilotSelection selection = requestedSeed == null
                ? autoSelect(byTable, maxTables, platforms, requirements)
                : selectRequested(byTable, requestedSeed, maxTables, platforms, requirements);

        DatabaseSchema integrated = buildSchema(selection.selected());
        ForeignKeyAnalysisResult analysis = analyzer.analyze(integrated);
        assertTrue(analysis.deployable(), "Selected pilot is not deployable: " + blockerSummary(analysis));
        assertTrue(analysis.physicalForeignKeys() > 0,
                "Pilot must contain at least one physical foreign key to test integrated deployment");

        IntegratedSchemaDeploymentPlan plan = planner.plan(integrated);
        Map<DatabasePlatform, IntegratedSqlScript> scripts = new LinkedHashMap<>();
        for (DatabasePlatform platform : platforms) {
            IntegratedSqlScript script = new IntegratedSqlRenderer(DialectFactory.create(platform))
                    .render(integrated, plan);
            scripts.put(platform, script);
            Path platformDir = Files.createDirectories(outputRoot.resolve(platform.commandLineName()));
            Files.writeString(platformDir.resolve("integrated-pilot." + platform.commandLineName() + ".sql"),
                    script.combinedSql() + System.lineSeparator(), StandardCharsets.UTF_8);
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path selectedReport = outputRoot.resolve("integrated-pilot-selected_" + timestamp + ".csv");
        Path issueReport = outputRoot.resolve("integrated-pilot-fk-issues_" + timestamp + ".csv");
        Path summaryReport = outputRoot.resolve("integrated-pilot-summary_" + timestamp + ".txt");
        Files.writeString(selectedReport, selectedCsv(selection), StandardCharsets.UTF_8);
        Files.writeString(issueReport, issuesCsv(analysis), StandardCharsets.UTF_8);
        String summary = summary(inputRoot, outputRoot, snapshots.size(), byTable.size(), duplicateOccurrences,
                allowHistoricalSelection, selection, analysis, plan, scripts, requirements);
        Files.writeString(summaryReport, summary, StandardCharsets.UTF_8);

        System.out.print(summary);
        System.out.println("Selected report : " + selectedReport);
        System.out.println("FK issues report: " + issueReport);
        System.out.println("Summary report  : " + summaryReport);
    }

    private PilotSelection autoSelect(
            Map<String, List<TableOccurrence>> byTable, int maxTables, List<DatabasePlatform> platforms,
            PilotRequirements requirements) {
        List<TableOccurrence> seeds = byTable.values().stream()
                .flatMap(List::stream)
                .filter(occurrence -> physicalForeignKeyCount(occurrence.table()) > 0)
                .sorted(Comparator
                        .comparingInt((TableOccurrence occurrence) -> byTable.get(occurrence.tableKey()).size())
                        .thenComparingInt(occurrence -> physicalForeignKeyCount(occurrence.table()))
                        .thenComparing(TableOccurrence::tableKey)
                        .thenComparing(TableOccurrence::snapshot))
                .toList();

        for (TableOccurrence seed : seeds) {
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>();
            if (selectClosure(seed, byTable, selected, new LinkedHashSet<>(), maxTables)) {
                expandSelection(selected, byTable, maxTables, platforms, requirements);
                DatabaseSchema schema = buildSchema(selected.values().stream().toList());
                ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
                PilotMetrics metrics = metrics(schema, analysis);
                if (analysis.deployable() && meetsRequirements(metrics, requirements)
                        && renderableOnPlatforms(schema, platforms)) {
                    return new PilotSelection(seed.table().qualifiedName().toString(),
                            requirements.targetTables() > 2
                                    ? "AUTO_TEST_ONLY_CROSS_DIALECT_LARGE_PILOT"
                                    : "AUTO_TEST_ONLY_CROSS_DIALECT_COMPATIBLE_CLOSURE",
                            selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                            metrics);
                }
            }
        }
        throw new IllegalStateException(
                "No deployable cross-dialect pilot satisfying targetTables=" + requirements.targetTables()
                        + ", minPhysicalForeignKeys=" + requirements.minPhysicalForeignKeys()
                        + ", minFkChainDepth=" + requirements.minChainDepth()
                        + " could be selected within maxTables=" + maxTables + ".");
    }

    private PilotSelection selectRequested(
            Map<String, List<TableOccurrence>> byTable, String requestedSeed, int maxTables,
            List<DatabasePlatform> platforms, PilotRequirements requirements) {
        String key = requestedSeed.trim().toUpperCase(Locale.ROOT);
        List<TableOccurrence> candidates = byTable.getOrDefault(key, List.of());
        if (candidates.isEmpty() && !key.contains(".")) {
            candidates = byTable.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("." + key) || entry.getKey().equals(key))
                    .flatMap(entry -> entry.getValue().stream())
                    .sorted(Comparator.comparing(TableOccurrence::snapshot))
                    .toList();
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Pilot seed table not found: " + requestedSeed);
        }
        for (TableOccurrence seed : candidates) {
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>();
            if (selectClosure(seed, byTable, selected, new LinkedHashSet<>(), maxTables)) {
                expandSelection(selected, byTable, maxTables, platforms, requirements);
                DatabaseSchema schema = buildSchema(selected.values().stream().toList());
                ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
                PilotMetrics metrics = metrics(schema, analysis);
                if (analysis.deployable() && meetsRequirements(metrics, requirements)
                        && renderableOnPlatforms(schema, platforms)) {
                    return new PilotSelection(seed.table().qualifiedName().toString(),
                            "EXPLICIT_SEED_CROSS_DIALECT_COMPATIBLE_CLOSURE",
                            selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                            metrics);
                }
            }
        }
        throw new IllegalStateException("No deployable FK closure found for pilot seed " + requestedSeed
                + " within maxTables=" + maxTables);
    }


    private void expandSelection(
            LinkedHashMap<String, TableOccurrence> selected,
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            List<DatabasePlatform> platforms,
            PilotRequirements requirements) {
        while (selected.size() < requirements.targetTables() && selected.size() < maxTables) {
            ExpansionCandidate best = bestExpansion(selected, byTable, maxTables, platforms, true);
            if (best == null && requirements.allowDisconnectedExpansion()) {
                best = bestExpansion(selected, byTable, maxTables, platforms, false);
            }
            if (best == null) return;
            selected.clear();
            selected.putAll(best.selected());
        }
    }

    private ExpansionCandidate bestExpansion(
            LinkedHashMap<String, TableOccurrence> selected,
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            List<DatabasePlatform> platforms,
            boolean connectedOnly) {
        List<TableOccurrence> candidates = byTable.values().stream()
                .flatMap(List::stream)
                .filter(candidate -> !selected.containsKey(candidate.tableKey()))
                .filter(candidate -> physicalForeignKeyCount(candidate.table()) > 0)
                .filter(candidate -> !connectedOnly || touchesSelection(candidate, selected))
                .sorted(Comparator
                        .comparingInt((TableOccurrence candidate) -> touchesSelection(candidate, selected) ? 0 : 1)
                        .thenComparing((TableOccurrence candidate) -> -physicalForeignKeyCount(candidate.table()))
                        .thenComparingInt(candidate -> byTable.get(candidate.tableKey()).size())
                        .thenComparing(TableOccurrence::tableKey)
                        .thenComparing(TableOccurrence::snapshot))
                .limit(250)
                .toList();

        ExpansionCandidate best = null;
        for (TableOccurrence candidate : candidates) {
            LinkedHashMap<String, TableOccurrence> trial = new LinkedHashMap<>(selected);
            if (!selectClosure(candidate, byTable, trial, new LinkedHashSet<>(), maxTables)) continue;
            if (trial.size() <= selected.size() || trial.size() > maxTables) continue;
            DatabaseSchema schema;
            ForeignKeyAnalysisResult analysis;
            try {
                schema = buildSchema(trial.values().stream().toList());
                analysis = analyzer.analyze(schema);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!analysis.deployable() || !renderableOnPlatforms(schema, platforms)) continue;
            PilotMetrics metrics = metrics(schema, analysis);
            ExpansionCandidate current = new ExpansionCandidate(trial, metrics);
            if (best == null || better(current, best)) best = current;
        }
        return best;
    }

    private static boolean better(ExpansionCandidate left, ExpansionCandidate right) {
        int cmp = Integer.compare(left.metrics().chainDepth(), right.metrics().chainDepth());
        if (cmp != 0) return cmp > 0;
        cmp = Integer.compare(left.metrics().physicalForeignKeys(), right.metrics().physicalForeignKeys());
        if (cmp != 0) return cmp > 0;
        cmp = Integer.compare(left.metrics().tables(), right.metrics().tables());
        if (cmp != 0) return cmp > 0;
        return String.join("|", left.selected().keySet()).compareTo(String.join("|", right.selected().keySet())) < 0;
    }

    private static boolean touchesSelection(
            TableOccurrence candidate, LinkedHashMap<String, TableOccurrence> selected) {
        for (ForeignKey foreignKey : candidate.table().foreignKeys()) {
            if (foreignKey.physicalReference()
                    && selected.containsKey(resolvedTargetKey(candidate.table(), foreignKey))) {
                return true;
            }
        }
        for (TableOccurrence owner : selected.values()) {
            for (ForeignKey foreignKey : owner.table().foreignKeys()) {
                if (foreignKey.physicalReference()
                        && resolvedTargetKey(owner.table(), foreignKey).equals(candidate.tableKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean meetsRequirements(PilotMetrics metrics, PilotRequirements requirements) {
        return metrics.tables() >= requirements.targetTables()
                && metrics.physicalForeignKeys() >= requirements.minPhysicalForeignKeys()
                && metrics.chainDepth() >= requirements.minChainDepth();
    }

    private static PilotMetrics metrics(DatabaseSchema schema, ForeignKeyAnalysisResult analysis) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Table table : schema.tables()) graph.put(tableKey(table.qualifiedName()), new LinkedHashSet<>());
        for (Table table : schema.tables()) {
            String owner = tableKey(table.qualifiedName());
            for (ForeignKey foreignKey : table.foreignKeys()) {
                if (!foreignKey.physicalReference()) continue;
                String target = resolvedTargetKey(table, foreignKey);
                if (graph.containsKey(target)) graph.get(owner).add(target);
            }
        }
        int chainDepth = 0;
        for (String start : graph.keySet()) {
            chainDepth = Math.max(chainDepth, longestSimplePath(start, graph, new LinkedHashSet<>()));
        }
        int components = undirectedComponents(graph);
        return new PilotMetrics(schema.tables().size(), analysis.physicalForeignKeys(), chainDepth,
                analysis.selfReferences(), analysis.cycleGroups(), components);
    }

    private static int longestSimplePath(String node, Map<String, Set<String>> graph, Set<String> visiting) {
        if (!visiting.add(node)) return 0;
        int best = 0;
        for (String target : graph.getOrDefault(node, Set.of())) {
            if (!visiting.contains(target)) {
                best = Math.max(best, 1 + longestSimplePath(target, graph, visiting));
            }
        }
        visiting.remove(node);
        return best;
    }

    private static int undirectedComponents(Map<String, Set<String>> graph) {
        Map<String, Set<String>> undirected = new LinkedHashMap<>();
        graph.keySet().forEach(key -> undirected.put(key, new LinkedHashSet<>()));
        graph.forEach((owner, targets) -> targets.forEach(target -> {
            if (undirected.containsKey(target)) {
                undirected.get(owner).add(target);
                undirected.get(target).add(owner);
            }
        }));
        Set<String> seen = new LinkedHashSet<>();
        int components = 0;
        for (String start : undirected.keySet()) {
            if (!seen.add(start)) continue;
            components++;
            List<String> queue = new ArrayList<>();
            queue.add(start);
            for (int index = 0; index < queue.size(); index++) {
                String current = queue.get(index);
                for (String next : undirected.getOrDefault(current, Set.of())) {
                    if (seen.add(next)) queue.add(next);
                }
            }
        }
        return components;
    }


    private boolean renderableOnPlatforms(DatabaseSchema schema, List<DatabasePlatform> platforms) {
        IntegratedSchemaDeploymentPlan plan;
        try {
            plan = planner.plan(schema);
        } catch (RuntimeException exception) {
            return false;
        }
        for (DatabasePlatform platform : platforms) {
            try {
                new IntegratedSqlRenderer(DialectFactory.create(platform)).render(schema, plan);
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return true;
    }

    private boolean selectClosure(
            TableOccurrence occurrence,
            Map<String, List<TableOccurrence>> byTable,
            LinkedHashMap<String, TableOccurrence> selected,
            Set<String> visiting,
            int maxTables) {
        TableOccurrence existing = selected.get(occurrence.tableKey());
        if (existing != null) {
            return existing.snapshot().equals(occurrence.snapshot());
        }
        if (selected.size() >= maxTables) return false;

        LinkedHashMap<String, TableOccurrence> beforeOccurrence = new LinkedHashMap<>(selected);
        selected.put(occurrence.tableKey(), occurrence);
        if (!visiting.add(occurrence.tableKey())) return true;

        List<ForeignKey> physicalForeignKeys = occurrence.table().foreignKeys().stream()
                .filter(ForeignKey::physicalReference)
                .toList();
        boolean resolved = selectForeignKeys(
                occurrence, physicalForeignKeys, 0, byTable, selected, visiting, maxTables);
        visiting.remove(occurrence.tableKey());
        if (!resolved) {
            restore(selected, beforeOccurrence);
        }
        return resolved && selected.size() <= maxTables;
    }

    private boolean selectForeignKeys(
            TableOccurrence owner,
            List<ForeignKey> foreignKeys,
            int index,
            Map<String, List<TableOccurrence>> byTable,
            LinkedHashMap<String, TableOccurrence> selected,
            Set<String> visiting,
            int maxTables) {
        if (index >= foreignKeys.size()) return true;

        ForeignKey foreignKey = foreignKeys.get(index);
        String targetKey = resolvedTargetKey(owner.table(), foreignKey);
        TableOccurrence alreadySelected = selected.get(targetKey);
        if (alreadySelected != null) {
            return compatibleTarget(alreadySelected.table(), foreignKey)
                    && selectForeignKeys(owner, foreignKeys, index + 1, byTable, selected, visiting, maxTables);
        }

        List<TableOccurrence> targetCandidates = byTable.getOrDefault(targetKey, List.of()).stream()
                .filter(candidate -> compatibleTarget(candidate.table(), foreignKey))
                .sorted(Comparator.comparing(TableOccurrence::snapshot))
                .toList();
        for (TableOccurrence target : targetCandidates) {
            LinkedHashMap<String, TableOccurrence> beforeTarget = new LinkedHashMap<>(selected);
            if (selectClosure(target, byTable, selected, visiting, maxTables)
                    && selectForeignKeys(owner, foreignKeys, index + 1, byTable, selected, visiting, maxTables)) {
                return true;
            }
            restore(selected, beforeTarget);
        }
        return false;
    }

    private static boolean compatibleTarget(Table target, ForeignKey foreignKey) {
        if (foreignKey.referencedColumns().stream()
                .anyMatch(column -> target.findColumn(column.value()).isEmpty())) {
            return false;
        }
        List<String> expected = foreignKey.referencedColumns().stream().map(Identifier::normalized).toList();
        if (target.primaryKey().map(pk -> pk.columns().stream().map(Identifier::normalized).toList().equals(expected))
                .orElse(false)) {
            return true;
        }
        for (UniqueKey uniqueKey : target.uniqueKeys()) {
            if (uniqueKey.columns().stream().map(Identifier::normalized).toList().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static DatabaseSchema buildSchema(List<TableOccurrence> selected) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("INTEGRATED_PILOT");
        selected.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey))
                .map(TableOccurrence::table)
                .forEach(builder::addTable);

        Map<String, Sequence> sequences = new LinkedHashMap<>();
        for (TableOccurrence occurrence : selected) {
            for (Sequence sequence : occurrence.sequences()) {
                String sequenceKey = tableKey(sequence.qualifiedName());
                Sequence previous = sequences.putIfAbsent(sequenceKey, sequence);
                if (previous != null) {
                    throw new IllegalStateException(
                            "INPUT_DUPLICATE_SEQUENCE in selected pilot: " + sequence.qualifiedName());
                }
            }
        }
        sequences.values().stream()
                .sorted(Comparator.comparing(sequence -> tableKey(sequence.qualifiedName())))
                .forEach(builder::addSequence);
        return builder.build();
    }

    private static Map<String, List<TableOccurrence>> groupByTable(List<TableOccurrence> occurrences) {
        Map<String, List<TableOccurrence>> grouped = new LinkedHashMap<>();
        occurrences.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey).thenComparing(TableOccurrence::snapshot))
                .forEach(occurrence -> grouped.computeIfAbsent(occurrence.tableKey(), ignored -> new ArrayList<>())
                        .add(occurrence));
        return grouped;
    }

    private static String resolvedTargetKey(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) return tableKey(referenced);
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return tableKey(QualifiedName.of(ownerSchema, referenced.name().value()));
    }

    private static int physicalForeignKeyCount(Table table) {
        return (int) table.foreignKeys().stream().filter(ForeignKey::physicalReference).count();
    }

    private static void restore(
            LinkedHashMap<String, TableOccurrence> selected,
            LinkedHashMap<String, TableOccurrence> snapshot) {
        selected.clear();
        selected.putAll(snapshot);
    }

    private static String selectedCsv(PilotSelection selection) {
        List<String> lines = new ArrayList<>();
        lines.add("table,snapshot,source,physical_foreign_keys");
        for (TableOccurrence occurrence : selection.selected()) {
            lines.add(csvLine(occurrence.table().qualifiedName().toString(), occurrence.snapshot(),
                    occurrence.source(), Integer.toString(physicalForeignKeyCount(occurrence.table()))));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String issuesCsv(ForeignKeyAnalysisResult analysis) {
        List<String> lines = new ArrayList<>();
        lines.add("severity,code,table,foreign_key,referenced_table,message");
        for (ForeignKeyAnalysisIssue issue : analysis.issues()) {
            lines.add(csvLine(issue.severity().name(), issue.code().name(), issue.table(), issue.foreignKey(),
                    issue.referencedTable(), issue.message()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String summary(
            Path inputRoot,
            Path outputRoot,
            int snapshots,
            int distinctTables,
            long duplicateOccurrences,
            boolean historicalSelection,
            PilotSelection selection,
            ForeignKeyAnalysisResult analysis,
            IntegratedSchemaDeploymentPlan plan,
            Map<DatabasePlatform, IntegratedSqlScript> scripts,
            PilotRequirements requirements) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge integrated deployment pilot").append(System.lineSeparator());
        text.append("=======================================").append(System.lineSeparator());
        text.append("Input directory        : ").append(inputRoot).append(System.lineSeparator());
        text.append("Output directory       : ").append(outputRoot).append(System.lineSeparator());
        text.append("Snapshots discovered   : ").append(snapshots).append(System.lineSeparator());
        text.append("Distinct table names   : ").append(distinctTables).append(System.lineSeparator());
        text.append("Duplicate occurrences  : ").append(duplicateOccurrences).append(System.lineSeparator());
        text.append("Historical test select : ").append(historicalSelection).append(System.lineSeparator());
        text.append("Target tables          : ").append(requirements.targetTables()).append(System.lineSeparator());
        text.append("Minimum physical FKs   : ").append(requirements.minPhysicalForeignKeys()).append(System.lineSeparator());
        text.append("Minimum FK chain depth : ").append(requirements.minChainDepth()).append(System.lineSeparator());
        text.append("Disconnected expansion : ").append(requirements.allowDisconnectedExpansion()).append(System.lineSeparator());
        text.append("Selection mode         : ").append(selection.mode()).append(System.lineSeparator());
        text.append("Seed table             : ").append(selection.seedTable()).append(System.lineSeparator());
        text.append("Pilot tables           : ").append(selection.selected().size()).append(System.lineSeparator());
        text.append("FK chain depth         : ").append(selection.metrics().chainDepth()).append(System.lineSeparator());
        text.append("Connected components   : ").append(selection.metrics().connectedComponents()).append(System.lineSeparator());
        text.append("Self references        : ").append(selection.metrics().selfReferences()).append(System.lineSeparator());
        text.append("Physical FKs           : ").append(analysis.physicalForeignKeys()).append(System.lineSeparator());
        text.append("Resolved physical FKs  : ").append(analysis.resolvedPhysicalForeignKeys()).append(System.lineSeparator());
        text.append("Logical FKs            : ").append(analysis.logicalForeignKeys()).append(System.lineSeparator());
        text.append("Dependency cycles      : ").append(analysis.cycleGroups()).append(System.lineSeparator());
        text.append("FK blockers            : ").append(analysis.errorCount()).append(System.lineSeparator());
        text.append("Phase 1 tables         : ").append(plan.phase1Tables().size()).append(System.lineSeparator());
        text.append("Phase 2 local objects  : ").append(plan.phase2ObjectCount()).append(System.lineSeparator());
        text.append("Phase 3 foreign keys   : ").append(plan.phase3ForeignKeys().size()).append(System.lineSeparator());
        text.append("Phase 4 metadata tables: ").append(plan.phase4MetadataTables().size()).append(System.lineSeparator());
        for (Map.Entry<DatabasePlatform, IntegratedSqlScript> entry : scripts.entrySet()) {
            text.append(entry.getKey().commandLineName()).append(" rendered chunks")
                    .append(" : ").append(entry.getValue().renderedChunkCount()).append(System.lineSeparator());
        }
        text.append("Database execution     : NOT RUN").append(System.lineSeparator());
        return text.toString();
    }

    private static String blockerSummary(ForeignKeyAnalysisResult analysis) {
        return analysis.issues().stream()
                .filter(issue -> "ERROR".equals(issue.severity().name()))
                .limit(5)
                .map(issue -> issue.code() + ": " + issue.message())
                .reduce((left, right) -> left + " | " + right)
                .orElse("unknown FK blocker");
    }

    private static List<DatabasePlatform> platforms() {
        String configured = System.getProperty(PLATFORMS, "oracle,postgresql,sqlserver");
        List<DatabasePlatform> result = new ArrayList<>();
        for (String token : configured.split(",")) {
            if (!token.isBlank()) {
                DatabasePlatform platform = DatabasePlatform.parse(token);
                if (platform == DatabasePlatform.DB2_ZOS) {
                    throw new IllegalArgumentException("Integrated pilot currently targets oracle, postgresql, sqlserver");
                }
                if (!result.contains(platform)) result.add(platform);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No pilot platform selected");
        return List.copyOf(result);
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
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-integrated-pilot").toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String tableKey(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
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

    private record TableOccurrence(
            String tableKey,
            Table table,
            List<Sequence> sequences,
            String snapshot,
            String source) {
        private TableOccurrence {
            Objects.requireNonNull(tableKey);
            Objects.requireNonNull(table);
            sequences = List.copyOf(Objects.requireNonNull(sequences));
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(source);
        }
    }

    private record PilotRequirements(
            int targetTables,
            int minPhysicalForeignKeys,
            int minChainDepth,
            boolean allowDisconnectedExpansion) {
    }

    private record PilotMetrics(
            int tables,
            int physicalForeignKeys,
            int chainDepth,
            int selfReferences,
            int cycleGroups,
            int connectedComponents) {
    }

    private record ExpansionCandidate(
            LinkedHashMap<String, TableOccurrence> selected,
            PilotMetrics metrics) {
        private ExpansionCandidate {
            selected = new LinkedHashMap<>(selected);
        }
    }

    private record PilotSelection(
            String seedTable,
            String mode,
            List<TableOccurrence> selected,
            PilotMetrics metrics) {
        private PilotSelection {
            selected = List.copyOf(selected);
        }
    }
}
