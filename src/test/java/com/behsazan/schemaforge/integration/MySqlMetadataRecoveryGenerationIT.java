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
 * P2-R3 evaluation runner: applies exact DB2 catalog evidence as an in-memory MySQL-only
 * datatype overlay, then measures how many previously blocked canonical snapshots become
 * genuinely generatable. Persisted canonical JSON is never changed.
 */
class MySqlMetadataRecoveryGenerationIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.recovery.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.recovery.db2SysColumnsFile";
    private static final String OUTPUT_DIR = "schemaforge.mysql.recovery.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.mysql.recovery.cleanOutput";
    private static final String FAIL_ON_GENERATION_ERRORS = "schemaforge.mysql.recovery.failOnGenerationErrors";

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
    void measuresEvidenceBackedMysqlRecoveryWithoutMutatingCanonicalSnapshots() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path outputRoot = outputDirectory(snapshotRoot);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        boolean failOnGenerationErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_GENERATION_ERRORS, "false"));

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);
        Path generatedRoot = outputRoot.resolve("generated");
        Files.createDirectories(outputRoot);
        if (cleanOutput) cleanDirectory(generatedRoot);
        Files.createDirectories(generatedRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(snapshotRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        List<String> details = new ArrayList<>();
        details.add("snapshot,source,status,baseline_blockers,recovery_applied,remaining_blockers,output_file,error");
        List<String> recoveries = new ArrayList<>();
        recoveries.add("snapshot,source,schema,table,column,canonical_type,metadata_type,mysql_metadata_type");
        List<String> remaining = new ArrayList<>();
        remaining.add("snapshot,source,severity,code,path,message");

        int snapshotReadFailures = 0;
        int baselineBlockedSnapshots = 0;
        int baselineCompatibleSnapshots = 0;
        int snapshotsWithRecoveryApplied = 0;
        int recoveryOccurrences = 0;
        Set<String> uniqueRecoveredColumns = new LinkedHashSet<>();
        int newlyUnblockedSnapshots = 0;
        int stillBlockedSnapshots = 0;
        int generatedSnapshots = 0;
        int generationFailures = 0;
        int remainingBlockerOccurrences = 0;
        Set<String> uniqueRemainingBlockerPaths = new LinkedHashSet<>();
        Map<String, Integer> remainingByCode = new LinkedHashMap<>();
        Map<String, Integer> generationErrors = new LinkedHashMap<>();

        for (Path snapshotPath : snapshots) {
            String relativeSnapshot = normalize(snapshotRoot.relativize(snapshotPath));
            CanonicalSchemaSnapshot snapshot;
            DatabaseSchema original;
            String source = "";
            try {
                snapshot = store.readSnapshot(snapshotPath);
                source = snapshot.source() == null ? "" : safe(snapshot.source().relativePath());
                original = mapper.toDomainPersistedSource(snapshot);
            } catch (Exception exception) {
                snapshotReadFailures++;
                details.add(csvLine(relativeSnapshot, source, "SNAPSHOT_FAILED", "0", "0", "0", "",
                        exception.getClass().getSimpleName() + ": " + safe(exception.getMessage())));
                continue;
            }

            var baselineAssessment = compatibilityAnalyzer.analyze(original, mysqlDialect);
            int baselineBlockers = blockingCount(baselineAssessment.issues());
            boolean baselineBlocked = baselineAssessment.blocking();
            if (baselineBlocked) baselineBlockedSnapshots++; else baselineCompatibleSnapshots++;

            RecoveryOverlay overlay = recoverMissingExactNumericEvidence(original, catalog);
            if (overlay.applied() > 0) {
                snapshotsWithRecoveryApplied++;
                recoveryOccurrences += overlay.applied();
                uniqueRecoveredColumns.addAll(overlay.uniqueColumns());
                for (RecoveryAction action : overlay.actions()) {
                    recoveries.add(csvLine(relativeSnapshot, source, action.schema(), action.table(), action.column(),
                            renderType(action.canonicalType()), renderType(action.metadataType()), action.mysqlMetadataType()));
                }
            }

            var recoveredAssessment = compatibilityAnalyzer.analyze(overlay.schema(), mysqlDialect);
            int remainingBlockers = blockingCount(recoveredAssessment.issues());
            if (recoveredAssessment.blocking()) {
                stillBlockedSnapshots++;
                for (var issue : recoveredAssessment.issues()) {
                    if (!"ERROR".equalsIgnoreCase(issue.severity())) continue;
                    remainingBlockerOccurrences++;
                    remainingByCode.merge(issue.code(), 1, Integer::sum);
                    uniqueRemainingBlockerPaths.add(normalizedIssueKey(overlay.schema(), issue.path()));
                    remaining.add(csvLine(relativeSnapshot, source, issue.severity(), issue.code(),
                            issue.path(), issue.message()));
                }
                details.add(csvLine(relativeSnapshot, source, "STILL_BLOCKED", Integer.toString(baselineBlockers),
                        Integer.toString(overlay.applied()), Integer.toString(remainingBlockers), "", ""));
                continue;
            }

            if (baselineBlocked) newlyUnblockedSnapshots++;
            Path target = generatedTarget(generatedRoot, snapshotRoot.relativize(snapshotPath));
            try {
                PreparedSchema prepared = preparationService.prepare(overlay.schema());
                String sql = new DdlGenerator(mysqlDialect).generate(prepared.schema(), prepared.validationReport());
                Files.createDirectories(target.getParent());
                Files.writeString(target, sql, StandardCharsets.UTF_8);
                generatedSnapshots++;
                details.add(csvLine(relativeSnapshot, source,
                        baselineBlocked ? "RECOVERED_AND_GENERATED" : "GENERATED_BASELINE_COMPATIBLE",
                        Integer.toString(baselineBlockers), Integer.toString(overlay.applied()), "0",
                        normalize(outputRoot.relativize(target)), ""));
            } catch (Exception exception) {
                generationFailures++;
                String key = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
                generationErrors.merge(key, 1, Integer::sum);
                details.add(csvLine(relativeSnapshot, source, "GENERATION_FAILED_AFTER_RECOVERY",
                        Integer.toString(baselineBlockers), Integer.toString(overlay.applied()), "0", "", key));
            }
        }

        Path detailsFile = outputRoot.resolve("mysql-recovery-generation-details_" + timestamp + ".csv");
        Path recoveriesFile = outputRoot.resolve("mysql-recovery-applied_" + timestamp + ".csv");
        Path remainingFile = outputRoot.resolve("mysql-recovery-remaining-blockers_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-recovery-generation-summary_" + timestamp + ".txt");
        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(recoveriesFile, String.join(System.lineSeparator(), recoveries) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(remainingFile, String.join(System.lineSeparator(), remaining) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL metadata recovery generation audit");
        summary.add("=====================================================");
        summary.add("Snapshot directory       : " + snapshotRoot);
        summary.add("Db2 SYSCOLUMNS file      : " + metadataFile);
        summary.add("Snapshots discovered     : " + snapshots.size());
        summary.add("Snapshot read failures   : " + snapshotReadFailures);
        summary.add("Baseline compatible      : " + baselineCompatibleSnapshots);
        summary.add("Baseline blocked         : " + baselineBlockedSnapshots);
        summary.add("Snapshots with recovery  : " + snapshotsWithRecoveryApplied);
        summary.add("Recovery occurrences     : " + recoveryOccurrences);
        summary.add("Unique recovered columns : " + uniqueRecoveredColumns.size());
        summary.add("Newly unblocked snapshots: " + newlyUnblockedSnapshots);
        summary.add("Still blocked snapshots  : " + stillBlockedSnapshots);
        summary.add("Generated after overlay  : " + generatedSnapshots);
        summary.add("Generation failures      : " + generationFailures);
        summary.add("Remaining blocker occ.   : " + remainingBlockerOccurrences);
        summary.add("Unique remaining blockers: " + uniqueRemainingBlockerPaths.size());
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
        summary.add("Details     : " + detailsFile);
        summary.add("Recoveries  : " + recoveriesFile);
        summary.add("Remaining   : " + remainingFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("Snapshots discovered      : " + snapshots.size());
        System.out.println("Baseline compatible       : " + baselineCompatibleSnapshots);
        System.out.println("Baseline blocked          : " + baselineBlockedSnapshots);
        System.out.println("Recovery occurrences      : " + recoveryOccurrences);
        System.out.println("Unique recovered columns  : " + uniqueRecoveredColumns.size());
        System.out.println("Newly unblocked snapshots : " + newlyUnblockedSnapshots);
        System.out.println("Still blocked snapshots   : " + stillBlockedSnapshots);
        System.out.println("Generated after overlay   : " + generatedSnapshots);
        System.out.println("Generation failures       : " + generationFailures);
        remainingByCode.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                    : " + summaryFile);

        assertTrue(baselineBlockedSnapshots > 0, "No baseline MySQL datatype blockers were found");
        assertTrue(recoveryOccurrences > 0, "No evidence-backed datatype recoveries were applied");
        if (failOnGenerationErrors) {
            assertTrue(generationFailures == 0, "Generation failures remain after recovery; see " + detailsFile);
        }
    }

    private RecoveryOverlay recoverMissingExactNumericEvidence(
            DatabaseSchema schema, Db2SysColumnsFileCatalog catalog) {
        DatabaseSchema.Builder schemaBuilder = DatabaseSchema.builder(schema.name().value())
                .description(schema.description().value());
        schema.metadata().forEach(schemaBuilder::metadata);
        schema.sequences().forEach(schemaBuilder::addSequence);

        List<RecoveryAction> actions = new ArrayList<>();
        Set<String> uniqueColumns = new LinkedHashSet<>();

        for (Table table : schema.tables()) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            String tableName = table.qualifiedName().name().value();
            Table.Builder tableBuilder = Table.builder(schemaName, tableName)
                    .persianName(table.persianName().value())
                    .description(table.description().value());
            table.physicalOptions().forEach(tableBuilder::physicalOption);

            for (Column column : table.columns()) {
                DataType canonicalType = column.dataType();
                DataType recoveredType = recoveryType(canonicalType, catalog, schemaName, tableName, column.name().value());
                if (recoveredType != null) {
                    String mysqlMetadataType = mySqlTypeMapper.map(recoveredType);
                    actions.add(new RecoveryAction(schemaName, tableName, column.name().value(),
                            canonicalType, recoveredType, mysqlMetadataType));
                    uniqueColumns.add(normalizedColumnKey(schemaName, tableName, column.name().value()));
                    tableBuilder.addColumn(copyColumn(column, recoveredType));
                } else {
                    tableBuilder.addColumn(column);
                }
            }
            table.primaryKey().ifPresent(tableBuilder::primaryKey);
            table.foreignKeys().forEach(tableBuilder::addForeignKey);
            table.uniqueKeys().forEach(tableBuilder::addUniqueKey);
            table.checkConstraints().forEach(tableBuilder::addCheck);
            table.indexes().forEach(tableBuilder::addIndex);
            schemaBuilder.addTable(tableBuilder.build());
        }
        return new RecoveryOverlay(schemaBuilder.build(), actions.size(), Set.copyOf(uniqueColumns), List.copyOf(actions));
    }

    private DataType recoveryType(
            DataType canonicalType,
            Db2SysColumnsFileCatalog catalog,
            String schema,
            String table,
            String column) {
        String canonicalName = canonicalType.name().normalized().toUpperCase(Locale.ROOT);
        if (!CANONICAL_EXACT_NUMERIC.contains(canonicalName) || canonicalType.precision() != null) return null;
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

    private static Column copyColumn(Column source, DataType dataType) {
        return new Column(source.name(), dataType, source.nullable(), source.defaultValue(), source.description(),
                source.identity(), source.ordinalPosition(), source.generatedExpression(), source.physicalOptions());
    }

    private static int blockingCount(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        return (int) issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.severity())).count();
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
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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

    private static Path outputDirectory(Path snapshotRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? snapshotRoot.resolve("mysql-metadata-recovery-generation").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String csvLine(String... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            cells.add('"' + safe.replace("\"", "\"\"") + '"');
        }
        return String.join(",", cells);
    }

    private record RecoveryAction(
            String schema,
            String table,
            String column,
            DataType canonicalType,
            DataType metadataType,
            String mysqlMetadataType) { }

    private record RecoveryOverlay(
            DatabaseSchema schema,
            int applied,
            Set<String> uniqueColumns,
            List<RecoveryAction> actions) { }
}
