package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalyzer;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramFileWriter;
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
 * Generates real Mermaid diagram artifacts from canonical JSON snapshots.
 *
 * <p>This runner is deliberately test-only. Production diagram export accepts a normal canonical
 * input with one definition per qualified table. Historical regression corpora may contain several
 * versions of the same table; when explicitly enabled, this runner chooses a deterministic
 * FK-compatible closure and reports every selected snapshot/source so the historical selection is
 * never hidden.</p>
 *
 * <p>No Word document is opened, no SQL is generated, and no database connection is made.</p>
 */
class CanonicalJsonMermaidPilotIT {
    private static final String INPUT_DIR = "schemaforge.diagram.pilot.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.diagram.pilot.outputDir";
    private static final String SEED_TABLE = "schemaforge.diagram.pilot.seedTable";
    private static final String MAX_TABLES = "schemaforge.diagram.pilot.maxTables";
    private static final String TARGET_TABLES = "schemaforge.diagram.pilot.targetTables";
    private static final String MIN_PHYSICAL_FKS = "schemaforge.diagram.pilot.minPhysicalForeignKeys";
    private static final String MIN_CHAIN_DEPTH = "schemaforge.diagram.pilot.minFkChainDepth";
    private static final String ALLOW_DISCONNECTED_EXPANSION =
            "schemaforge.diagram.pilot.allowDisconnectedExpansion";
    private static final String ALLOW_HISTORICAL_SELECTION =
            "schemaforge.diagram.pilot.allowHistoricalSelection";
    private static final String DEPENDENCY_DEPTH = "schemaforge.diagram.pilot.dependencyDepth";
    private static final String INCLUDE_COLUMNS = "schemaforge.diagram.pilot.includeColumns";
    private static final String INCLUDE_DATA_TYPES = "schemaforge.diagram.pilot.includeDataTypes";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();
    private final MermaidDiagramFileWriter writer = new MermaidDiagramFileWriter();

    @Test
    void generatesMermaidPilotFromCanonicalJson() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        Files.createDirectories(outputRoot);

        int maxTables = integerProperty(MAX_TABLES, 20, 2, 100);
        int targetTables = integerProperty(TARGET_TABLES, 8, 2, maxTables);
        int minPhysicalForeignKeys = integerProperty(MIN_PHYSICAL_FKS, 3, 1, Integer.MAX_VALUE);
        int minChainDepth = integerProperty(MIN_CHAIN_DEPTH, 2, 1, Integer.MAX_VALUE);
        int dependencyDepth = integerProperty(DEPENDENCY_DEPTH, 2, 0, 20);
        boolean allowDisconnectedExpansion = booleanProperty(ALLOW_DISCONNECTED_EXPANSION, false);
        boolean allowHistoricalSelection = booleanProperty(ALLOW_HISTORICAL_SELECTION, false);
        boolean includeColumns = booleanProperty(INCLUDE_COLUMNS, true);
        boolean includeDataTypes = booleanProperty(INCLUDE_DATA_TYPES, true);
        PilotRequirements requirements = new PilotRequirements(
                targetTables, minPhysicalForeignKeys, minChainDepth, allowDisconnectedExpansion);

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonMermaidPilotIT::isSnapshot)
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
                            tableKey(table.qualifiedName()), table, schema.sequences(), relativeSnapshot, source));
                }
            } catch (Exception exception) {
                snapshotErrors.add(normalize(inputRoot.relativize(snapshotPath)) + " -> "
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }
        assertTrue(snapshotErrors.isEmpty(), "Snapshot failures: "
                + String.join(" | ", snapshotErrors.stream().limit(5).toList()));

        Map<String, List<TableOccurrence>> byTable = groupByTable(occurrences);
        long duplicateOccurrences = byTable.values().stream()
                .mapToLong(group -> Math.max(0, group.size() - 1L)).sum();
        if (duplicateOccurrences > 0 && !allowHistoricalSelection) {
            throw new IllegalStateException(
                    "INPUT_DUPLICATE_TABLE: historical input contains " + duplicateOccurrences
                            + " duplicate table occurrences. For this test-only Mermaid pilot either provide a normal"
                            + " unique input directory or set -D" + ALLOW_HISTORICAL_SELECTION + "=true."
                            + " Production diagram export never performs historical version selection.");
        }

        String requestedSeed = trimToNull(System.getProperty(SEED_TABLE));
        PilotSelection selection = requestedSeed == null
                ? autoSelect(byTable, maxTables, requirements)
                : selectRequested(byTable, requestedSeed, maxTables, requirements);

        DatabaseSchema selectedSchema = buildSchema(selection.selected());
        ForeignKeyAnalysisResult analysis = analyzer.analyze(selectedSchema);
        assertTrue(analysis.deployable(), "Selected Mermaid pilot is not canonically deployable: "
                + blockerSummary(analysis));
        assertTrue(analysis.physicalForeignKeys() >= minPhysicalForeignKeys,
                "Selected pilot has fewer physical FKs than required");

        String rootName = selection.seedTable();
        QualifiedName rootTable = selectedSchema.tables().stream()
                .filter(table -> tableKey(table.qualifiedName()).equals(rootName.toUpperCase(Locale.ROOT)))
                .map(Table::qualifiedName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected seed table not present in pilot: " + rootName));

        String rootToken = fileToken(rootTable.toString());
        Path erDir = Files.createDirectories(outputRoot.resolve("mermaid").resolve("er"));
        Path dependencyDir = Files.createDirectories(outputRoot.resolve("mermaid").resolve("dependency"));

        DiagramExportOptions erFull = DiagramExportOptions.builder()
                .type(DiagramType.ER)
                .scope(DiagramScope.ALL)
                .includeColumns(includeColumns)
                .includeDataTypes(includeDataTypes)
                .includePrimaryKeys(true)
                .includeForeignKeys(true)
                .build();
        DiagramExportOptions dependencyFull = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.ALL)
                .includeForeignKeys(true)
                .build();
        DiagramExportOptions erDepth = DiagramExportOptions.builder()
                .type(DiagramType.ER)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable(rootTable)
                .dependencyDepth(dependencyDepth)
                .includeColumns(includeColumns)
                .includeDataTypes(includeDataTypes)
                .includePrimaryKeys(true)
                .includeForeignKeys(true)
                .build();
        DiagramExportOptions dependencyDepthOptions = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable(rootTable)
                .dependencyDepth(dependencyDepth)
                .includeForeignKeys(true)
                .build();

        List<DiagramArtifact> artifacts = new ArrayList<>();
        artifacts.add(writeArtifact(erDir.resolve(rootToken + "__er-full.mmd"), selectedSchema.tables(), erFull,
                "ER", "ALL", selectedSchema.tables().size(), analysis.resolvedPhysicalForeignKeys()));
        artifacts.add(writeArtifact(dependencyDir.resolve(rootToken + "__dependency-full.mmd"),
                selectedSchema.tables(), dependencyFull, "DEPENDENCY", "ALL",
                selectedSchema.tables().size(), analysis.resolvedPhysicalForeignKeys()));

        Path erDepthFile = erDir.resolve(rootToken + "__er-depth-" + dependencyDepth + ".mmd");
        Path dependencyDepthFile = dependencyDir.resolve(
                rootToken + "__dependency-depth-" + dependencyDepth + ".mmd");
        artifacts.add(writeArtifact(erDepthFile, selectedSchema.tables(), erDepth,
                "ER", "TABLE_WITH_DEPENDENCIES", -1, -1));
        artifacts.add(writeArtifact(dependencyDepthFile, selectedSchema.tables(), dependencyDepthOptions,
                "DEPENDENCY", "TABLE_WITH_DEPENDENCIES", -1, -1));

        assertMermaidFile(erDepthFile, "erDiagram");
        assertMermaidFile(dependencyDepthFile, "flowchart LR");
        assertTrue(Files.readString(dependencyDepthFile, StandardCharsets.UTF_8).contains(rootTable.toString()),
                "Depth dependency diagram does not contain root table " + rootTable);

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path selectedReport = outputRoot.resolve("mermaid-pilot-selected_" + timestamp + ".csv");
        Path manifestReport = outputRoot.resolve("mermaid-pilot-manifest_" + timestamp + ".csv");
        Path summaryReport = outputRoot.resolve("mermaid-pilot-summary_" + timestamp + ".txt");
        Files.writeString(selectedReport, selectedCsv(selection), StandardCharsets.UTF_8);
        Files.writeString(manifestReport, manifestCsv(outputRoot, artifacts), StandardCharsets.UTF_8);
        String summary = summary(inputRoot, outputRoot, snapshots.size(), byTable.size(), duplicateOccurrences,
                allowHistoricalSelection, selection, analysis, dependencyDepth, includeColumns, includeDataTypes,
                artifacts, requirements);
        Files.writeString(summaryReport, summary, StandardCharsets.UTF_8);

        System.out.print(summary);
        System.out.println("Selected report : " + selectedReport);
        System.out.println("Diagram manifest: " + manifestReport);
        System.out.println("Summary report  : " + summaryReport);
    }

    private DiagramArtifact writeArtifact(
            Path file,
            List<Table> tables,
            DiagramExportOptions options,
            String type,
            String scope,
            int tableCount,
            int relationCount) throws Exception {
        writer.write(file, tables, options);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String expectedHeader = "ER".equals(type) ? "erDiagram" : "flowchart LR";
        assertTrue(content.startsWith(expectedHeader), "Unexpected Mermaid header in " + file);
        long size = Files.size(file);
        assertTrue(size > 0, "Empty Mermaid artifact: " + file);
        return new DiagramArtifact(type, scope, file, tableCount, relationCount, size);
    }

    private PilotSelection autoSelect(
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            PilotRequirements requirements) {
        List<TableOccurrence> seeds = byTable.values().stream()
                .flatMap(List::stream)
                .filter(occurrence -> physicalForeignKeyCount(occurrence.table()) > 0)
                .sorted(Comparator
                        .comparingInt((TableOccurrence occurrence) -> -physicalForeignKeyCount(occurrence.table()))
                        .thenComparingInt(occurrence -> byTable.get(occurrence.tableKey()).size())
                        .thenComparing(TableOccurrence::tableKey)
                        .thenComparing(TableOccurrence::snapshot))
                .toList();

        for (TableOccurrence seed : seeds) {
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>();
            if (!selectClosure(seed, byTable, selected, new LinkedHashSet<>(), maxTables)) continue;
            expandSelection(selected, byTable, maxTables, requirements);
            DatabaseSchema schema = buildSchema(selected.values().stream().toList());
            ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
            PilotMetrics metrics = metrics(schema, analysis);
            if (analysis.deployable() && meetsRequirements(metrics, requirements)) {
                return new PilotSelection(seed.table().qualifiedName().toString(),
                        requirements.targetTables() > 2
                                ? "AUTO_TEST_ONLY_HISTORICAL_DIAGRAM_PILOT"
                                : "AUTO_TEST_ONLY_HISTORICAL_FK_CLOSURE",
                        selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                        metrics);
            }
        }
        throw new IllegalStateException(
                "No canonical Mermaid pilot satisfying targetTables=" + requirements.targetTables()
                        + ", minPhysicalForeignKeys=" + requirements.minPhysicalForeignKeys()
                        + ", minFkChainDepth=" + requirements.minChainDepth()
                        + " could be selected within maxTables=" + maxTables + ".");
    }

    private PilotSelection selectRequested(
            Map<String, List<TableOccurrence>> byTable,
            String requestedSeed,
            int maxTables,
            PilotRequirements requirements) {
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
            throw new IllegalArgumentException("Mermaid pilot seed table not found: " + requestedSeed);
        }
        for (TableOccurrence seed : candidates) {
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>();
            if (!selectClosure(seed, byTable, selected, new LinkedHashSet<>(), maxTables)) continue;
            expandSelection(selected, byTable, maxTables, requirements);
            DatabaseSchema schema = buildSchema(selected.values().stream().toList());
            ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
            PilotMetrics metrics = metrics(schema, analysis);
            if (analysis.deployable() && meetsRequirements(metrics, requirements)) {
                return new PilotSelection(seed.table().qualifiedName().toString(),
                        "EXPLICIT_SEED_TEST_ONLY_HISTORICAL_DIAGRAM_PILOT",
                        selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                        metrics);
            }
        }
        throw new IllegalStateException("No compatible Mermaid FK closure found for pilot seed " + requestedSeed
                + " within maxTables=" + maxTables + " satisfying targetTables=" + requirements.targetTables());
    }

    private void expandSelection(
            LinkedHashMap<String, TableOccurrence> selected,
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            PilotRequirements requirements) {
        while (selected.size() < requirements.targetTables() && selected.size() < maxTables) {
            ExpansionCandidate best = bestExpansion(selected, byTable, maxTables, true);
            if (best == null && requirements.allowDisconnectedExpansion()) {
                best = bestExpansion(selected, byTable, maxTables, false);
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
            if (!analysis.deployable()) continue;
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
        return String.join("|", left.selected().keySet())
                .compareTo(String.join("|", right.selected().keySet())) < 0;
    }

    private boolean selectClosure(
            TableOccurrence occurrence,
            Map<String, List<TableOccurrence>> byTable,
            LinkedHashMap<String, TableOccurrence> selected,
            Set<String> visiting,
            int maxTables) {
        TableOccurrence existing = selected.get(occurrence.tableKey());
        if (existing != null) return existing.snapshot().equals(occurrence.snapshot());
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
        if (!resolved) restore(selected, beforeOccurrence);
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
            if (uniqueKey.columns().stream().map(Identifier::normalized).toList().equals(expected)) return true;
        }
        return false;
    }

    private static boolean touchesSelection(
            TableOccurrence candidate,
            LinkedHashMap<String, TableOccurrence> selected) {
        for (ForeignKey foreignKey : candidate.table().foreignKeys()) {
            if (foreignKey.physicalReference()
                    && selected.containsKey(resolvedTargetKey(candidate.table(), foreignKey))) return true;
        }
        for (TableOccurrence owner : selected.values()) {
            for (ForeignKey foreignKey : owner.table().foreignKeys()) {
                if (foreignKey.physicalReference()
                        && resolvedTargetKey(owner.table(), foreignKey).equals(candidate.tableKey())) return true;
            }
        }
        return false;
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
        return new PilotMetrics(schema.tables().size(), analysis.physicalForeignKeys(), chainDepth,
                analysis.selfReferences(), analysis.cycleGroups(), undirectedComponents(graph));
    }

    private static int longestSimplePath(String node, Map<String, Set<String>> graph, Set<String> visiting) {
        if (!visiting.add(node)) return 0;
        int best = 0;
        for (String target : graph.getOrDefault(node, Set.of())) {
            if (!visiting.contains(target)) best = Math.max(best, 1 + longestSimplePath(target, graph, visiting));
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

    private static boolean meetsRequirements(PilotMetrics metrics, PilotRequirements requirements) {
        return metrics.tables() >= requirements.targetTables()
                && metrics.physicalForeignKeys() >= requirements.minPhysicalForeignKeys()
                && metrics.chainDepth() >= requirements.minChainDepth();
    }

    private static DatabaseSchema buildSchema(List<TableOccurrence> selected) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("MERMAID_DIAGRAM_PILOT");
        selected.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey))
                .map(TableOccurrence::table)
                .forEach(builder::addTable);
        return builder.build();
    }

    private static Map<String, List<TableOccurrence>> groupByTable(List<TableOccurrence> occurrences) {
        Map<String, List<TableOccurrence>> grouped = new LinkedHashMap<>();
        occurrences.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey).thenComparing(TableOccurrence::snapshot))
                .forEach(occurrence -> grouped.computeIfAbsent(occurrence.tableKey(), ignored -> new ArrayList<>())
                        .add(occurrence));
        grouped.replaceAll((key, value) -> List.copyOf(value));
        return grouped;
    }

    private static String resolvedTargetKey(Table owner, ForeignKey foreignKey) {
        QualifiedName target = foreignKey.referencedTable();
        if (target.schema() != null) return tableKey(target);
        String schema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return tableKey(QualifiedName.of(schema, target.name().value()));
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

    private static void assertMermaidFile(Path file, String header) throws Exception {
        assertTrue(Files.isRegularFile(file), "Mermaid file not created: " + file);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith(header), "Unexpected Mermaid syntax header: " + file);
    }

    private static String selectedCsv(PilotSelection selection) {
        List<String> lines = new ArrayList<>();
        lines.add("table,snapshot,source,physical_foreign_keys,columns");
        for (TableOccurrence occurrence : selection.selected()) {
            lines.add(csvLine(occurrence.table().qualifiedName().toString(), occurrence.snapshot(),
                    occurrence.source(), Integer.toString(physicalForeignKeyCount(occurrence.table())),
                    Integer.toString(occurrence.table().columns().size())));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String manifestCsv(Path outputRoot, List<DiagramArtifact> artifacts) {
        List<String> lines = new ArrayList<>();
        lines.add("diagram_type,scope,file,tables,relations,bytes");
        for (DiagramArtifact artifact : artifacts) {
            lines.add(csvLine(artifact.type(), artifact.scope(), normalize(outputRoot.relativize(artifact.file())),
                    artifact.tableCount() < 0 ? "scope-derived" : Integer.toString(artifact.tableCount()),
                    artifact.relationCount() < 0 ? "scope-derived" : Integer.toString(artifact.relationCount()),
                    Long.toString(artifact.bytes())));
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
            int dependencyDepth,
            boolean includeColumns,
            boolean includeDataTypes,
            List<DiagramArtifact> artifacts,
            PilotRequirements requirements) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge canonical JSON Mermaid pilot").append(System.lineSeparator());
        text.append("=========================================").append(System.lineSeparator());
        text.append("Input directory          : ").append(inputRoot).append(System.lineSeparator());
        text.append("Output directory         : ").append(outputRoot).append(System.lineSeparator());
        text.append("Snapshots discovered     : ").append(snapshots).append(System.lineSeparator());
        text.append("Distinct table names     : ").append(distinctTables).append(System.lineSeparator());
        text.append("Duplicate occurrences    : ").append(duplicateOccurrences).append(System.lineSeparator());
        text.append("Historical test select   : ").append(historicalSelection).append(System.lineSeparator());
        text.append("Selection mode           : ").append(selection.mode()).append(System.lineSeparator());
        text.append("Seed table               : ").append(selection.seedTable()).append(System.lineSeparator());
        text.append("Pilot tables             : ").append(selection.selected().size()).append(System.lineSeparator());
        text.append("Target tables            : ").append(requirements.targetTables()).append(System.lineSeparator());
        text.append("Physical FKs             : ").append(analysis.physicalForeignKeys()).append(System.lineSeparator());
        text.append("Resolved physical FKs    : ").append(analysis.resolvedPhysicalForeignKeys()).append(System.lineSeparator());
        text.append("FK chain depth           : ").append(selection.metrics().chainDepth()).append(System.lineSeparator());
        text.append("Connected components     : ").append(selection.metrics().connectedComponents()).append(System.lineSeparator());
        text.append("Self references          : ").append(selection.metrics().selfReferences()).append(System.lineSeparator());
        text.append("Dependency cycles        : ").append(selection.metrics().cycleGroups()).append(System.lineSeparator());
        text.append("FK blockers              : ").append(analysis.errorCount()).append(System.lineSeparator());
        text.append("Diagram dependency depth : ").append(dependencyDepth).append(System.lineSeparator());
        text.append("Include columns          : ").append(includeColumns).append(System.lineSeparator());
        text.append("Include data types       : ").append(includeDataTypes).append(System.lineSeparator());
        text.append("Mermaid artifacts        : ").append(artifacts.size()).append(System.lineSeparator());
        for (DiagramArtifact artifact : artifacts) {
            text.append("  ").append(artifact.type()).append("/").append(artifact.scope()).append(" : ")
                    .append(normalize(outputRoot.relativize(artifact.file())))
                    .append(" (").append(artifact.bytes()).append(" bytes)")
                    .append(System.lineSeparator());
        }
        text.append("Database execution       : NOT APPLICABLE").append(System.lineSeparator());
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

    private static int integerProperty(String name, int defaultValue, int min, int max) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(name, Boolean.toString(defaultValue)));
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
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-mermaid-pilot").toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String tableKey(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    private static String fileToken(String value) {
        String token = value.replaceAll("[^A-Za-z0-9._-]+", "_").replace('.', '_');
        return token.isBlank() ? "schemaforge" : token;
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

    private record DiagramArtifact(
            String type,
            String scope,
            Path file,
            int tableCount,
            int relationCount,
            long bytes) {
    }
}
