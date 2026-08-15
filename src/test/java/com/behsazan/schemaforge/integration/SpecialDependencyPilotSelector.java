package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalyzer;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlan;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only selector for real self-reference and coexisting-cycle integrated deployment pilots. */
final class SpecialDependencyPilotSelector {
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    SelfReferenceSelection selectSelfReference(
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            List<DatabasePlatform> platforms) {
        SelfReferenceAssessment assessment = assessSelfReference(byTable, maxTables, platforms);
        if (assessment.selection() != null) return assessment.selection();
        throw new IllegalStateException(assessment.reason());
    }

    SelfReferenceAssessment assessSelfReference(
            Map<String, List<TableOccurrence>> byTable,
            int maxTables,
            List<DatabasePlatform> platforms) {
        List<TableOccurrence> candidates = byTable.values().stream()
                .flatMap(List::stream)
                .filter(occurrence -> hasSelfReference(occurrence.table()))
                .sorted(Comparator
                        .comparingInt((TableOccurrence occurrence) -> byTable.get(occurrence.tableKey()).size())
                        .thenComparingInt(occurrence -> physicalForeignKeyCount(occurrence.table()))
                        .thenComparing(TableOccurrence::tableKey)
                        .thenComparing(TableOccurrence::snapshot))
                .toList();

        if (candidates.isEmpty()) {
            return new SelfReferenceAssessment(
                    SelfReferenceStatus.NOT_APPLICABLE, null, List.of(),
                    "No physical self-reference definition exists in the selected input");
        }

        List<String> rejected = new ArrayList<>();
        for (TableOccurrence candidate : candidates) {
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>();
            selected.put(candidate.tableKey(), candidate);
            if (!completeClosure(selected, byTable, maxTables)) {
                rejected.add(candidate.tableKey() + "@" + candidate.snapshot() + ": unresolved FK closure");
                continue;
            }
            DatabaseSchema schema = buildSchema(selected.values().stream().toList(), "SELF_REFERENCE_PILOT");
            ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
            if (!analysis.deployable() || analysis.selfReferences() < 1) {
                rejected.add(candidate.tableKey() + "@" + candidate.snapshot() + ": " + blockerSummary(analysis));
                continue;
            }
            String renderBlocker = crossDialectBlocker(schema, platforms);
            if (renderBlocker != null) {
                rejected.add(candidate.tableKey() + "@" + candidate.snapshot() + ": " + renderBlocker);
                continue;
            }
            SelfReferenceSelection selection = new SelfReferenceSelection(
                    candidate.table().qualifiedName().toString(),
                    selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                    analysis, List.copyOf(rejected), SelfReferenceMode.FULL_CLOSURE, 0);
            return new SelfReferenceAssessment(
                    SelfReferenceStatus.DEPLOYABLE, selection, List.copyOf(rejected),
                    "A complete cross-dialect self-reference closure was selected");
        }

        // Historical corpora can contain a valid self-reference together with unrelated FKs whose
        // targets are absent or belong to another document version. For self-reference coverage only,
        // isolate the real self-referencing FK(s) while preserving the table's columns, PK/UK, checks,
        // indexes, comments, and physical options. This fallback is test-only and never changes the
        // production one-version-per-table integrated path.
        for (TableOccurrence candidate : candidates) {
            Table isolatedTable = isolateSelfReferences(candidate.table());
            int omitted = physicalForeignKeyCount(candidate.table()) - physicalForeignKeyCount(isolatedTable);
            TableOccurrence isolated = new TableOccurrence(
                    candidate.tableKey(), isolatedTable, candidate.sequences(), candidate.snapshot(), candidate.source());
            DatabaseSchema schema = buildSchema(List.of(isolated), "ISOLATED_SELF_REFERENCE_PILOT");
            ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
            if (!analysis.deployable() || analysis.selfReferences() < 1) {
                rejected.add(candidate.tableKey() + "@" + candidate.snapshot()
                        + ": isolated self-reference blocked: " + blockerSummary(analysis));
                continue;
            }
            String renderBlocker = crossDialectBlocker(schema, platforms);
            if (renderBlocker != null) {
                rejected.add(candidate.tableKey() + "@" + candidate.snapshot()
                        + ": isolated self-reference " + renderBlocker);
                continue;
            }
            SelfReferenceSelection selection = new SelfReferenceSelection(
                    candidate.table().qualifiedName().toString(), List.of(isolated), analysis,
                    List.copyOf(rejected), SelfReferenceMode.ISOLATED_SELF_REFERENCE, omitted);
            return new SelfReferenceAssessment(
                    SelfReferenceStatus.DEPLOYABLE, selection, List.copyOf(rejected),
                    "Full FK closure was unavailable; a test-only isolated real self-reference was selected");
        }

        String reason = "Self-reference definitions are present but none is cross-dialect deployable. Candidates="
                + candidates.size() + "; examples=" + String.join(" | ", rejected.stream().limit(5).toList());
        return new SelfReferenceAssessment(
                SelfReferenceStatus.PRESENT_BUT_NOT_DEPLOYABLE, null, List.copyOf(rejected), reason);
    }

    List<CycleAssessment> assessCycles(
            Map<String, List<TableOccurrence>> byTable,
            List<HistoricalDependencyCoverage.CycleGroup> aggregateCycles,
            int maxTables,
            int maxCombinations,
            List<DatabasePlatform> platforms) {
        List<CycleAssessment> assessments = new ArrayList<>();
        int ordinal = 0;
        for (HistoricalDependencyCoverage.CycleGroup aggregateCycle : aggregateCycles) {
            ordinal++;
            List<String> members = aggregateCycle.members().stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .sorted()
                    .toList();
            Set<String> memberSet = new LinkedHashSet<>(members);
            Map<String, List<TableOccurrence>> cycleCandidates = new LinkedHashMap<>();
            boolean missingCandidate = false;
            for (String member : members) {
                List<TableOccurrence> candidates = byTable.getOrDefault(member, List.of()).stream()
                        .filter(occurrence -> hasInternalCycleEdge(occurrence.table(), memberSet))
                        .sorted(Comparator
                                .comparingInt((TableOccurrence occurrence) -> physicalForeignKeyCount(occurrence.table()))
                                .thenComparing(TableOccurrence::snapshot))
                        .toList();
                cycleCandidates.put(member, candidates);
                if (candidates.isEmpty()) missingCandidate = true;
            }
            if (missingCandidate) {
                assessments.add(new CycleAssessment(
                        ordinal, members, CycleStatus.HISTORICAL_AGGREGATE_ONLY, 0, List.of(), null,
                        "At least one cycle member has no single table definition contributing an internal cycle edge"));
                continue;
            }

            AtomicInteger combinations = new AtomicInteger();
            CycleSearchEvidence evidence = new CycleSearchEvidence();
            CycleSelection selection = searchCycleCombination(
                    members, 0, cycleCandidates, new LinkedHashMap<>(), byTable, memberSet,
                    maxTables, maxCombinations, combinations, platforms, evidence);
            if (selection != null) {
                assessments.add(new CycleAssessment(
                        ordinal, members, CycleStatus.DEPLOYABLE_CYCLE, combinations.get(),
                        selection.selected(), selection.analysis(), "A coexisting cross-dialect cycle was found"));
            } else {
                CycleStatus status;
                String reason;
                if (combinations.get() >= maxCombinations) {
                    status = CycleStatus.INCONCLUSIVE_COMBINATION_LIMIT;
                    reason = "No deployable cycle found before the deterministic combination limit " + maxCombinations;
                } else if (evidence.crossDialectBlocked > 0) {
                    status = CycleStatus.COEXISTING_PORTABILITY_BLOCKED;
                    reason = "A one-version-per-table canonical cycle exists, but requested DBMS portability blocks rendering"
                            + (evidence.exampleBlocker == null ? "" : ": " + evidence.exampleBlocker);
                } else if (evidence.coexistingCycles > 0) {
                    status = CycleStatus.COEXISTING_CANONICAL_BLOCKED;
                    reason = "A one-version-per-table cycle exists, but canonical FK validation blocks integrated deployment";
                } else {
                    status = CycleStatus.HISTORICAL_AGGREGATE_ONLY;
                    reason = "No one-version-per-table compatible combination preserves the aggregate cycle";
                }
                assessments.add(new CycleAssessment(
                        ordinal, members, status, combinations.get(), List.of(), null, reason));
            }
        }
        return List.copyOf(assessments);
    }

    private CycleSelection searchCycleCombination(
            List<String> members,
            int index,
            Map<String, List<TableOccurrence>> cycleCandidates,
            LinkedHashMap<String, TableOccurrence> fixed,
            Map<String, List<TableOccurrence>> byTable,
            Set<String> memberSet,
            int maxTables,
            int maxCombinations,
            AtomicInteger combinations,
            List<DatabasePlatform> platforms,
            CycleSearchEvidence evidence) {
        if (combinations.get() >= maxCombinations) return null;
        if (index >= members.size()) {
            combinations.incrementAndGet();
            LinkedHashMap<String, TableOccurrence> selected = new LinkedHashMap<>(fixed);
            if (!completeClosure(selected, byTable, maxTables)) return null;
            DatabaseSchema schema;
            try {
                schema = buildSchema(selected.values().stream().toList(), "CYCLE_PILOT");
            } catch (RuntimeException exception) {
                return null;
            }
            if (!isStronglyConnectedWithinMembers(schema, memberSet)) return null;
            evidence.coexistingCycles++;
            ForeignKeyAnalysisResult analysis = analyzer.analyze(schema);
            if (!analysis.deployable() || analysis.cycleGroups() < 1) return null;
            evidence.canonicalDeployableCycles++;
            String renderBlocker = crossDialectBlocker(schema, platforms);
            if (renderBlocker != null) {
                evidence.crossDialectBlocked++;
                if (evidence.exampleBlocker == null) evidence.exampleBlocker = renderBlocker;
                return null;
            }
            return new CycleSelection(
                    selected.values().stream().sorted(Comparator.comparing(TableOccurrence::tableKey)).toList(),
                    analysis);
        }

        String member = members.get(index);
        for (TableOccurrence candidate : cycleCandidates.getOrDefault(member, List.of())) {
            fixed.put(member, candidate);
            CycleSelection selection = searchCycleCombination(
                    members, index + 1, cycleCandidates, fixed, byTable, memberSet,
                    maxTables, maxCombinations, combinations, platforms, evidence);
            if (selection != null) return selection;
            fixed.remove(member);
            if (combinations.get() >= maxCombinations) break;
        }
        return null;
    }

    private boolean completeClosure(
            LinkedHashMap<String, TableOccurrence> selected,
            Map<String, List<TableOccurrence>> byTable,
            int maxTables) {
        if (selected.size() > maxTables) return false;

        for (TableOccurrence owner : selected.values().stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey)).toList()) {
            for (ForeignKey foreignKey : owner.table().foreignKeys()) {
                if (!foreignKey.physicalReference()) continue;
                String targetKey = resolvedTargetKey(owner.table(), foreignKey);
                TableOccurrence existing = selected.get(targetKey);
                if (existing != null) {
                    if (!compatibleTarget(existing.table(), foreignKey)) return false;
                    continue;
                }
                if (selected.size() >= maxTables) return false;
                List<TableOccurrence> candidates = byTable.getOrDefault(targetKey, List.of()).stream()
                        .filter(candidate -> compatibleTarget(candidate.table(), foreignKey))
                        .sorted(Comparator
                                .comparingInt((TableOccurrence candidate) -> physicalForeignKeyCount(candidate.table()))
                                .thenComparing(TableOccurrence::snapshot))
                        .toList();
                for (TableOccurrence candidate : candidates) {
                    LinkedHashMap<String, TableOccurrence> trial = new LinkedHashMap<>(selected);
                    trial.put(targetKey, candidate);
                    if (completeClosure(trial, byTable, maxTables)) {
                        selected.clear();
                        selected.putAll(trial);
                        return completeClosure(selected, byTable, maxTables);
                    }
                }
                return false;
            }
        }
        return true;
    }

    private String crossDialectBlocker(DatabaseSchema schema, List<DatabasePlatform> platforms) {
        IntegratedSchemaDeploymentPlan plan;
        try {
            plan = planner.plan(schema);
        } catch (RuntimeException exception) {
            return "planner: " + safeMessage(exception);
        }
        for (DatabasePlatform platform : platforms) {
            try {
                new IntegratedSqlRenderer(DialectFactory.create(platform)).render(schema, plan);
            } catch (RuntimeException exception) {
                return platform.commandLineName() + ": " + safeMessage(exception);
            }
        }
        return null;
    }

    private static boolean isStronglyConnectedWithinMembers(DatabaseSchema schema, Set<String> members) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        members.forEach(member -> graph.put(member, new LinkedHashSet<>()));
        for (Table table : schema.tables()) {
            String owner = tableKey(table.qualifiedName());
            if (!members.contains(owner)) continue;
            for (ForeignKey foreignKey : table.foreignKeys()) {
                if (!foreignKey.physicalReference()) continue;
                String target = resolvedTargetKey(table, foreignKey);
                if (members.contains(target)) graph.get(owner).add(target);
            }
        }
        for (String source : members) {
            Set<String> reached = new LinkedHashSet<>();
            visit(source, graph, reached);
            if (!reached.containsAll(members)) return false;
        }
        return true;
    }

    private static void visit(String node, Map<String, Set<String>> graph, Set<String> reached) {
        if (!reached.add(node)) return;
        for (String target : graph.getOrDefault(node, Set.of())) visit(target, graph, reached);
    }

    static Map<String, List<TableOccurrence>> groupByTable(List<TableOccurrence> occurrences) {
        Map<String, List<TableOccurrence>> grouped = new LinkedHashMap<>();
        occurrences.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey).thenComparing(TableOccurrence::snapshot))
                .forEach(occurrence -> grouped.computeIfAbsent(occurrence.tableKey(), ignored -> new ArrayList<>())
                        .add(occurrence));
        return grouped;
    }

    static DatabaseSchema buildSchema(List<TableOccurrence> selected, String schemaName) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder(schemaName);
        selected.stream()
                .sorted(Comparator.comparing(TableOccurrence::tableKey))
                .map(TableOccurrence::table)
                .forEach(builder::addTable);

        Map<String, Sequence> sequences = new LinkedHashMap<>();
        for (TableOccurrence occurrence : selected) {
            for (Sequence sequence : occurrence.sequences()) {
                String sequenceKey = tableKey(sequence.qualifiedName());
                Sequence previous = sequences.putIfAbsent(sequenceKey, sequence);
                if (previous != null && previous != sequence) {
                    throw new IllegalStateException("INPUT_DUPLICATE_SEQUENCE in selected pilot: "
                            + sequence.qualifiedName());
                }
            }
        }
        sequences.values().stream()
                .sorted(Comparator.comparing(sequence -> tableKey(sequence.qualifiedName())))
                .forEach(builder::addSequence);
        return builder.build();
    }

    static Table isolateSelfReferences(Table source) {
        String schema = source.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        Table.Builder builder = Table.builder(schema, source.qualifiedName().name().value())
                .persianName(source.persianName().value())
                .description(source.description().value());
        source.columns().forEach(builder::addColumn);
        source.primaryKey().ifPresent(builder::primaryKey);
        String owner = tableKey(source.qualifiedName());
        source.foreignKeys().stream()
                .filter(ForeignKey::physicalReference)
                .filter(foreignKey -> resolvedTargetKey(source, foreignKey).equals(owner))
                .forEach(builder::addForeignKey);
        source.uniqueKeys().forEach(builder::addUniqueKey);
        source.checkConstraints().forEach(builder::addCheck);
        source.indexes().forEach(builder::addIndex);
        source.physicalOptions().forEach(builder::physicalOption);
        return builder.build();
    }

    static boolean hasSelfReference(Table table) {
        String owner = tableKey(table.qualifiedName());
        return table.foreignKeys().stream()
                .filter(ForeignKey::physicalReference)
                .anyMatch(foreignKey -> resolvedTargetKey(table, foreignKey).equals(owner));
    }

    private static boolean hasInternalCycleEdge(Table table, Set<String> cycleMembers) {
        return table.foreignKeys().stream()
                .filter(ForeignKey::physicalReference)
                .map(foreignKey -> resolvedTargetKey(table, foreignKey))
                .anyMatch(cycleMembers::contains);
    }

    static boolean compatibleTarget(Table target, ForeignKey foreignKey) {
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

    static String resolvedTargetKey(Table owner, ForeignKey foreignKey) {
        QualifiedName referenced = foreignKey.referencedTable();
        if (referenced.schemaName().isPresent()) return tableKey(referenced);
        String ownerSchema = owner.qualifiedName().schemaName().map(Identifier::value).orElse(null);
        return tableKey(QualifiedName.of(ownerSchema, referenced.name().value()));
    }

    static String tableKey(QualifiedName name) {
        return name.toString().toUpperCase(Locale.ROOT);
    }

    static int physicalForeignKeyCount(Table table) {
        return (int) table.foreignKeys().stream().filter(ForeignKey::physicalReference).count();
    }

    private static String blockerSummary(ForeignKeyAnalysisResult analysis) {
        return analysis.issues().stream()
                .filter(issue -> "ERROR".equals(issue.severity().name()))
                .limit(3)
                .map(issue -> issue.code() + ": " + issue.message())
                .reduce((left, right) -> left + " | " + right)
                .orElse("not a deployable self-reference/cycle selection");
    }

    record TableOccurrence(
            String tableKey,
            Table table,
            List<Sequence> sequences,
            String snapshot,
            String source) {
        TableOccurrence {
            Objects.requireNonNull(tableKey, "tableKey");
            Objects.requireNonNull(table, "table");
            sequences = List.copyOf(Objects.requireNonNull(sequences, "sequences"));
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(source, "source");
        }
    }

    enum SelfReferenceStatus {
        DEPLOYABLE,
        PRESENT_BUT_NOT_DEPLOYABLE,
        NOT_APPLICABLE
    }

    enum SelfReferenceMode {
        FULL_CLOSURE,
        ISOLATED_SELF_REFERENCE
    }

    record SelfReferenceAssessment(
            SelfReferenceStatus status,
            SelfReferenceSelection selection,
            List<String> rejectedCandidates,
            String reason) {
        SelfReferenceAssessment {
            Objects.requireNonNull(status, "status");
            rejectedCandidates = List.copyOf(rejectedCandidates);
            Objects.requireNonNull(reason, "reason");
        }

        boolean deployable() {
            return status == SelfReferenceStatus.DEPLOYABLE && selection != null;
        }
    }

    record SelfReferenceSelection(
            String seedTable,
            List<TableOccurrence> selected,
            ForeignKeyAnalysisResult analysis,
            List<String> rejectedBeforeSelection,
            SelfReferenceMode mode,
            int omittedExternalPhysicalForeignKeys) {
        SelfReferenceSelection {
            selected = List.copyOf(selected);
            rejectedBeforeSelection = List.copyOf(rejectedBeforeSelection);
            Objects.requireNonNull(mode, "mode");
            if (omittedExternalPhysicalForeignKeys < 0) {
                throw new IllegalArgumentException("omittedExternalPhysicalForeignKeys must be >= 0");
            }
        }
    }

    enum CycleStatus {
        DEPLOYABLE_CYCLE,
        HISTORICAL_AGGREGATE_ONLY,
        COEXISTING_CANONICAL_BLOCKED,
        COEXISTING_PORTABILITY_BLOCKED,
        INCONCLUSIVE_COMBINATION_LIMIT
    }

    record CycleAssessment(
            int ordinal,
            List<String> members,
            CycleStatus status,
            int combinationsEvaluated,
            List<TableOccurrence> selected,
            ForeignKeyAnalysisResult analysis,
            String reason) {
        CycleAssessment {
            members = List.copyOf(members);
            selected = List.copyOf(selected);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }

        boolean deployable() {
            return status == CycleStatus.DEPLOYABLE_CYCLE;
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static final class CycleSearchEvidence {
        private int coexistingCycles;
        private int canonicalDeployableCycles;
        private int crossDialectBlocked;
        private String exampleBlocker;
    }

    private record CycleSelection(List<TableOccurrence> selected, ForeignKeyAnalysisResult analysis) {
        private CycleSelection {
            selected = List.copyOf(selected);
        }
    }
}
