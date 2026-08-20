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
 * P2-R4 audit runner. After the exact DB2 SYSCOLUMNS overlay used by P2-R3, it applies a second
 * MySQL-only in-memory recovery layer from unanimous historical canonical evidence for the exact
 * same schema/table/column. Persisted canonical JSON is never mutated.
 *
 * <p>Historical evidence is accepted only when every explicit precision/scale observation for the
 * exact column agrees. Conflicting historical definitions remain blocked rather than guessed.</p>
 */
class MySqlHistoricalConsensusRecoveryGenerationIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.consensus.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.consensus.db2SysColumnsFile";
    private static final String OUTPUT_DIR = "schemaforge.mysql.consensus.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.mysql.consensus.cleanOutput";
    private static final String MIN_EVIDENCE = "schemaforge.mysql.consensus.minEvidence";
    private static final String FAIL_ON_GENERATION_ERRORS = "schemaforge.mysql.consensus.failOnGenerationErrors";

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
    void measuresDb2PlusHistoricalConsensusRecoveryWithoutMutatingCanonicalSnapshots() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path outputRoot = outputDirectory(snapshotRoot);
        int minEvidence = positiveInt(System.getProperty(MIN_EVIDENCE, "1"), MIN_EVIDENCE);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        boolean failOnGenerationErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_GENERATION_ERRORS, "false"));

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);
        Path generatedRoot = outputRoot.resolve("generated");
        Files.createDirectories(outputRoot);
        if (cleanOutput) cleanDirectory(generatedRoot);
        Files.createDirectories(generatedRoot);

        List<Path> snapshotPaths;
        try (var paths = Files.walk(snapshotRoot)) {
            snapshotPaths = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }

        List<LoadedSnapshot> loaded = new ArrayList<>();
        int snapshotReadFailures = 0;
        for (Path snapshotPath : snapshotPaths) {
            String relative = normalize(snapshotRoot.relativize(snapshotPath));
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                loaded.add(new LoadedSnapshot(snapshotPath, relative, snapshot, schema));
            } catch (Exception ignored) {
                snapshotReadFailures++;
            }
        }

        Map<String, HistoricalEvidence> evidenceIndex = buildHistoricalEvidenceIndex(loaded);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        List<String> details = new ArrayList<>();
        details.add("snapshot,source,status,baseline_blockers,db2_recovery,historical_recovery,remaining_blockers,output_file,error");
        List<String> applied = new ArrayList<>();
        applied.add("snapshot,source,schema,table,column,canonical_type,recovered_type,evidence_count,evidence_sources");
        List<String> evidence = new ArrayList<>();
        evidence.add("snapshot,source,schema,table,column,classification,explicit_signatures,evidence_count,evidence_sources");
        List<String> remaining = new ArrayList<>();
        remaining.add("snapshot,source,severity,code,path,message");

        int baselineCompatible = 0;
        int baselineBlocked = 0;
        int db2RecoveryOccurrences = 0;
        Set<String> uniqueDb2Recovered = new LinkedHashSet<>();
        int historicalRecoveryOccurrences = 0;
        Set<String> uniqueHistoricalRecovered = new LinkedHashSet<>();
        int db2NewlyUnblocked = 0;
        int historicalNewlyUnblocked = 0;
        int stillBlocked = 0;
        int generated = 0;
        int generationFailures = 0;
        int remainingOccurrences = 0;
        Set<String> uniqueRemaining = new LinkedHashSet<>();
        Map<String, Integer> remainingByCode = new LinkedHashMap<>();
        Map<String, Integer> historicalClassificationCounts = new LinkedHashMap<>();
        Map<String, Integer> generationErrors = new LinkedHashMap<>();

        for (LoadedSnapshot item : loaded) {
            String source = item.snapshot().source() == null ? "" : safe(item.snapshot().source().relativePath());
            DatabaseSchema original = item.schema();
            var baselineAssessment = compatibilityAnalyzer.analyze(original, mysqlDialect);
            int baselineBlockers = blockingCount(baselineAssessment.issues());
            if (baselineAssessment.blocking()) baselineBlocked++; else baselineCompatible++;

            Overlay db2Overlay = applyDb2Overlay(original, catalog);
            db2RecoveryOccurrences += db2Overlay.actions().size();
            db2Overlay.actions().forEach(action -> uniqueDb2Recovered.add(
                    normalizedColumnKey(action.schema(), action.table(), action.column())));
            var db2Assessment = compatibilityAnalyzer.analyze(db2Overlay.schema(), mysqlDialect);
            if (baselineAssessment.blocking() && !db2Assessment.blocking()) db2NewlyUnblocked++;

            HistoricalOverlay historicalOverlay = applyHistoricalConsensusOverlay(
                    db2Overlay.schema(), evidenceIndex, minEvidence);
            historicalRecoveryOccurrences += historicalOverlay.actions().size();
            for (HistoricalAction action : historicalOverlay.actions()) {
                uniqueHistoricalRecovered.add(normalizedColumnKey(action.schema(), action.table(), action.column()));
                applied.add(csvLine(item.relative(), source, action.schema(), action.table(), action.column(),
                        renderType(action.canonicalType()), renderType(action.recoveredType()),
                        Integer.toString(action.evidenceCount()), String.join("|", action.sources())));
            }
            for (HistoricalDecision decision : historicalOverlay.decisions()) {
                historicalClassificationCounts.merge(decision.classification(), 1, Integer::sum);
                evidence.add(csvLine(item.relative(), source, decision.schema(), decision.table(), decision.column(),
                        decision.classification(), decision.signatures(), Integer.toString(decision.evidenceCount()),
                        String.join("|", decision.sources())));
            }

            var combinedAssessment = compatibilityAnalyzer.analyze(historicalOverlay.schema(), mysqlDialect);
            int remainingBlockers = blockingCount(combinedAssessment.issues());
            if (db2Assessment.blocking() && !combinedAssessment.blocking()) historicalNewlyUnblocked++;

            if (combinedAssessment.blocking()) {
                stillBlocked++;
                for (var issue : combinedAssessment.issues()) {
                    if (!"ERROR".equalsIgnoreCase(issue.severity())) continue;
                    remainingOccurrences++;
                    remainingByCode.merge(issue.code(), 1, Integer::sum);
                    uniqueRemaining.add(normalizedIssueKey(historicalOverlay.schema(), issue.path()));
                    remaining.add(csvLine(item.relative(), source, issue.severity(), issue.code(), issue.path(), issue.message()));
                }
                details.add(csvLine(item.relative(), source, "STILL_BLOCKED", Integer.toString(baselineBlockers),
                        Integer.toString(db2Overlay.actions().size()), Integer.toString(historicalOverlay.actions().size()),
                        Integer.toString(remainingBlockers), "", ""));
                continue;
            }

            Path target = generatedTarget(generatedRoot, snapshotRoot.relativize(item.path()));
            try {
                PreparedSchema prepared = preparationService.prepare(historicalOverlay.schema());
                String sql = new DdlGenerator(mysqlDialect).generate(prepared.schema(), prepared.validationReport());
                Files.createDirectories(target.getParent());
                Files.writeString(target, sql, StandardCharsets.UTF_8);
                generated++;
                details.add(csvLine(item.relative(), source,
                        baselineAssessment.blocking() ? "RECOVERED_AND_GENERATED" : "GENERATED_BASELINE_COMPATIBLE",
                        Integer.toString(baselineBlockers), Integer.toString(db2Overlay.actions().size()),
                        Integer.toString(historicalOverlay.actions().size()), "0",
                        normalize(outputRoot.relativize(target)), ""));
            } catch (Exception exception) {
                generationFailures++;
                String error = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                generationErrors.merge(error, 1, Integer::sum);
                details.add(csvLine(item.relative(), source, "GENERATION_FAILED", Integer.toString(baselineBlockers),
                        Integer.toString(db2Overlay.actions().size()), Integer.toString(historicalOverlay.actions().size()),
                        "0", normalize(outputRoot.relativize(target)), error));
            }
        }

        Path summaryFile = outputRoot.resolve("mysql-historical-consensus-summary_" + timestamp + ".txt");
        Path detailsFile = outputRoot.resolve("mysql-historical-consensus-details_" + timestamp + ".csv");
        Path appliedFile = outputRoot.resolve("mysql-historical-consensus-applied_" + timestamp + ".csv");
        Path evidenceFile = outputRoot.resolve("mysql-historical-consensus-evidence_" + timestamp + ".csv");
        Path remainingFile = outputRoot.resolve("mysql-historical-consensus-remaining_" + timestamp + ".csv");

        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(appliedFile, String.join(System.lineSeparator(), applied) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(evidenceFile, String.join(System.lineSeparator(), evidence) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(remainingFile, String.join(System.lineSeparator(), remaining) + System.lineSeparator(), StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL DB2 + historical consensus recovery audit");
        summary.add("===========================================================");
        summary.add("Snapshot directory          : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file         : " + metadataFile);
        summary.add("Snapshots discovered        : " + snapshotPaths.size());
        summary.add("Snapshots loaded            : " + loaded.size());
        summary.add("Snapshot read failures      : " + snapshotReadFailures);
        summary.add("Minimum historical evidence : " + minEvidence);
        summary.add("Historical evidence keys    : " + evidenceIndex.size());
        summary.add("Baseline compatible         : " + baselineCompatible);
        summary.add("Baseline blocked            : " + baselineBlocked);
        summary.add("DB2 recovery occurrences    : " + db2RecoveryOccurrences);
        summary.add("Unique DB2 recovered cols   : " + uniqueDb2Recovered.size());
        summary.add("DB2 newly unblocked         : " + db2NewlyUnblocked);
        summary.add("Historical recovery occ.    : " + historicalRecoveryOccurrences);
        summary.add("Unique historical rec. cols : " + uniqueHistoricalRecovered.size());
        summary.add("Historical newly unblocked  : " + historicalNewlyUnblocked);
        summary.add("Still blocked snapshots     : " + stillBlocked);
        summary.add("Generated after overlays    : " + generated);
        summary.add("Generation failures         : " + generationFailures);
        summary.add("Remaining blocker occ.      : " + remainingOccurrences);
        summary.add("Unique remaining blockers   : " + uniqueRemaining.size());
        summary.add("");
        summary.add("Historical evidence classifications");
        summary.add("-----------------------------------");
        if (historicalClassificationCounts.isEmpty()) summary.add("None");
        else historicalClassificationCounts.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Remaining blockers by code");
        summary.add("--------------------------");
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
        summary.add("Evidence  : " + evidenceFile);
        summary.add("Remaining : " + remainingFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("Snapshots discovered        : " + snapshotPaths.size());
        System.out.println("Baseline compatible         : " + baselineCompatible);
        System.out.println("Baseline blocked            : " + baselineBlocked);
        System.out.println("DB2 newly unblocked         : " + db2NewlyUnblocked);
        System.out.println("Historical recovery occ.    : " + historicalRecoveryOccurrences);
        System.out.println("Unique historical rec. cols : " + uniqueHistoricalRecovered.size());
        System.out.println("Historical newly unblocked  : " + historicalNewlyUnblocked);
        System.out.println("Still blocked snapshots     : " + stillBlocked);
        System.out.println("Generated after overlays    : " + generated);
        System.out.println("Generation failures         : " + generationFailures);
        historicalClassificationCounts.forEach((key, value) -> System.out.println(key + " : " + value));
        remainingByCode.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                      : " + summaryFile);

        assertTrue(baselineBlocked > 0, "No baseline MySQL datatype blockers were found");
        assertTrue(db2RecoveryOccurrences > 0, "No DB2 evidence-backed recoveries were applied");
        if (failOnGenerationErrors) {
            assertTrue(generationFailures == 0, "Generation failures remain after overlays; see " + detailsFile);
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
                if (recovered == null) {
                    tableBuilder.addColumn(column);
                } else {
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
        List<HistoricalDecision> decisions = new ArrayList<>();

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

                String key = normalizedColumnKey(schemaName, tableName, column.name().value());
                HistoricalEvidence historical = evidenceIndex.get(key);
                HistoricalDecision decision = classifyHistoricalEvidence(
                        schemaName, tableName, column.name().value(), historical, minEvidence);
                decisions.add(decision);

                if (!"HISTORICAL_CONSENSUS_APPLIED".equals(decision.classification())) {
                    tableBuilder.addColumn(column);
                    continue;
                }

                NumericSignature signature = historical.signatures().keySet().iterator().next();
                DataType recovered = DataType.numeric(
                        canonicalType.name().value(), signature.precision(), signature.scale() == 0 ? null : signature.scale());
                try {
                    mySqlTypeMapper.map(recovered);
                } catch (RuntimeException unsupported) {
                    decisions.set(decisions.size() - 1, new HistoricalDecision(schemaName, tableName,
                            column.name().value(), "HISTORICAL_CONSENSUS_STILL_UNSUPPORTED",
                            renderSignatures(historical), historical.observationCount(), historical.sources()));
                    tableBuilder.addColumn(column);
                    continue;
                }

                tableBuilder.addColumn(copyColumn(column, recovered));
                actions.add(new HistoricalAction(schemaName, tableName, column.name().value(), canonicalType,
                        recovered, historical.observationCount(), historical.sources()));
            }
            copyTableObjects(table, tableBuilder);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new HistoricalOverlay(schemaBuilder.build(), List.copyOf(actions), List.copyOf(decisions));
    }

    private HistoricalDecision classifyHistoricalEvidence(
            String schema, String table, String column, HistoricalEvidence historical, int minEvidence) {
        if (historical == null || historical.signatures().isEmpty()) {
            return new HistoricalDecision(schema, table, column, "HISTORICAL_NO_EXPLICIT_EVIDENCE", "", 0, List.of());
        }
        if (historical.signatures().size() > 1) {
            return new HistoricalDecision(schema, table, column, "HISTORICAL_CONFLICTING_EXPLICIT_EVIDENCE",
                    renderSignatures(historical), historical.observationCount(), historical.sources());
        }
        if (historical.observationCount() < minEvidence) {
            return new HistoricalDecision(schema, table, column, "HISTORICAL_INSUFFICIENT_EVIDENCE",
                    renderSignatures(historical), historical.observationCount(), historical.sources());
        }
        return new HistoricalDecision(schema, table, column, "HISTORICAL_CONSENSUS_APPLIED",
                renderSignatures(historical), historical.observationCount(), historical.sources());
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

    private static boolean isExactNumeric(DataType type) {
        return CANONICAL_EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT));
    }

    private static int normalizedScale(Integer scale) {
        return scale == null ? 0 : scale;
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

    private static int blockingCount(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        return (int) issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).count();
    }

    private static String renderSignatures(HistoricalEvidence historical) {
        List<String> values = new ArrayList<>();
        historical.signatures().forEach((signature, sources) ->
                values.add("NUMBER(" + signature.precision() + "," + signature.scale() + ")x" + sources.size()));
        return String.join("|", values);
    }

    private static String normalizedIssueKey(DatabaseSchema schema, String path) {
        return schema.name().normalized() + ":" + safe(path).toUpperCase(Locale.ROOT);
    }

    private static String normalizedColumnKey(String schema, String table, String column) {
        return safe(schema).toUpperCase(Locale.ROOT) + "."
                + safe(table).toUpperCase(Locale.ROOT) + "."
                + safe(column).toUpperCase(Locale.ROOT);
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
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("System property " + property + " must point to a directory: " + path);
        }
        return path;
    }

    private static Path requiredFile(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("System property " + property + " must point to a file: " + path);
        }
        return path;
    }

    private static Path outputDirectory(Path snapshotRoot) throws Exception {
        String configured = trimToNull(System.getProperty(OUTPUT_DIR));
        Path path = configured == null
                ? snapshotRoot.resolveSibling("SchemaForge-MySQL-P2-R4-HistoricalConsensus")
                : Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    private static int positiveInt(String value, String property) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("System property " + property + " must be a positive integer: " + value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) escaped.add(csv(value));
        return String.join(",", escaped);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private record LoadedSnapshot(Path path, String relative, CanonicalSchemaSnapshot snapshot, DatabaseSchema schema) { }
    private record RecoveryAction(String schema, String table, String column) { }
    private record Overlay(DatabaseSchema schema, List<RecoveryAction> actions) { }
    private record HistoricalAction(String schema, String table, String column, DataType canonicalType,
                                    DataType recoveredType, int evidenceCount, List<String> sources) { }
    private record HistoricalDecision(String schema, String table, String column, String classification,
                                      String signatures, int evidenceCount, List<String> sources) { }
    private record HistoricalOverlay(DatabaseSchema schema, List<HistoricalAction> actions,
                                     List<HistoricalDecision> decisions) { }
    private record NumericSignature(int precision, int scale) { }

    private record HistoricalEvidence(Map<NumericSignature, Set<String>> signatures) {
        int observationCount() {
            return signatures.values().stream().mapToInt(Set::size).sum();
        }

        List<String> sources() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            signatures.values().forEach(values::addAll);
            return List.copyOf(values);
        }
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
