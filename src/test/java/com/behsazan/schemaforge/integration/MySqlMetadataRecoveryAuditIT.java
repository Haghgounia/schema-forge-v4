package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-only MySQL datatype recovery audit against an offline SYSIBM.SYSCOLUMNS export.
 *
 * <p>This test never mutates canonical snapshots and never changes a datatype. It measures which
 * MySQL datatype blockers have exact external metadata evidence that could support a later,
 * explicitly reviewed canonical recovery step.</p>
 */
class MySqlMetadataRecoveryAuditIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.recovery.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.recovery.db2SysColumnsFile";
    private static final String OUTPUT_DIR = "schemaforge.mysql.recovery.outputDir";
    private static final String FAIL_ON_UNRESOLVED = "schemaforge.mysql.recovery.failOnUnresolved";

    private static final Set<String> EXACT_NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
    private static final Set<String> EXACT_METADATA_NUMERIC = Set.of(
            "DECIMAL", "NUMERIC", "SMALLINT", "INTEGER", "BIGINT", "TINYINT");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final MySqlTypeMapper mySqlTypeMapper = new MySqlTypeMapper();

    @Test
    void auditsBlockedMySqlDatatypesAgainstDb2SysColumnsEvidence() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path outputRoot = outputDirectory(snapshotRoot);
        boolean failOnUnresolved = Boolean.parseBoolean(System.getProperty(FAIL_ON_UNRESOLVED, "false"));

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);
        Files.createDirectories(outputRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(snapshotRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        List<String> detail = new ArrayList<>();
        detail.add("snapshot,source,parser_id,schema,table,column,canonical_type,issue_code,metadata_status,metadata_type,mysql_metadata_type,classification,detail");

        Map<String, Integer> classificationCounts = new LinkedHashMap<>();
        Map<String, Integer> issueCounts = new LinkedHashMap<>();
        Set<String> uniqueBlockedColumns = new LinkedHashSet<>();
        Set<String> uniqueRecoverableColumns = new LinkedHashSet<>();
        int snapshotFailures = 0;
        int snapshotsWithBlockers = 0;
        int blockerOccurrences = 0;
        int recoverableOccurrences = 0;

        for (Path snapshotPath : snapshots) {
            String relativeSnapshot = normalize(snapshotRoot.relativize(snapshotPath));
            CanonicalSchemaSnapshot snapshot;
            DatabaseSchema schema;
            try {
                snapshot = store.readSnapshot(snapshotPath);
                schema = mapper.toDomainPersistedSource(snapshot);
            } catch (Exception exception) {
                snapshotFailures++;
                continue;
            }

            String source = snapshot.source() == null ? "" : safe(snapshot.source().relativePath());
            String parserId = snapshot.source() == null ? "" : safe(snapshot.source().parserId());
            boolean snapshotBlocked = false;

            for (Table table : schema.tables()) {
                String schemaName = table.qualifiedName().schemaName()
                        .map(identifier -> identifier.value()).orElse(schema.name().value());
                String tableName = table.qualifiedName().name().value();

                for (var column : table.columns()) {
                    DataType canonicalType = column.dataType();
                    Blocker blocker = blocker(canonicalType);
                    if (blocker == null) continue;

                    snapshotBlocked = true;
                    blockerOccurrences++;
                    issueCounts.merge(blocker.code(), 1, Integer::sum);
                    String columnName = column.name().value();
                    String uniqueKey = normalizedKey(schemaName, tableName, columnName);
                    uniqueBlockedColumns.add(uniqueKey);

                    Db2SysColumnsFileCatalog.LookupStatus status =
                            catalog.lookupStatus(schemaName, tableName, columnName);
                    DataType metadataType = status == Db2SysColumnsFileCatalog.LookupStatus.USABLE
                            ? catalog.findType(schemaName, tableName, columnName).orElse(null)
                            : null;
                    RecoveryDecision decision = classify(blocker, canonicalType, metadataType, status);

                    classificationCounts.merge(decision.classification(), 1, Integer::sum);
                    if (decision.recoverable()) {
                        recoverableOccurrences++;
                        uniqueRecoverableColumns.add(uniqueKey);
                    }

                    detail.add(csvLine(
                            relativeSnapshot,
                            source,
                            parserId,
                            schemaName,
                            tableName,
                            columnName,
                            renderType(canonicalType),
                            blocker.code(),
                            status.name(),
                            renderType(metadataType),
                            decision.mysqlMetadataType(),
                            decision.classification(),
                            decision.detail()));
                }
            }
            if (snapshotBlocked) snapshotsWithBlockers++;
        }

        Path detailFile = outputRoot.resolve("mysql-metadata-recovery-details_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-metadata-recovery-summary_" + timestamp + ".txt");
        Files.writeString(detailFile, String.join(System.lineSeparator(), detail) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL metadata recovery audit");
        summary.add("=========================================");
        summary.add("Snapshot directory    : " + snapshotRoot);
        summary.add("Db2 SYSCOLUMNS file   : " + metadataFile);
        summary.add("Metadata source rows  : " + catalog.sourceRows());
        summary.add("Metadata usable cols  : " + catalog.usableColumns());
        summary.add("Metadata ambiguous    : " + catalog.ambiguousColumns());
        summary.add("Snapshots discovered  : " + snapshots.size());
        summary.add("Snapshot read failures: " + snapshotFailures);
        summary.add("Snapshots with blockers: " + snapshotsWithBlockers);
        summary.add("Blocker occurrences   : " + blockerOccurrences);
        summary.add("Unique blocked columns: " + uniqueBlockedColumns.size());
        summary.add("Recoverable occurrences: " + recoverableOccurrences);
        summary.add("Unique recoverable cols: " + uniqueRecoverableColumns.size());
        summary.add("");
        summary.add("By blocker code");
        summary.add("---------------");
        issueCounts.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("By recovery classification");
        summary.add("--------------------------");
        classificationCounts.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Details: " + detailFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("Snapshots discovered   : " + snapshots.size());
        System.out.println("Snapshots with blockers: " + snapshotsWithBlockers);
        System.out.println("Blocker occurrences    : " + blockerOccurrences);
        System.out.println("Unique blocked columns : " + uniqueBlockedColumns.size());
        System.out.println("Recoverable occurrences: " + recoverableOccurrences);
        System.out.println("Unique recoverable cols: " + uniqueRecoverableColumns.size());
        classificationCounts.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                 : " + summaryFile);
        System.out.println("Details                 : " + detailFile);

        assertTrue(blockerOccurrences > 0, "No MySQL datatype blockers were found in the snapshot corpus");
        if (failOnUnresolved) {
            assertTrue(recoverableOccurrences == blockerOccurrences,
                    "Some MySQL datatype blockers remain unresolved; see " + detailFile);
        }
    }

    private RecoveryDecision classify(
            Blocker blocker,
            DataType canonicalType,
            DataType metadataType,
            Db2SysColumnsFileCatalog.LookupStatus status) {
        if (status != Db2SysColumnsFileCatalog.LookupStatus.USABLE || metadataType == null) {
            return new RecoveryDecision(false, "METADATA_" + status.name(), "",
                    "No exact usable schema/table/column metadata evidence is available.");
        }

        if (blocker.code().equals("MYSQL_ROWID_UNSUPPORTED")) {
            return new RecoveryDecision(false, "ROWID_PHYSICAL_ARTIFACT_REVIEW", "",
                    "Exact metadata type is " + renderType(metadataType)
                            + "; ROWID semantics require explicit physical-artifact handling and are not converted automatically.");
        }

        String mysqlMetadataType;
        try {
            mysqlMetadataType = mySqlTypeMapper.map(metadataType);
        } catch (RuntimeException exception) {
            return new RecoveryDecision(false, "METADATA_STILL_UNSUPPORTED", "",
                    "Exact metadata type " + renderType(metadataType)
                            + " is itself not losslessly mappable to MySQL: " + safe(exception.getMessage()));
        }

        String canonicalName = normalizedTypeName(canonicalType);
        String metadataName = normalizedTypeName(metadataType);

        if (blocker.code().equals("MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED")) {
            if (EXACT_NUMERIC.contains(canonicalName) && exactMetadataNumeric(metadataName)) {
                return new RecoveryDecision(true, "RECOVERABLE_EXACT_NUMERIC_METADATA", mysqlMetadataType,
                        "Exact DB2 catalog evidence supplies a fixed numeric type without inventing precision/scale.");
            }
            return new RecoveryDecision(false, "CANONICAL_METADATA_TYPE_CONFLICT", mysqlMetadataType,
                    "Canonical exact numeric type conflicts with exact metadata type "
                            + renderType(metadataType) + "; review is required before changing the canonical model.");
        }

        if (blocker.code().equals("MYSQL_DECIMAL_PRECISION_UNSUPPORTED")) {
            if (exactMetadataNumeric(metadataName)) {
                return new RecoveryDecision(false, "RECOVERABLE_WITH_CANONICAL_CONFLICT", mysqlMetadataType,
                        "Metadata provides a MySQL-compatible exact numeric type, but canonical precision is explicitly "
                                + canonicalType.precision() + "; resolve the source/metadata conflict before recovery.");
            }
            return new RecoveryDecision(false, "CANONICAL_METADATA_TYPE_CONFLICT", mysqlMetadataType,
                    "Canonical over-precision numeric type conflicts with metadata type "
                            + renderType(metadataType) + "; review is required.");
        }

        return new RecoveryDecision(false, "METADATA_EVIDENCE_REVIEW", mysqlMetadataType,
                "Exact metadata exists, but this blocker has no automatic recovery policy.");
    }

    private Blocker blocker(DataType type) {
        String name = normalizedTypeName(type);
        if (EXACT_NUMERIC.contains(name)) {
            if (type.precision() == null) {
                return new Blocker("MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED");
            }
            if (type.precision() > MySqlTypeMapper.MAX_DECIMAL_PRECISION) {
                return new Blocker("MYSQL_DECIMAL_PRECISION_UNSUPPORTED");
            }
            int scale = type.scale() == null ? 0 : type.scale();
            if (scale < 0 || scale > MySqlTypeMapper.MAX_DECIMAL_SCALE || scale > type.precision()) {
                return new Blocker("MYSQL_DECIMAL_SCALE_UNSUPPORTED");
            }
        }
        if (name.equals("ROWID") || name.equals("UROWID")) {
            return new Blocker("MYSQL_ROWID_UNSUPPORTED");
        }
        try {
            mySqlTypeMapper.map(type);
            return null;
        } catch (RuntimeException exception) {
            return new Blocker("MYSQL_DATATYPE_UNSUPPORTED");
        }
    }

    private static boolean exactMetadataNumeric(String name) {
        return EXACT_METADATA_NUMERIC.contains(name);
    }

    private static String normalizedTypeName(DataType type) {
        return type == null ? "" : type.name().normalized().toUpperCase(Locale.ROOT);
    }

    private static String renderType(DataType type) {
        if (type == null) return "";
        String name = type.name().value();
        if (type.length() != null) return name + "(" + type.length() + ")";
        if (type.precision() != null) {
            return name + "(" + type.precision()
                    + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return name;
    }

    private static String normalizedKey(String schema, String table, String column) {
        return safe(schema).toUpperCase(Locale.ROOT) + "."
                + safe(table).toUpperCase(Locale.ROOT) + "."
                + safe(column).toUpperCase(Locale.ROOT);
    }

    private static Path requiredDirectory(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("System property " + property
                    + " must point to an existing directory: " + path);
        }
        return path;
    }

    private static Path requiredFile(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("System property " + property
                    + " must point to an existing file: " + path);
        }
        return path;
    }

    private static Path outputDirectory(Path snapshotRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? snapshotRoot.resolve("mysql-metadata-recovery-audit").toAbsolutePath().normalize()
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

    private record Blocker(String code) { }

    private record RecoveryDecision(
            boolean recoverable,
            String classification,
            String mysqlMetadataType,
            String detail) { }
}
