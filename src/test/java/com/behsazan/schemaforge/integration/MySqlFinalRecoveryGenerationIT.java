package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL P2-FINAL evidence-backed cumulative generation runner.
 *
 * <p>This runner materializes the cumulative safe MySQL corpus without mutating persisted
 * canonical JSON. It reapplies the exact DB2 overlay and unanimous historical numeric
 * precision evidence, then consumes only the already-confirmed P2-R7, P2-R8 and P2-R10
 * recovery decisions. Anything that still blocks is classified as an explicit hard blocker
 * instead of being guessed or clamped.</p>
 */
class MySqlFinalRecoveryGenerationIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.final.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.final.db2SysColumnsFile";
    private static final String P2R2_DIR = "schemaforge.mysql.final.p2r2Dir";
    private static final String P2R7_DIR = "schemaforge.mysql.final.p2r7Dir";
    private static final String P2R8_DIR = "schemaforge.mysql.final.p2r8Dir";
    private static final String P2R10_DIR = "schemaforge.mysql.final.p2r10Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.final.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.mysql.final.cleanOutput";
    private static final String MIN_EVIDENCE = "schemaforge.mysql.final.minEvidence";
    private static final String FAIL_ON_GENERATION_ERRORS = "schemaforge.mysql.final.failOnGenerationErrors";

    private static final Set<String> CANONICAL_EXACT_NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
    private static final Set<String> METADATA_EXACT_NUMERIC = Set.of(
            "DECIMAL", "NUMERIC", "SMALLINT", "INTEGER", "BIGINT", "TINYINT");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final DatatypeCompatibilityAnalyzer compatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final MySqlTypeMapper mySqlTypeMapper = new MySqlTypeMapper();
    private final Dialect mysqlDialect = DialectFactory.create(DatabasePlatform.MYSQL);

    @Test
    void generatesFinalEvidenceBackedCorpusAndFreezesResidualHardBlockers() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path p2r2Root = requiredDirectory(P2R2_DIR);
        Path p2r7Root = requiredDirectory(P2R7_DIR);
        Path p2r8Root = requiredDirectory(P2R8_DIR);
        Path p2r10Root = requiredDirectory(P2R10_DIR);
        Path outputRoot = outputDirectory(snapshotRoot);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        boolean failOnGenerationErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_GENERATION_ERRORS, "false"));
        int minEvidence = positiveInt(System.getProperty(MIN_EVIDENCE, "1"), MIN_EVIDENCE);

        Path p2r2Details = latestFile(p2r2Root, "mysql-metadata-recovery-details_", ".csv");
        Path p2r7Applied = latestFile(p2r7Root, "mysql-strong-table-reconciliation-applied_", ".csv");
        Path p2r8Applied = latestFile(p2r8Root, "mysql-cross-schema-reconciliation-applied_", ".csv");
        Path p2r10Details = latestFile(p2r10Root, "mysql-historical-column-corroboration-details_", ".csv");

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);
        List<LoadedSnapshot> loaded = loadSnapshots(snapshotRoot);
        Map<String, HistoricalEvidence> historicalIndex = buildHistoricalEvidenceIndex(loaded);
        Map<String, String> sourceEvidence = readP2R2Evidence(p2r2Details);
        List<RecoveryDirective> directives = new ArrayList<>();
        directives.addAll(readP2R7Directives(p2r7Applied));
        directives.addAll(readP2R8Directives(p2r8Applied));
        directives.addAll(readP2R10Directives(p2r10Details));
        Map<String, Map<String, RecoveryDirective>> directivesBySnapshot = groupDirectives(directives);

        Path generatedRoot = outputRoot.resolve("generated");
        Files.createDirectories(outputRoot);
        if (cleanOutput) cleanDirectory(generatedRoot);
        Files.createDirectories(generatedRoot);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        List<String> details = new ArrayList<>();
        details.add("snapshot,source,status,baseline_blockers,db2_recovery,historical_recovery,confirmed_recovery,remaining_blockers,output_file,error");
        List<String> applied = new ArrayList<>();
        applied.add("snapshot,source,stage,schema,table,column,evidence_schema,evidence_table,evidence_column,canonical_type,recovered_type");
        List<String> remaining = new ArrayList<>();
        remaining.add("snapshot,source,severity,issue_code,path,message,hard_classification,p2r2_evidence_classification");

        int baselineCompatible = 0;
        int baselineBlocked = 0;
        int db2RecoveryOccurrences = 0;
        int historicalRecoveryOccurrences = 0;
        int confirmedRecoveryOccurrences = 0;
        Map<String, Integer> confirmedByStage = new LinkedHashMap<>();
        Set<String> confirmedRecoverySnapshots = new LinkedHashSet<>();
        Set<String> newlyUnblockedByConfirmed = new LinkedHashSet<>();
        int generated = 0;
        int hardBlockedSnapshots = 0;
        int generationFailures = 0;
        int remainingOccurrences = 0;
        Map<String, Integer> remainingByCode = new LinkedHashMap<>();
        Map<String, Integer> hardByClassification = new LinkedHashMap<>();
        Map<String, Set<String>> hardSnapshotsByClassification = new LinkedHashMap<>();
        Map<String, Integer> generationErrors = new LinkedHashMap<>();

        for (LoadedSnapshot item : loaded) {
            String source = item.snapshot().source() == null ? "" : safe(item.snapshot().source().relativePath());
            DatabaseSchema original = item.schema();
            var baselineAssessment = compatibilityAnalyzer.analyze(original, mysqlDialect);
            int baselineBlockers = blockingCount(baselineAssessment.issues());
            if (baselineAssessment.blocking()) baselineBlocked++; else baselineCompatible++;

            Overlay db2 = applyDb2Overlay(original, catalog);
            db2RecoveryOccurrences += db2.actions().size();
            HistoricalOverlay historical = applyHistoricalConsensusOverlay(db2.schema(), historicalIndex, minEvidence);
            historicalRecoveryOccurrences += historical.actions().size();
            var beforeConfirmedAssessment = compatibilityAnalyzer.analyze(historical.schema(), mysqlDialect);

            Map<String, RecoveryDirective> itemDirectives = directivesBySnapshot.getOrDefault(item.relative(), Map.of());
            ConfirmedOverlay confirmed = applyConfirmedDirectives(historical.schema(), item.relative(), itemDirectives, catalog);
            confirmedRecoveryOccurrences += confirmed.actions().size();
            if (!confirmed.actions().isEmpty()) confirmedRecoverySnapshots.add(item.relative());
            for (ConfirmedAction action : confirmed.actions()) {
                confirmedByStage.merge(action.directive().stage(), 1, Integer::sum);
                applied.add(csvLine(item.relative(), source, action.directive().stage(), action.schema(), action.table(),
                        action.column(), action.directive().evidenceSchema(), action.directive().evidenceTable(),
                        action.directive().evidenceColumn(), renderType(action.canonicalType()), renderType(action.recoveredType())));
            }

            var finalAssessment = compatibilityAnalyzer.analyze(confirmed.schema(), mysqlDialect);
            int finalBlockers = blockingCount(finalAssessment.issues());
            if (beforeConfirmedAssessment.blocking() && !finalAssessment.blocking()) {
                newlyUnblockedByConfirmed.add(item.relative());
            }

            if (finalAssessment.blocking()) {
                hardBlockedSnapshots++;
                for (var issue : finalAssessment.issues()) {
                    if (!"ERROR".equalsIgnoreCase(issue.severity())) continue;
                    remainingOccurrences++;
                    remainingByCode.merge(issue.code(), 1, Integer::sum);
                    IssueLocation location = issueLocation(issue.path());
                    String evidenceClass = sourceEvidence.getOrDefault(
                            evidenceKey(item.relative(), location.table(), location.column()), "");
                    String hardClass = hardClassification(issue.code(), evidenceClass);
                    hardByClassification.merge(hardClass, 1, Integer::sum);
                    hardSnapshotsByClassification.computeIfAbsent(hardClass, ignored -> new LinkedHashSet<>())
                            .add(item.relative());
                    remaining.add(csvLine(item.relative(), source, issue.severity(), issue.code(), issue.path(), issue.message(),
                            hardClass, evidenceClass));
                }
                details.add(csvLine(item.relative(), source, "HARD_BLOCKED", Integer.toString(baselineBlockers),
                        Integer.toString(db2.actions().size()), Integer.toString(historical.actions().size()),
                        Integer.toString(confirmed.actions().size()), Integer.toString(finalBlockers), "", ""));
                continue;
            }

            Path target = generatedTarget(generatedRoot, snapshotRoot.relativize(item.path()));
            try {
                PreparedSchema prepared = preparationService.prepare(confirmed.schema());
                String sql = new DdlGenerator(mysqlDialect).generate(prepared.schema(), prepared.validationReport());
                Files.createDirectories(target.getParent());
                Files.writeString(target, sql, StandardCharsets.UTF_8);
                generated++;
                details.add(csvLine(item.relative(), source,
                        baselineAssessment.blocking() ? "RECOVERED_AND_GENERATED" : "GENERATED_BASELINE_COMPATIBLE",
                        Integer.toString(baselineBlockers), Integer.toString(db2.actions().size()),
                        Integer.toString(historical.actions().size()), Integer.toString(confirmed.actions().size()),
                        "0", normalize(outputRoot.relativize(target)), ""));
            } catch (Exception exception) {
                generationFailures++;
                String error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                generationErrors.merge(error, 1, Integer::sum);
                details.add(csvLine(item.relative(), source, "GENERATION_FAILED", Integer.toString(baselineBlockers),
                        Integer.toString(db2.actions().size()), Integer.toString(historical.actions().size()),
                        Integer.toString(confirmed.actions().size()), "0", normalize(outputRoot.relativize(target)), error));
            }
        }

        Path summaryFile = outputRoot.resolve("mysql-final-recovery-summary_" + timestamp + ".txt");
        Path detailsFile = outputRoot.resolve("mysql-final-recovery-details_" + timestamp + ".csv");
        Path appliedFile = outputRoot.resolve("mysql-final-recovery-applied_" + timestamp + ".csv");
        Path remainingFile = outputRoot.resolve("mysql-final-hard-blockers_" + timestamp + ".csv");
        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(appliedFile, String.join(System.lineSeparator(), applied) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(remainingFile, String.join(System.lineSeparator(), remaining) + System.lineSeparator(), StandardCharsets.UTF_8);

        double coverage = loaded.isEmpty() ? 0.0 : (100.0 * generated / loaded.size());
        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL P2-FINAL evidence-backed recovery freeze");
        summary.add("=========================================================");
        summary.add("Snapshot directory              : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file             : " + metadataFile);
        summary.add("P2-R2 evidence details          : " + p2r2Details);
        summary.add("P2-R7 applied                   : " + p2r7Applied);
        summary.add("P2-R8 applied                   : " + p2r8Applied);
        summary.add("P2-R10 details                  : " + p2r10Details);
        summary.add("Snapshots loaded                : " + loaded.size());
        summary.add("Baseline compatible             : " + baselineCompatible);
        summary.add("Baseline blocked                : " + baselineBlocked);
        summary.add("DB2 recovery occurrences        : " + db2RecoveryOccurrences);
        summary.add("Historical recovery occurrences : " + historicalRecoveryOccurrences);
        summary.add("Confirmed recovery occurrences  : " + confirmedRecoveryOccurrences);
        summary.add("Confirmed recovery snapshots    : " + confirmedRecoverySnapshots.size());
        summary.add("Newly unblocked by P2-R7/R8/R10 : " + newlyUnblockedByConfirmed.size());
        summary.add("Final generated                 : " + generated);
        summary.add("Final hard-blocked snapshots    : " + hardBlockedSnapshots);
        summary.add("Generation failures             : " + generationFailures);
        summary.add(String.format(Locale.ROOT, "Final generation coverage        : %.2f%%", coverage));
        summary.add("Remaining blocker occurrences   : " + remainingOccurrences);
        summary.add("");
        summary.add("Confirmed recovery occurrences by stage");
        summary.add("---------------------------------------");
        if (confirmedByStage.isEmpty()) summary.add("None");
        else confirmedByStage.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Remaining blockers by MySQL code");
        summary.add("--------------------------------");
        if (remainingByCode.isEmpty()) summary.add("None");
        else remainingByCode.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Hard blocker classifications (occurrences / snapshots)");
        summary.add("------------------------------------------------------");
        if (hardByClassification.isEmpty()) summary.add("None");
        else hardByClassification.forEach((key, value) -> summary.add(
                key + " : " + value + " / " + hardSnapshotsByClassification.getOrDefault(key, Set.of()).size()));
        summary.add("");
        summary.add("Generation errors");
        summary.add("-----------------");
        if (generationErrors.isEmpty()) summary.add("None");
        else generationErrors.forEach((key, value) -> summary.add(value + " : " + key));
        summary.add("");
        summary.add("Details      : " + detailsFile);
        summary.add("Applied      : " + appliedFile);
        summary.add("Hard blockers: " + remainingFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("Snapshots loaded                : " + loaded.size());
        System.out.println("Baseline compatible             : " + baselineCompatible);
        System.out.println("Baseline blocked                : " + baselineBlocked);
        System.out.println("Confirmed recovery occurrences  : " + confirmedRecoveryOccurrences);
        System.out.println("Confirmed recovery snapshots    : " + confirmedRecoverySnapshots.size());
        System.out.println("Newly unblocked by P2-R7/R8/R10 : " + newlyUnblockedByConfirmed.size());
        System.out.println("Final generated                 : " + generated);
        System.out.println("Final hard-blocked snapshots    : " + hardBlockedSnapshots);
        System.out.println("Generation failures             : " + generationFailures);
        System.out.printf(Locale.ROOT, "Final generation coverage        : %.2f%%%n", coverage);
        confirmedByStage.forEach((key, value) -> System.out.println(key + " : " + value));
        hardByClassification.forEach((key, value) -> System.out.println(
                key + " : " + value + " / " + hardSnapshotsByClassification.getOrDefault(key, Set.of()).size()));
        System.out.println("Summary                         : " + summaryFile);

        assertEquals(loaded.size(), generated + hardBlockedSnapshots + generationFailures,
                "Every loaded snapshot must end in generated, hard-blocked, or generation-failed state");
        if (failOnGenerationErrors) assertEquals(0, generationFailures, "Final MySQL generation failures were found");
        assertTrue(generated >= 4702, "Final cumulative generation unexpectedly regressed below P2-R8 baseline");
    }

    private ConfirmedOverlay applyConfirmedDirectives(DatabaseSchema schema, String snapshot,
                                                       Map<String, RecoveryDirective> directives,
                                                       Db2SysColumnsFileCatalog catalog) {
        if (directives.isEmpty()) return new ConfirmedOverlay(schema, List.of());
        DatabaseSchema.Builder schemaBuilder = copySchemaHeader(schema);
        List<ConfirmedAction> actions = new ArrayList<>();
        for (Table table : schema.tables()) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            String tableName = table.qualifiedName().name().value();
            Table.Builder tableBuilder = copyTableHeader(table, schemaName, tableName);
            for (Column column : table.columns()) {
                DataType canonicalType = column.dataType();
                String directiveKey = targetColumnKey(schemaName, tableName, column.name().value());
                RecoveryDirective directive = directives.get(directiveKey);
                if (directive == null || !isExactNumeric(canonicalType) || canonicalType.precision() != null) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                DataType recovered = directiveRecoveryType(catalog, directive);
                if (recovered == null) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                tableBuilder.addColumn(copyColumn(column, recovered));
                actions.add(new ConfirmedAction(schemaName, tableName, column.name().value(), canonicalType, recovered, directive));
            }
            copyTableObjects(table, tableBuilder);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new ConfirmedOverlay(schemaBuilder.build(), List.copyOf(actions));
    }

    private DataType directiveRecoveryType(Db2SysColumnsFileCatalog catalog, RecoveryDirective directive) {
        if (catalog.lookupStatus(directive.evidenceSchema(), directive.evidenceTable(), directive.evidenceColumn())
                != Db2SysColumnsFileCatalog.LookupStatus.USABLE) return null;
        DataType metadataType = catalog.findType(directive.evidenceSchema(), directive.evidenceTable(), directive.evidenceColumn())
                .orElse(null);
        if (metadataType == null) return null;
        String metadataName = metadataType.name().normalized().toUpperCase(Locale.ROOT);
        if (!METADATA_EXACT_NUMERIC.contains(metadataName)) return null;
        try {
            mySqlTypeMapper.map(metadataType);
            return metadataType;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private Overlay applyDb2Overlay(DatabaseSchema schema, Db2SysColumnsFileCatalog catalog) {
        DatabaseSchema.Builder schemaBuilder = copySchemaHeader(schema);
        List<RecoveryAction> actions = new ArrayList<>();
        for (Table table : schema.tables()) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            String tableName = table.qualifiedName().name().value();
            Table.Builder tableBuilder = copyTableHeader(table, schemaName, tableName);
            for (Column column : table.columns()) {
                DataType recovered = db2RecoveryType(column.dataType(), catalog, schemaName, tableName, column.name().value());
                if (recovered == null) tableBuilder.addColumn(column);
                else {
                    tableBuilder.addColumn(copyColumn(column, recovered));
                    actions.add(new RecoveryAction(schemaName, tableName, column.name().value()));
                }
            }
            copyTableObjects(table, tableBuilder);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new Overlay(schemaBuilder.build(), List.copyOf(actions));
    }

    private HistoricalOverlay applyHistoricalConsensusOverlay(DatabaseSchema schema,
                                                               Map<String, HistoricalEvidence> evidenceIndex,
                                                               int minEvidence) {
        DatabaseSchema.Builder schemaBuilder = copySchemaHeader(schema);
        List<HistoricalAction> actions = new ArrayList<>();
        for (Table table : schema.tables()) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            String tableName = table.qualifiedName().name().value();
            Table.Builder tableBuilder = copyTableHeader(table, schemaName, tableName);
            for (Column column : table.columns()) {
                DataType canonicalType = column.dataType();
                if (!isExactNumeric(canonicalType) || canonicalType.precision() != null) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                HistoricalEvidence historical = evidenceIndex.get(
                        normalizedColumnKey(schemaName, tableName, column.name().value()));
                if (historical == null || historical.signatures().size() != 1
                        || historical.observationCount() < minEvidence) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                NumericSignature signature = historical.signatures().keySet().iterator().next();
                DataType recovered = DataType.numeric(canonicalType.name().value(), signature.precision(),
                        signature.scale() == 0 ? null : signature.scale());
                try {
                    mySqlTypeMapper.map(recovered);
                } catch (RuntimeException unsupported) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                tableBuilder.addColumn(copyColumn(column, recovered));
                actions.add(new HistoricalAction(schemaName, tableName, column.name().value()));
            }
            copyTableObjects(table, tableBuilder);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new HistoricalOverlay(schemaBuilder.build(), List.copyOf(actions));
    }

    private DataType db2RecoveryType(DataType canonicalType, Db2SysColumnsFileCatalog catalog,
                                     String schema, String table, String column) {
        if (!isExactNumeric(canonicalType) || canonicalType.precision() != null) return null;
        if (catalog.lookupStatus(schema, table, column) != Db2SysColumnsFileCatalog.LookupStatus.USABLE) return null;
        DataType metadataType = catalog.findType(schema, table, column).orElse(null);
        if (metadataType == null) return null;
        String metadataName = metadataType.name().normalized().toUpperCase(Locale.ROOT);
        if (!METADATA_EXACT_NUMERIC.contains(metadataName)) return null;
        try {
            mySqlTypeMapper.map(metadataType);
            return metadataType;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private Map<String, HistoricalEvidence> buildHistoricalEvidenceIndex(List<LoadedSnapshot> loaded) {
        Map<String, EvidenceAccumulator> accumulators = new LinkedHashMap<>();
        for (LoadedSnapshot item : loaded) {
            DatabaseSchema schema = item.schema();
            for (Table table : schema.tables()) {
                String schemaName = table.qualifiedName().schemaName()
                        .map(identifier -> identifier.value()).orElse(schema.name().value());
                String tableName = table.qualifiedName().name().value();
                for (Column column : table.columns()) {
                    DataType type = column.dataType();
                    if (!isExactNumeric(type) || type.precision() == null) continue;
                    NumericSignature signature = new NumericSignature(type.precision(), normalizedScale(type.scale()));
                    String key = normalizedColumnKey(schemaName, tableName, column.name().value());
                    accumulators.computeIfAbsent(key, ignored -> new EvidenceAccumulator())
                            .add(signature, item.relative());
                }
            }
        }
        Map<String, HistoricalEvidence> result = new LinkedHashMap<>();
        accumulators.forEach((key, value) -> result.put(key, value.build()));
        return Map.copyOf(result);
    }

    private List<LoadedSnapshot> loadSnapshots(Path snapshotRoot) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(snapshotRoot)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }
        List<LoadedSnapshot> result = new ArrayList<>();
        for (Path path : paths) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                result.add(new LoadedSnapshot(path, normalize(snapshotRoot.relativize(path)), snapshot, schema));
            } catch (RuntimeException ignored) {
                // Earlier corpus audits own snapshot-read quality. Final recovery must not die on non-canonical artifacts.
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> readP2R2Evidence(Path file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, String> row : readCsv(file)) {
            String snapshot = normalizePath(row.get("snapshot"));
            String table = upper(row.get("table"));
            String column = upper(row.get("column"));
            String classification = safe(row.get("classification"));
            if (!snapshot.isBlank() && !table.isBlank() && !column.isBlank()) {
                result.put(evidenceKey(snapshot, table, column), classification);
            }
        }
        return Map.copyOf(result);
    }

    private static List<RecoveryDirective> readP2R7Directives(Path file) throws Exception {
        List<RecoveryDirective> result = new ArrayList<>();
        for (Map<String, String> row : readCsv(file)) {
            result.add(new RecoveryDirective("P2-R7", normalizePath(row.get("snapshot")), upper(row.get("schema")),
                    upper(row.get("canonical_table")), upper(row.get("column")), upper(row.get("schema")),
                    upper(row.get("candidate_table")), upper(row.get("column"))));
        }
        return List.copyOf(result);
    }

    private static List<RecoveryDirective> readP2R8Directives(Path file) throws Exception {
        List<RecoveryDirective> result = new ArrayList<>();
        for (Map<String, String> row : readCsv(file)) {
            result.add(new RecoveryDirective("P2-R8", normalizePath(row.get("snapshot")), upper(row.get("canonical_schema")),
                    upper(row.get("canonical_table")), upper(row.get("column")), upper(row.get("evidence_schema")),
                    upper(row.get("evidence_table")), upper(row.get("column"))));
        }
        return List.copyOf(result);
    }

    private static List<RecoveryDirective> readP2R10Directives(Path file) throws Exception {
        List<RecoveryDirective> result = new ArrayList<>();
        for (Map<String, String> row : readCsv(file)) {
            if (!"CONFIRMED_HISTORICAL_CANDIDATE_NAME".equals(row.get("decision"))) continue;
            result.add(new RecoveryDirective("P2-R10", normalizePath(row.get("snapshot")), upper(row.get("schema")),
                    upper(row.get("table")), upper(row.get("column")), upper(row.get("schema")),
                    upper(row.get("table")), upper(row.get("candidate_column"))));
        }
        return List.copyOf(result);
    }

    private static Map<String, Map<String, RecoveryDirective>> groupDirectives(List<RecoveryDirective> directives) {
        Map<String, Map<String, RecoveryDirective>> grouped = new LinkedHashMap<>();
        for (RecoveryDirective directive : directives) {
            grouped.computeIfAbsent(directive.snapshot(), ignored -> new LinkedHashMap<>())
                    .put(targetColumnKey(directive.schema(), directive.table(), directive.column()), directive);
        }
        Map<String, Map<String, RecoveryDirective>> result = new LinkedHashMap<>();
        grouped.forEach((key, value) -> result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }

    private static List<Map<String, String>> readCsv(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> headers = parseCsvLine(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = parseCsvLine(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                row.put(headers.get(j).trim().toLowerCase(Locale.ROOT), value(values, j));
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                result.add(cell.toString());
                cell.setLength(0);
            } else cell.append(ch);
        }
        result.add(cell.toString());
        return result;
    }

    private static String hardClassification(String issueCode, String evidenceClass) {
        if ("MYSQL_DECIMAL_PRECISION_UNSUPPORTED".equals(issueCode)) {
            return "HARD_MYSQL_DECIMAL_PRECISION_UNSUPPORTED";
        }
        if ("MYSQL_ROWID_UNSUPPORTED".equals(issueCode)) {
            return "HARD_ROWID_PHYSICAL_ARTIFACT_REVIEW";
        }
        if (!"MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED".equals(issueCode)) {
            return "HARD_OTHER_MYSQL_UNSUPPORTED";
        }
        return switch (safe(evidenceClass)) {
            case "METADATA_TABLE_NOT_FOUND" -> "HARD_NO_EXACT_TABLE_EVIDENCE";
            case "METADATA_COLUMN_NOT_FOUND" -> "HARD_NO_EXACT_COLUMN_EVIDENCE";
            case "CANONICAL_METADATA_TYPE_CONFLICT" -> "HARD_CANONICAL_METADATA_TYPE_CONFLICT";
            case "RECOVERABLE_WITH_CANONICAL_CONFLICT" -> "HARD_CANONICAL_CONFLICT_REQUIRES_SOURCE_REVIEW";
            case "RECOVERABLE_EXACT_NUMERIC_METADATA" -> "HARD_OVERLAY_INCONSISTENCY_REVIEW";
            default -> "HARD_NO_SAFE_RECOVERY_EVIDENCE";
        };
    }

    private static IssueLocation issueLocation(String path) {
        if (path == null) return new IssueLocation("", "");
        String[] parts = path.split("\\.");
        if (parts.length >= 4 && "tables".equalsIgnoreCase(parts[0]) && "columns".equalsIgnoreCase(parts[2])) {
            return new IssueLocation(upper(parts[1]), upper(parts[3]));
        }
        return new IssueLocation("", "");
    }

    private static boolean isExactNumeric(DataType type) {
        return type != null && CANONICAL_EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT));
    }

    private static int normalizedScale(Integer scale) { return scale == null ? 0 : scale; }

    private static int blockingCount(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        return (int) issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).count();
    }

    private static DatabaseSchema.Builder copySchemaHeader(DatabaseSchema schema) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(builder::metadata);
        schema.sequences().forEach(builder::addSequence);
        return builder;
    }

    private static Table.Builder copyTableHeader(Table table, String schemaName, String tableName) {
        Table.Builder builder = Table.builder(schemaName, tableName)
                .persianName(table.persianName().value())
                .description(table.description().value());
        table.physicalOptions().forEach(builder::physicalOption);
        return builder;
    }

    private static void copyTableObjects(Table source, Table.Builder target) {
        source.primaryKey().ifPresent(target::primaryKey);
        source.foreignKeys().forEach(target::addForeignKey);
        source.uniqueKeys().forEach(target::addUniqueKey);
        source.checkConstraints().forEach(target::addCheck);
        source.indexes().forEach(target::addIndex);
    }

    private static Column copyColumn(Column source, DataType dataType) {
        return new Column(source.name(), dataType, source.nullable(), source.defaultValue(), source.description(),
                source.identity(), source.ordinalPosition(), source.generatedExpression(), source.physicalOptions());
    }

    private static Path generatedTarget(Path generatedRoot, Path relativeSnapshot) {
        String normalized = normalize(relativeSnapshot);
        String file = normalized.endsWith(".schema.json")
                ? normalized.substring(0, normalized.length() - ".schema.json".length()) + ".mysql.sql"
                : normalized + ".mysql.sql";
        return generatedRoot.resolve(file.replace('/', java.io.File.separatorChar)).normalize();
    }

    private static void cleanDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Path requiredDirectory(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException(property + " must point to a directory: " + path);
        return path;
    }

    private static Path requiredFile(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(property + " must point to a file: " + path);
        return path;
    }

    private static Path outputDirectory(Path snapshotRoot) throws Exception {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        Path path = value == null
                ? snapshotRoot.resolveSibling("SchemaForge-MySQL-P2-FINAL")
                : Path.of(value).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    private static Path latestFile(Path root, String prefix, String suffix) throws Exception {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException("No " + prefix + "*" + suffix + " file found in " + root));
        }
    }

    private static int positiveInt(String value, String property) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(property + " must be a positive integer: " + value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String renderType(DataType type) {
        if (type == null) return "";
        String name = type.name().value();
        if (type.length() != null) return name + "(" + type.length() + ")";
        if (type.precision() != null) {
            return name + "(" + type.precision() + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return name;
    }

    private static String normalizedColumnKey(String schema, String table, String column) {
        return upper(schema) + "." + upper(table) + "." + upper(column);
    }

    private static String targetColumnKey(String schema, String table, String column) {
        return normalizedColumnKey(schema, table, column);
    }

    private static String evidenceKey(String snapshot, String table, String column) {
        return normalizePath(snapshot) + "|" + upper(table) + "|" + upper(column);
    }

    private static String value(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String normalizePath(String value) { return value == null ? "" : value.replace('\\', '/'); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String safe(String value) { return value == null ? "" : value; }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private record LoadedSnapshot(Path path, String relative, CanonicalSchemaSnapshot snapshot, DatabaseSchema schema) { }
    private record RecoveryAction(String schema, String table, String column) { }
    private record Overlay(DatabaseSchema schema, List<RecoveryAction> actions) { }
    private record HistoricalAction(String schema, String table, String column) { }
    private record HistoricalOverlay(DatabaseSchema schema, List<HistoricalAction> actions) { }
    private record RecoveryDirective(String stage, String snapshot, String schema, String table, String column,
                                     String evidenceSchema, String evidenceTable, String evidenceColumn) { }
    private record ConfirmedAction(String schema, String table, String column, DataType canonicalType,
                                   DataType recoveredType, RecoveryDirective directive) { }
    private record ConfirmedOverlay(DatabaseSchema schema, List<ConfirmedAction> actions) { }
    private record NumericSignature(int precision, int scale) { }
    private record IssueLocation(String table, String column) { }

    private record HistoricalEvidence(Map<NumericSignature, Set<String>> signatures) {
        int observationCount() { return signatures.values().stream().mapToInt(Set::size).sum(); }
    }

    private static final class EvidenceAccumulator {
        private final Map<NumericSignature, LinkedHashSet<String>> signatures = new LinkedHashMap<>();
        void add(NumericSignature signature, String source) {
            signatures.computeIfAbsent(signature, ignored -> new LinkedHashSet<>()).add(source);
        }
        HistoricalEvidence build() {
            Map<NumericSignature, Set<String>> copy = new LinkedHashMap<>();
            signatures.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
            return new HistoricalEvidence(Map.copyOf(copy));
        }
    }
}
