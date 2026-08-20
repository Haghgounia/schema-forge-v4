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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-R8 evidence-only cross-schema table reconciliation runner.
 *
 * <p>Starts from the P2-R4 exact-DB2 + historical-consensus overlays and evaluates only
 * P2-R6 {@code REVIEW_EXACT_NAME_OTHER_SCHEMA} candidates. The candidate is used strictly
 * as an evidence source: the canonical schema/table identity is never changed. Acceptance
 * requires one exact-name candidate in another DB2 schema, strong bidirectional column
 * coverage, independent datatype-family corroboration on non-blocked shared columns, and
 * zero datatype-family conflicts. Ambiguous candidates remain blocked. Persisted canonical
 * JSON is never modified.</p>
 */
class MySqlCrossSchemaReconciliationGenerationIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.crossschema.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.crossschema.db2SysColumnsFile";
    private static final String P2R4_DIR = "schemaforge.mysql.crossschema.p2r4Dir";
    private static final String P2R6_DIR = "schemaforge.mysql.crossschema.p2r6Dir";
    private static final String P2R7_DIR = "schemaforge.mysql.crossschema.p2r7Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.crossschema.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.mysql.crossschema.cleanOutput";
    private static final String MIN_EVIDENCE = "schemaforge.mysql.crossschema.minEvidence";
    private static final String MIN_TYPE_CORROBORATION = "schemaforge.mysql.crossschema.minTypeCorroboration";
    private static final String MIN_CANONICAL_COVERAGE = "schemaforge.mysql.crossschema.minCanonicalCoverage";
    private static final String MIN_CANDIDATE_COVERAGE = "schemaforge.mysql.crossschema.minCandidateCoverage";
    private static final String FAIL_ON_GENERATION_ERRORS = "schemaforge.mysql.crossschema.failOnGenerationErrors";

    private static final Set<String> CANONICAL_EXACT_NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
    private static final Set<String> METADATA_EXACT_NUMERIC = Set.of(
            "DECIMAL", "NUMERIC", "SMALLINT", "INTEGER", "BIGINT", "TINYINT");
    private static final String CROSS_SCHEMA_CLASSIFICATION = "REVIEW_EXACT_NAME_OTHER_SCHEMA";

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final DatatypeCompatibilityAnalyzer compatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final MySqlTypeMapper mySqlTypeMapper = new MySqlTypeMapper();
    private final Dialect mysqlDialect = DialectFactory.create(DatabasePlatform.MYSQL);

    @Test
    void appliesOnlyStrictCrossSchemaExactNameCandidatesWithoutMutatingCanonical() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path p2r4Root = requiredDirectory(P2R4_DIR);
        Path p2r6Root = requiredDirectory(P2R6_DIR);
        Path p2r7Root = requiredDirectory(P2R7_DIR);
        Path outputRoot = outputDirectory(p2r7Root);
        int minEvidence = positiveInt(System.getProperty(MIN_EVIDENCE, "1"), MIN_EVIDENCE);
        int minTypeCorroboration = positiveInt(System.getProperty(MIN_TYPE_CORROBORATION, "3"), MIN_TYPE_CORROBORATION);
        double minCanonicalCoverage = boundedRatio(System.getProperty(MIN_CANONICAL_COVERAGE, "0.90"), MIN_CANONICAL_COVERAGE);
        double minCandidateCoverage = boundedRatio(System.getProperty(MIN_CANDIDATE_COVERAGE, "0.75"), MIN_CANDIDATE_COVERAGE);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        boolean failOnGenerationErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_GENERATION_ERRORS, "false"));

        Path p2r6Details = latestFile(p2r6Root, "mysql-db2-table-reconciliation-details_", ".csv");
        int p2r4Generated = countFiles(p2r4Root.resolve("generated"), ".mysql.sql");
        int p2r4StillBlocked = countRemainingSnapshots(
                latestFile(p2r4Root, "mysql-historical-consensus-remaining_", ".csv"));
        int p2r7GeneratedNew = countFiles(p2r7Root.resolve("generated-new"), ".mysql.sql");
        int p2r7ProjectedGenerated = p2r4Generated + p2r7GeneratedNew;
        int p2r7ProjectedStillBlocked = Math.max(0, p2r4StillBlocked - p2r7GeneratedNew);

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);
        List<LoadedSnapshot> loaded = loadSnapshots(snapshotRoot);
        Map<String, LoadedSnapshot> byRelative = new LinkedHashMap<>();
        loaded.forEach(item -> byRelative.put(item.relative(), item));
        Map<String, HistoricalEvidence> historicalIndex = buildHistoricalEvidenceIndex(loaded);
        List<CrossSchemaCandidate> candidates = readCrossSchemaCandidates(p2r6Details);

        Path generatedRoot = outputRoot.resolve("generated-new");
        Files.createDirectories(outputRoot);
        if (cleanOutput) cleanDirectory(generatedRoot);
        Files.createDirectories(generatedRoot);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        List<String> details = new ArrayList<>();
        details.add("snapshot,source,schema,canonical_table,classification,candidate_schema,candidate_table,"
                + "canonical_column_count,candidate_column_count,matched_columns,blocked_columns_matched,"
                + "canonical_coverage,candidate_coverage,type_corroboration,type_conflicts,decision,"
                + "p2r4_blockers,reconciled_columns,remaining_blockers,output_file,error");
        List<String> applied = new ArrayList<>();
        applied.add("snapshot,source,canonical_schema,canonical_table,evidence_schema,evidence_table,column,"
                + "canonical_type,metadata_type,classification");
        List<String> remaining = new ArrayList<>();
        remaining.add("snapshot,source,schema,canonical_table,evidence_schema,decision,remaining_blockers,blocking_codes");

        Map<String, Integer> decisions = new LinkedHashMap<>();
        Map<String, Integer> remainingByCode = new LinkedHashMap<>();
        Map<String, Integer> generationErrors = new LinkedHashMap<>();
        int confirmedCandidates = 0;
        int reconciliationOccurrences = 0;
        Set<String> uniqueReconciledColumns = new LinkedHashSet<>();
        int newlyUnblocked = 0;
        int stillBlockedCrossSchema = 0;
        int generatedNew = 0;
        int generationFailures = 0;

        for (CrossSchemaCandidate candidate : candidates) {
            LoadedSnapshot item = byRelative.get(candidate.snapshot());
            if (item == null) {
                decisions.merge("REJECT_SNAPSHOT_NOT_FOUND", 1, Integer::sum);
                stillBlockedCrossSchema++;
                details.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.classification(), candidate.candidateSchema(), candidate.candidateTable(),
                        Integer.toString(candidate.canonicalColumnCount()), Integer.toString(candidate.candidateColumnCount()),
                        Integer.toString(candidate.matchedColumns()), Integer.toString(candidate.blockedMatched()),
                        decimal(candidate.coverage()), decimal(candidate.candidateCoverage()), "0", "0",
                        "REJECT_SNAPSHOT_NOT_FOUND", "0", "0", "0", "", "snapshot not loaded"));
                continue;
            }

            Overlay db2Overlay = applyDb2Overlay(item.schema(), catalog);
            HistoricalOverlay historicalOverlay = applyHistoricalConsensusOverlay(db2Overlay.schema(), historicalIndex, minEvidence);
            var p2r4Assessment = compatibilityAnalyzer.analyze(historicalOverlay.schema(), mysqlDialect);
            int p2r4Blockers = blockingCount(p2r4Assessment.issues());

            CandidateValidation validation = validateCandidate(
                    item.schema(), candidate, catalog, minTypeCorroboration, minCanonicalCoverage, minCandidateCoverage);
            decisions.merge(validation.decision(), 1, Integer::sum);

            if (!validation.accepted()) {
                stillBlockedCrossSchema++;
                remaining.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.candidateSchema(), validation.decision(), Integer.toString(p2r4Blockers),
                        blockingCodes(p2r4Assessment.issues())));
                details.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.classification(), candidate.candidateSchema(), candidate.candidateTable(),
                        Integer.toString(candidate.canonicalColumnCount()), Integer.toString(candidate.candidateColumnCount()),
                        Integer.toString(candidate.matchedColumns()), Integer.toString(candidate.blockedMatched()),
                        decimal(candidate.coverage()), decimal(candidate.candidateCoverage()),
                        Integer.toString(validation.corroboration()), Integer.toString(validation.conflicts()),
                        validation.decision(), Integer.toString(p2r4Blockers), "0", Integer.toString(p2r4Blockers), "", ""));
                continue;
            }

            confirmedCandidates++;
            ReconciliationOverlay reconciled = applyReconciliationOverlay(historicalOverlay.schema(), candidate, catalog);
            reconciliationOccurrences += reconciled.actions().size();
            for (ReconciliationAction action : reconciled.actions()) {
                uniqueReconciledColumns.add(normalizedColumnKey(action.schema(), action.table(), action.column()));
                applied.add(csvLine(candidate.snapshot(), candidate.source(), action.schema(), action.table(),
                        candidate.candidateSchema(), candidate.candidateTable(), action.column(),
                        renderType(action.canonicalType()), renderType(action.metadataType()), candidate.classification()));
            }

            var reconciledAssessment = compatibilityAnalyzer.analyze(reconciled.schema(), mysqlDialect);
            int remainingBlockers = blockingCount(reconciledAssessment.issues());
            if (reconciledAssessment.blocking()) {
                stillBlockedCrossSchema++;
                for (var issue : reconciledAssessment.issues()) {
                    if (!"ERROR".equalsIgnoreCase(issue.severity())) continue;
                    remainingByCode.merge(issue.code(), 1, Integer::sum);
                }
                remaining.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.candidateSchema(), "CONFIRMED_BUT_STILL_BLOCKED", Integer.toString(remainingBlockers),
                        blockingCodes(reconciledAssessment.issues())));
                details.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.classification(), candidate.candidateSchema(), candidate.candidateTable(),
                        Integer.toString(candidate.canonicalColumnCount()), Integer.toString(candidate.candidateColumnCount()),
                        Integer.toString(candidate.matchedColumns()), Integer.toString(candidate.blockedMatched()),
                        decimal(candidate.coverage()), decimal(candidate.candidateCoverage()),
                        Integer.toString(validation.corroboration()), Integer.toString(validation.conflicts()),
                        "CONFIRMED_BUT_STILL_BLOCKED", Integer.toString(p2r4Blockers),
                        Integer.toString(reconciled.actions().size()), Integer.toString(remainingBlockers), "", ""));
                continue;
            }

            if (p2r4Assessment.blocking()) newlyUnblocked++;
            Path target = generatedTarget(generatedRoot, snapshotRoot.relativize(item.path()));
            try {
                PreparedSchema prepared = preparationService.prepare(reconciled.schema());
                String sql = new DdlGenerator(mysqlDialect).generate(prepared.schema(), prepared.validationReport());
                Files.createDirectories(target.getParent());
                Files.writeString(target, sql, StandardCharsets.UTF_8);
                generatedNew++;
                details.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.classification(), candidate.candidateSchema(), candidate.candidateTable(),
                        Integer.toString(candidate.canonicalColumnCount()), Integer.toString(candidate.candidateColumnCount()),
                        Integer.toString(candidate.matchedColumns()), Integer.toString(candidate.blockedMatched()),
                        decimal(candidate.coverage()), decimal(candidate.candidateCoverage()),
                        Integer.toString(validation.corroboration()), Integer.toString(validation.conflicts()),
                        "CONFIRMED_AND_GENERATED", Integer.toString(p2r4Blockers),
                        Integer.toString(reconciled.actions().size()), "0", normalize(outputRoot.relativize(target)), ""));
            } catch (Exception exception) {
                generationFailures++;
                String error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                generationErrors.merge(error, 1, Integer::sum);
                details.add(csvLine(candidate.snapshot(), candidate.source(), candidate.schema(), candidate.table(),
                        candidate.classification(), candidate.candidateSchema(), candidate.candidateTable(),
                        Integer.toString(candidate.canonicalColumnCount()), Integer.toString(candidate.candidateColumnCount()),
                        Integer.toString(candidate.matchedColumns()), Integer.toString(candidate.blockedMatched()),
                        decimal(candidate.coverage()), decimal(candidate.candidateCoverage()),
                        Integer.toString(validation.corroboration()), Integer.toString(validation.conflicts()),
                        "GENERATION_FAILED", Integer.toString(p2r4Blockers), Integer.toString(reconciled.actions().size()),
                        "0", normalize(outputRoot.relativize(target)), error));
            }
        }

        Path detailsFile = outputRoot.resolve("mysql-cross-schema-reconciliation-details_" + timestamp + ".csv");
        Path appliedFile = outputRoot.resolve("mysql-cross-schema-reconciliation-applied_" + timestamp + ".csv");
        Path remainingFile = outputRoot.resolve("mysql-cross-schema-reconciliation-remaining_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-cross-schema-reconciliation-summary_" + timestamp + ".txt");
        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(appliedFile, String.join(System.lineSeparator(), applied) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(remainingFile, String.join(System.lineSeparator(), remaining) + System.lineSeparator(), StandardCharsets.UTF_8);

        int projectedGenerated = p2r7ProjectedGenerated + newlyUnblocked;
        int projectedStillBlocked = Math.max(0, p2r7ProjectedStillBlocked - newlyUnblocked);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL P2-R8 cross-schema reconciliation generation audit");
        summary.add("==============================================================");
        summary.add("Snapshot directory             : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file            : " + metadataFile);
        summary.add("P2-R4 directory                : " + p2r4Root);
        summary.add("P2-R6 details                  : " + p2r6Details);
        summary.add("P2-R7 directory                : " + p2r7Root);
        summary.add("Snapshots loaded               : " + loaded.size());
        summary.add("P2-R4 generated                : " + p2r4Generated);
        summary.add("P2-R4 still blocked            : " + p2r4StillBlocked);
        summary.add("P2-R7 generated new            : " + p2r7GeneratedNew);
        summary.add("P2-R7 projected generated      : " + p2r7ProjectedGenerated);
        summary.add("P2-R7 projected still blocked  : " + p2r7ProjectedStillBlocked);
        summary.add("P2-R6 cross-schema candidates  : " + candidates.size());
        summary.add("Minimum type corroboration     : " + minTypeCorroboration);
        summary.add("Minimum canonical coverage     : " + decimal(minCanonicalCoverage));
        summary.add("Minimum candidate coverage     : " + decimal(minCandidateCoverage));
        summary.add("Confirmed cross-schema         : " + confirmedCandidates);
        summary.add("Reconciliation occurrences     : " + reconciliationOccurrences);
        summary.add("Unique reconciled columns      : " + uniqueReconciledColumns.size());
        summary.add("Newly unblocked snapshots      : " + newlyUnblocked);
        summary.add("Still blocked cross-schema     : " + stillBlockedCrossSchema);
        summary.add("Generated new                  : " + generatedNew);
        summary.add("Projected generated total      : " + projectedGenerated);
        summary.add("Projected still blocked        : " + projectedStillBlocked);
        summary.add("Generation failures            : " + generationFailures);
        summary.add("");
        summary.add("Candidate decisions");
        summary.add("-------------------");
        if (decisions.isEmpty()) summary.add("None");
        else decisions.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Remaining blocker codes among confirmed candidates");
        summary.add("---------------------------------------------------");
        if (remainingByCode.isEmpty()) summary.add("None");
        else remainingByCode.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Generation errors");
        summary.add("-----------------");
        if (generationErrors.isEmpty()) summary.add("None");
        else generationErrors.forEach((key, value) -> summary.add(value + " : " + key));
        summary.add("");
        summary.add("Details   : " + detailsFile);
        summary.add("Applied   : " + appliedFile);
        summary.add("Remaining : " + remainingFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("P2-R7 projected generated      : " + p2r7ProjectedGenerated);
        System.out.println("P2-R7 projected still blocked  : " + p2r7ProjectedStillBlocked);
        System.out.println("P2-R6 cross-schema candidates  : " + candidates.size());
        System.out.println("Confirmed cross-schema         : " + confirmedCandidates);
        System.out.println("Reconciliation occurrences     : " + reconciliationOccurrences);
        System.out.println("Newly unblocked snapshots      : " + newlyUnblocked);
        System.out.println("Still blocked cross-schema     : " + stillBlockedCrossSchema);
        System.out.println("Projected generated total      : " + projectedGenerated);
        System.out.println("Projected still blocked        : " + projectedStillBlocked);
        System.out.println("Generation failures            : " + generationFailures);
        decisions.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                        : " + summaryFile);

        assertTrue(!candidates.isEmpty(), "No P2-R6 REVIEW_EXACT_NAME_OTHER_SCHEMA candidates were found");
        if (failOnGenerationErrors) {
            assertTrue(generationFailures == 0, "Generation failures remain; see " + detailsFile);
        }
    }

    private CandidateValidation validateCandidate(
            DatabaseSchema original, CrossSchemaCandidate candidate, Db2SysColumnsFileCatalog catalog,
            int minTypeCorroboration, double minCanonicalCoverage, double minCandidateCoverage) {
        if (!CROSS_SCHEMA_CLASSIFICATION.equals(candidate.classification())) {
            return new CandidateValidation(false, "REJECT_NOT_CROSS_SCHEMA_REVIEW", 0, 0);
        }
        if (normalizeIdentifier(candidate.schema()).equals(normalizeIdentifier(candidate.candidateSchema()))) {
            return new CandidateValidation(false, "REJECT_SAME_SCHEMA", 0, 0);
        }
        if (!normalizeIdentifier(candidate.table()).equals(normalizeIdentifier(candidate.candidateTable()))) {
            return new CandidateValidation(false, "REJECT_TABLE_NAME_NOT_EXACT", 0, 0);
        }
        if (candidate.candidateCount() != 1) {
            return new CandidateValidation(false, "REJECT_NON_UNIQUE_CANDIDATE", 0, 0);
        }
        if (candidate.canonicalColumnCount() <= 0 || candidate.candidateColumnCount() <= 0) {
            return new CandidateValidation(false, "REJECT_INVALID_COLUMN_COUNTS", 0, 0);
        }
        if (candidate.coverage() < minCanonicalCoverage) {
            return new CandidateValidation(false, "REJECT_CANONICAL_COVERAGE", 0, 0);
        }
        if (candidate.candidateCoverage() < minCandidateCoverage) {
            return new CandidateValidation(false, "REJECT_CANDIDATE_COVERAGE", 0, 0);
        }
        if (candidate.blockedMatched() <= 0) {
            return new CandidateValidation(false, "REJECT_NO_BLOCKED_COLUMN_MATCH", 0, 0);
        }

        LocatedTable table = locateTable(original, candidate.table());
        if (table == null) return new CandidateValidation(false, "REJECT_CANONICAL_TABLE_NOT_FOUND", 0, 0);

        int corroboration = 0;
        int conflicts = 0;
        Set<String> blocked = splitPipe(candidate.blockedColumns());
        for (Column column : table.table().columns()) {
            String columnName = normalizeIdentifier(column.name().value());
            if (blocked.contains(columnName)) continue;
            if (catalog.lookupStatus(candidate.candidateSchema(), candidate.candidateTable(), columnName)
                    != Db2SysColumnsFileCatalog.LookupStatus.USABLE) continue;
            DataType metadataType = catalog.findType(candidate.candidateSchema(), candidate.candidateTable(), columnName)
                    .orElse(null);
            if (metadataType == null) continue;
            TypeFamily canonicalFamily = typeFamily(column.dataType());
            TypeFamily metadataFamily = typeFamily(metadataType);
            if (canonicalFamily == TypeFamily.UNKNOWN || metadataFamily == TypeFamily.UNKNOWN) continue;
            if (canonicalFamily == metadataFamily) corroboration++; else conflicts++;
        }

        if (conflicts > 0) {
            return new CandidateValidation(false, "REJECT_TYPE_FAMILY_CONFLICT", corroboration, conflicts);
        }
        if (corroboration < minTypeCorroboration) {
            return new CandidateValidation(false, "REJECT_INSUFFICIENT_TYPE_CORROBORATION", corroboration, 0);
        }
        return new CandidateValidation(true, "CONFIRMED_CROSS_SCHEMA_EXACT_NAME_TYPE_CORROBORATED", corroboration, 0);
    }

    private ReconciliationOverlay applyReconciliationOverlay(
            DatabaseSchema schema, CrossSchemaCandidate candidate, Db2SysColumnsFileCatalog catalog) {
        DatabaseSchema.Builder schemaBuilder = copySchemaHeader(schema);
        List<ReconciliationAction> actions = new ArrayList<>();
        Set<String> blocked = splitPipe(candidate.blockedColumns());

        for (Table table : schema.tables()) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            String tableName = table.qualifiedName().name().value();
            Table.Builder tableBuilder = copyTableHeader(table, schemaName, tableName);
            boolean target = normalizeIdentifier(tableName).equals(normalizeIdentifier(candidate.table()))
                    && normalizeIdentifier(schemaName).equals(normalizeIdentifier(candidate.schema()));

            for (Column column : table.columns()) {
                DataType canonicalType = column.dataType();
                String columnName = normalizeIdentifier(column.name().value());
                if (!target || !blocked.contains(columnName) || !isExactNumeric(canonicalType)
                        || canonicalType.precision() != null) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                DataType metadataType = mappedRecoveryType(catalog, candidate, columnName);
                if (metadataType == null) {
                    tableBuilder.addColumn(column);
                    continue;
                }
                tableBuilder.addColumn(copyColumn(column, metadataType));
                actions.add(new ReconciliationAction(schemaName, tableName, column.name().value(), canonicalType, metadataType));
            }
            copyTableObjects(table, tableBuilder);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new ReconciliationOverlay(schemaBuilder.build(), List.copyOf(actions));
    }

    private DataType mappedRecoveryType(Db2SysColumnsFileCatalog catalog, CrossSchemaCandidate candidate, String column) {
        if (catalog.lookupStatus(candidate.candidateSchema(), candidate.candidateTable(), column)
                != Db2SysColumnsFileCatalog.LookupStatus.USABLE) return null;
        DataType metadataType = catalog.findType(candidate.candidateSchema(), candidate.candidateTable(), column).orElse(null);
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

    private List<LoadedSnapshot> loadSnapshots(Path snapshotRoot) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(snapshotRoot)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }
        List<LoadedSnapshot> loaded = new ArrayList<>();
        for (Path path : paths) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                loaded.add(new LoadedSnapshot(path, normalize(snapshotRoot.relativize(path)), snapshot,
                        mapper.toDomainPersistedSource(snapshot)));
            } catch (RuntimeException ignored) {
                // P2 corpus audits already report read failures separately; this runner stays evidence-only.
            }
        }
        return List.copyOf(loaded);
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

    private HistoricalOverlay applyHistoricalConsensusOverlay(
            DatabaseSchema schema, Map<String, HistoricalEvidence> evidenceIndex, int minEvidence) {
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

    private DataType db2RecoveryType(
            DataType canonicalType, Db2SysColumnsFileCatalog catalog, String schema, String table, String column) {
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

    private static TypeFamily typeFamily(DataType type) {
        if (type == null) return TypeFamily.UNKNOWN;
        String name = type.name().normalized().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC", "SMALLINT", "INTEGER", "INT", "BIGINT", "TINYINT",
                "FLOAT", "REAL", "DOUBLE", "DECFLOAT", "BINARY_FLOAT", "BINARY_DOUBLE").contains(name)) {
            return TypeFamily.NUMERIC;
        }
        if (Set.of("CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "NCHAR",
                "GRAPHIC", "VARGRAPHIC", "VARG").contains(name)) return TypeFamily.CHARACTER;
        if (name.startsWith("TIMESTAMP") || Set.of("DATE", "TIME", "DATETIME", "DATETIME2", "DATETIMEOFFSET").contains(name)) {
            return TypeFamily.TEMPORAL;
        }
        if (Set.of("BLOB", "CLOB", "DBCLOB", "LONGVAR", "LONGVARG", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(name)) {
            return TypeFamily.LOB;
        }
        if (Set.of("BINARY", "VARBINARY", "VARBIN", "RAW", "LONG RAW").contains(name)) return TypeFamily.BINARY;
        if (Set.of("BOOLEAN", "BOOL", "BIT").contains(name)) return TypeFamily.BOOLEAN;
        if (Set.of("XML", "JSON").contains(name)) return TypeFamily.DOCUMENT;
        if (name.equals("ROWID")) return TypeFamily.ROWID;
        return TypeFamily.UNKNOWN;
    }

    private static LocatedTable locateTable(DatabaseSchema schema, String tableName) {
        for (Table table : schema.tables()) {
            if (!normalizeIdentifier(table.qualifiedName().name().value()).equals(normalizeIdentifier(tableName))) continue;
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            return new LocatedTable(schemaName, table);
        }
        return null;
    }

    private static List<CrossSchemaCandidate> readCrossSchemaCandidates(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);

        int snapshot = requiredIndex(index, "snapshot");
        int source = requiredIndex(index, "source");
        int schema = requiredIndex(index, "schema");
        int table = requiredIndex(index, "canonical_table");
        int blockedColumns = requiredIndex(index, "blocked_columns");
        int canonicalColumnCount = requiredIndex(index, "canonical_column_count");
        int classification = requiredIndex(index, "classification");
        int candidateSchema = requiredIndex(index, "candidate_schema");
        int candidateTable = requiredIndex(index, "candidate_table");
        int candidateColumnCount = requiredIndex(index, "candidate_column_count");
        int matchedColumns = requiredIndex(index, "matched_columns");
        int blockedMatched = requiredIndex(index, "blocked_columns_matched");
        int coverage = requiredIndex(index, "canonical_coverage");
        int candidateCount = requiredIndex(index, "candidate_count");

        List<CrossSchemaCandidate> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> row = parseCsvLine(lines.get(i));
            String cls = value(row, classification);
            if (!CROSS_SCHEMA_CLASSIFICATION.equals(cls)) continue;
            int matched = integer(value(row, matchedColumns));
            int candidateColumns = integer(value(row, candidateColumnCount));
            double reverseCoverage = candidateColumns <= 0 ? 0.0 : (double) matched / candidateColumns;
            result.add(new CrossSchemaCandidate(
                    normalizePath(value(row, snapshot)), value(row, source), value(row, schema), value(row, table),
                    value(row, blockedColumns), integer(value(row, canonicalColumnCount)), cls,
                    value(row, candidateSchema), value(row, candidateTable), candidateColumns, matched,
                    integer(value(row, blockedMatched)), decimalValue(value(row, coverage)), reverseCoverage,
                    integer(value(row, candidateCount))));
        }
        return List.copyOf(result);
    }

    private static int countRemainingSnapshots(Path remainingFile) throws Exception {
        List<String> lines = Files.readAllLines(remainingFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return 0;
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        int snapshot = requiredIndex(index, "snapshot");
        Set<String> values = new LinkedHashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            values.add(normalizePath(value(parseCsvLine(lines.get(i)), snapshot)));
        }
        return values.size();
    }

    private static int countFiles(Path root, String suffix) throws Exception {
        if (!Files.isDirectory(root)) return 0;
        try (var stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static boolean isExactNumeric(DataType type) {
        return CANONICAL_EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT));
    }

    private static int normalizedScale(Integer scale) { return scale == null ? 0 : scale; }

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

    private static int blockingCount(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        return (int) issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).count();
    }

    private static String blockingCodes(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).forEach(issue -> codes.add(issue.code()));
        return String.join("|", codes);
    }

    private static String normalizedColumnKey(String schema, String table, String column) {
        return normalizeIdentifier(schema) + "." + normalizeIdentifier(table) + "." + normalizeIdentifier(column);
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

    private static String renderType(DataType type) {
        if (type == null) return "";
        String name = type.name().value();
        if (type.length() != null) return name + "(" + type.length() + ")";
        if (type.precision() != null) {
            return name + "(" + type.precision() + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return name;
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

    private static Path outputDirectory(Path p2r7Root) throws Exception {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        Path path = value == null
                ? p2r7Root.resolveSibling("SchemaForge-MySQL-P2-R8")
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

    private static int requiredIndex(Map<String, Integer> index, String name) {
        Integer value = index.get(name);
        if (value == null) throw new IllegalArgumentException("Missing CSV column: " + name);
        return value;
    }

    private static String value(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                result.add(cell.toString()); cell.setLength(0);
            } else cell.append(ch);
        }
        result.add(cell.toString());
        return result;
    }

    private static Set<String> splitPipe(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return result;
        for (String item : value.split("\\|")) {
            String normalized = normalizeIdentifier(item);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static double decimalValue(String value) {
        try { return Double.parseDouble(value.trim()); }
        catch (RuntimeException ignored) { return 0.0; }
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

    private static double boundedRatio(String value, String property) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed < 0.0 || parsed > 1.0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(property + " must be a decimal ratio between 0 and 1: " + value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String normalize(Path path) { return path.toString().replace('\\', '/'); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.3f", value); }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private enum TypeFamily { NUMERIC, CHARACTER, TEMPORAL, LOB, BINARY, BOOLEAN, DOCUMENT, ROWID, UNKNOWN }

    private record LoadedSnapshot(Path path, String relative, CanonicalSchemaSnapshot snapshot, DatabaseSchema schema) { }
    private record LocatedTable(String schema, Table table) { }
    private record CrossSchemaCandidate(String snapshot, String source, String schema, String table, String blockedColumns,
                                        int canonicalColumnCount, String classification, String candidateSchema,
                                        String candidateTable, int candidateColumnCount, int matchedColumns,
                                        int blockedMatched, double coverage, double candidateCoverage, int candidateCount) { }
    private record CandidateValidation(boolean accepted, String decision, int corroboration, int conflicts) { }
    private record RecoveryAction(String schema, String table, String column) { }
    private record Overlay(DatabaseSchema schema, List<RecoveryAction> actions) { }
    private record HistoricalAction(String schema, String table, String column) { }
    private record HistoricalOverlay(DatabaseSchema schema, List<HistoricalAction> actions) { }
    private record ReconciliationAction(String schema, String table, String column,
                                        DataType canonicalType, DataType metadataType) { }
    private record ReconciliationOverlay(DatabaseSchema schema, List<ReconciliationAction> actions) { }
    private record NumericSignature(int precision, int scale) { }

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
