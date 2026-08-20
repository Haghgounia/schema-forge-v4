package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.domain.model.Column;
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
 * P2-R5 audit only. Examines remaining P2-R4 MySQL blockers where the exact DB2
 * schema/table/column exists but its datatype conflicts with the canonical datatype.
 * A conflict is classified as a strong recovery candidate only when independent
 * historical canonical observations unanimously map to the exact same MySQL datatype
 * as the DB2 catalog datatype. No production model or canonical snapshot is mutated.
 */
class MySqlCrossSourceConflictAuditIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.crosssource.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.crosssource.db2SysColumnsFile";
    private static final String P2R4_DIR = "schemaforge.mysql.crosssource.p2r4Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.crosssource.outputDir";
    private static final String MIN_EVIDENCE = "schemaforge.mysql.crosssource.minEvidence";

    private static final Set<String> EXACT_NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final MySqlTypeMapper mySqlTypeMapper = new MySqlTypeMapper();

    @Test
    void auditsExactDb2ConflictsAgainstIndependentHistoricalCanonicalEvidence() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path p2r4Root = requiredDirectory(P2R4_DIR);
        Path outputRoot = outputDirectory(p2r4Root);
        int minEvidence = positiveInt(System.getProperty(MIN_EVIDENCE, "1"), MIN_EVIDENCE);

        Path remainingFile = latestFile(p2r4Root, "mysql-historical-consensus-remaining_", ".csv");
        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(metadataFile);

        List<LoadedSnapshot> loaded = loadSnapshots(snapshotRoot);
        Map<String, LoadedSnapshot> byRelativePath = new LinkedHashMap<>();
        for (LoadedSnapshot item : loaded) byRelativePath.put(item.relative(), item);
        Map<String, HistoricalEvidence> history = buildHistoricalEvidence(loaded);

        List<RemainingRow> remainingRows = readRemaining(remainingFile);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Files.createDirectories(outputRoot);
        Path detailsFile = outputRoot.resolve("mysql-cross-source-conflict-details_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-cross-source-conflict-summary_" + timestamp + ".txt");

        List<String> details = new ArrayList<>();
        details.add("snapshot,source,schema,table,column,blocker_code,canonical_type,db2_type,db2_mysql_type,classification,historical_signatures,evidence_count,evidence_sources");

        int candidateOccurrences = 0;
        Set<String> uniqueCandidates = new LinkedHashSet<>();
        Map<String, Integer> classifications = new LinkedHashMap<>();
        Map<String, Integer> db2Types = new LinkedHashMap<>();
        Map<String, Integer> columns = new LinkedHashMap<>();

        for (RemainingRow row : remainingRows) {
            if (!row.code().startsWith("MYSQL_")) continue;
            ColumnRef ref = parseColumnPath(row.path());
            if (ref == null) continue;
            LoadedSnapshot item = byRelativePath.get(row.snapshot());
            if (item == null) continue;
            LocatedColumn located = locate(item.schema(), ref.table(), ref.column());
            if (located == null) continue;

            DataType canonical = located.column().dataType();
            if (!isBlockedExactNumeric(canonical)) continue;
            String schemaName = located.schema();
            String tableName = located.table();
            String columnName = located.column().name().value();
            if (catalog.lookupStatus(schemaName, tableName, columnName)
                    != Db2SysColumnsFileCatalog.LookupStatus.USABLE) continue;

            DataType db2Type = catalog.findType(schemaName, tableName, columnName).orElse(null);
            if (db2Type == null) continue;
            String db2Mysql = tryMap(db2Type);
            if (db2Mysql == null) continue;

            // If DB2 is another exact numeric that is already a straightforward R3 recovery,
            // this is not a cross-family canonical conflict and is outside this audit.
            if (isExactNumeric(db2Type) && canonical.precision() == null) continue;

            String key = normalizedColumnKey(schemaName, tableName, columnName);
            HistoricalEvidence evidence = history.get(key);
            Decision decision = classify(db2Mysql, evidence, minEvidence);

            candidateOccurrences++;
            uniqueCandidates.add(key);
            classifications.merge(decision.classification(), 1, Integer::sum);
            db2Types.merge(renderType(db2Type), 1, Integer::sum);
            columns.merge(columnName.toUpperCase(Locale.ROOT), 1, Integer::sum);

            String source = item.snapshot().source() == null ? "" : safe(item.snapshot().source().relativePath());
            details.add(csvLine(row.snapshot(), source, schemaName, tableName, columnName, row.code(),
                    renderType(canonical), renderType(db2Type), db2Mysql, decision.classification(),
                    decision.signatures(), Integer.toString(decision.evidenceCount()),
                    String.join("|", decision.sources())));
        }

        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL cross-source canonical/DB2 conflict audit");
        summary.add("========================================================");
        summary.add("Snapshot directory       : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file      : " + metadataFile);
        summary.add("P2-R4 remaining file     : " + remainingFile);
        summary.add("Snapshots loaded         : " + loaded.size());
        summary.add("P2-R4 remaining rows     : " + remainingRows.size());
        summary.add("Minimum evidence         : " + minEvidence);
        summary.add("Conflict candidate occ.  : " + candidateOccurrences);
        summary.add("Unique conflict columns  : " + uniqueCandidates.size());
        summary.add("");
        summary.add("Cross-source classifications");
        summary.add("----------------------------");
        if (classifications.isEmpty()) summary.add("None");
        else classifications.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("DB2 types among candidates");
        summary.add("--------------------------");
        db2Types.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> summary.add(entry.getKey() + " : " + entry.getValue()));
        summary.add("");
        summary.add("Top columns among candidates");
        summary.add("----------------------------");
        columns.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .forEach(entry -> summary.add(entry.getKey() + " : " + entry.getValue()));
        summary.add("");
        summary.add("Details: " + detailsFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("P2-R4 remaining rows      : " + remainingRows.size());
        System.out.println("Conflict candidate occ.   : " + candidateOccurrences);
        System.out.println("Unique conflict columns   : " + uniqueCandidates.size());
        classifications.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                   : " + summaryFile);

        assertTrue(candidateOccurrences > 0, "No exact DB2 canonical conflicts were found in P2-R4 remaining blockers");
    }

    private List<LoadedSnapshot> loadSnapshots(Path snapshotRoot) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(snapshotRoot)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalize(snapshotRoot.relativize(path))))
                    .toList();
        }
        List<LoadedSnapshot> result = new ArrayList<>();
        for (Path path : paths) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                result.add(new LoadedSnapshot(normalize(snapshotRoot.relativize(path)), snapshot,
                        mapper.toDomainPersistedSource(snapshot)));
            } catch (RuntimeException ignored) {
                // P2-R4 already established zero read failures for the intended corpus. Keep audit resilient.
            }
        }
        return List.copyOf(result);
    }

    private Map<String, HistoricalEvidence> buildHistoricalEvidence(List<LoadedSnapshot> loaded) {
        Map<String, EvidenceAccumulator> accumulators = new LinkedHashMap<>();
        for (LoadedSnapshot item : loaded) {
            for (Table table : item.schema().tables()) {
                String schemaName = table.qualifiedName().schemaName()
                        .map(identifier -> identifier.value()).orElse(item.schema().name().value());
                String tableName = table.qualifiedName().name().value();
                for (Column column : table.columns()) {
                    String mysql = tryMap(column.dataType());
                    if (mysql == null) continue;
                    String key = normalizedColumnKey(schemaName, tableName, column.name().value());
                    accumulators.computeIfAbsent(key, ignored -> new EvidenceAccumulator())
                            .add(mysql, item.relative());
                }
            }
        }
        Map<String, HistoricalEvidence> result = new LinkedHashMap<>();
        accumulators.forEach((key, accumulator) -> result.put(key, accumulator.build()));
        return Map.copyOf(result);
    }

    private Decision classify(String db2Mysql, HistoricalEvidence historical, int minEvidence) {
        if (historical == null || historical.observationCount() == 0) {
            return new Decision("CROSS_SOURCE_NO_HISTORICAL_EVIDENCE", "", 0, List.of());
        }
        Set<String> signatures = historical.signatures().keySet();
        String rendered = renderSignatures(historical);
        if (historical.observationCount() < minEvidence) {
            return new Decision("CROSS_SOURCE_INSUFFICIENT_EVIDENCE", rendered,
                    historical.observationCount(), historical.sources());
        }
        if (signatures.size() == 1 && signatures.contains(db2Mysql)) {
            return new Decision("CROSS_SOURCE_EXACT_CONSENSUS", rendered,
                    historical.observationCount(), historical.sources());
        }
        String db2Family = mysqlFamily(db2Mysql);
        boolean allSameFamily = signatures.stream().allMatch(signature -> mysqlFamily(signature).equals(db2Family));
        if (allSameFamily) {
            return new Decision("CROSS_SOURCE_SAME_FAMILY_DIFFERENT_DETAILS", rendered,
                    historical.observationCount(), historical.sources());
        }
        return new Decision("CROSS_SOURCE_CONFLICT", rendered,
                historical.observationCount(), historical.sources());
    }

    private static List<RemainingRow> readRemaining(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        int snapshot = requiredIndex(index, "snapshot");
        int code = requiredIndex(index, "code");
        int path = requiredIndex(index, "path");
        List<RemainingRow> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> cells = parseCsvLine(lines.get(i));
            result.add(new RemainingRow(value(cells, snapshot), value(cells, code), value(cells, path)));
        }
        return List.copyOf(result);
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
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                result.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        result.add(cell.toString());
        return result;
    }

    private LocatedColumn locate(DatabaseSchema schema, String tableName, String columnName) {
        for (Table table : schema.tables()) {
            if (!table.qualifiedName().name().normalized().equalsIgnoreCase(tableName)) continue;
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            for (Column column : table.columns()) {
                if (column.name().normalized().equalsIgnoreCase(columnName)) {
                    return new LocatedColumn(schemaName, table.qualifiedName().name().value(), column);
                }
            }
        }
        return null;
    }

    private static ColumnRef parseColumnPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        if (parts.length != 4 || !parts[0].equalsIgnoreCase("tables") || !parts[2].equalsIgnoreCase("columns")) {
            return null;
        }
        return new ColumnRef(parts[1], parts[3]);
    }

    private String tryMap(DataType type) {
        try {
            return mySqlTypeMapper.map(type).toUpperCase(Locale.ROOT);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static boolean isBlockedExactNumeric(DataType type) {
        if (!isExactNumeric(type)) return false;
        if (type.precision() == null) return true;
        int scale = type.scale() == null ? 0 : type.scale();
        return type.precision() > MySqlTypeMapper.MAX_DECIMAL_PRECISION
                || scale > MySqlTypeMapper.MAX_DECIMAL_SCALE;
    }

    private static boolean isExactNumeric(DataType type) {
        return EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT));
    }

    private static String mysqlFamily(String mapped) {
        int paren = mapped.indexOf('(');
        return (paren < 0 ? mapped : mapped.substring(0, paren)).trim().toUpperCase(Locale.ROOT);
    }

    private static String renderSignatures(HistoricalEvidence evidence) {
        List<String> parts = new ArrayList<>();
        evidence.signatures().forEach((signature, sources) -> parts.add(signature + "x" + sources.size()));
        return String.join("|", parts);
    }

    private static String renderType(DataType type) {
        String name = type.name().value();
        if (type.length() != null) return name + "(" + type.length() + ")";
        if (type.precision() != null) {
            return type.scale() == null ? name + "(" + type.precision() + ")"
                    : name + "(" + type.precision() + "," + type.scale() + ")";
        }
        return name;
    }

    private static String normalizedColumnKey(String schema, String table, String column) {
        return safe(schema).toUpperCase(Locale.ROOT) + "." + safe(table).toUpperCase(Locale.ROOT)
                + "." + safe(column).toUpperCase(Locale.ROOT);
    }

    private static Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Directory does not exist: " + path);
        return path;
    }

    private static Path requiredFile(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path p2r4Root) {
        String value = System.getProperty(OUTPUT_DIR);
        return value == null || value.isBlank()
                ? p2r4Root.resolve("cross-source-audit").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path latestFile(Path root, String prefix, String suffix) throws Exception {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No " + prefix + "*" + suffix + " file found in " + root));
        }
    }

    private static int positiveInt(String value, String property) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("-D" + property + " must be a positive integer: " + value);
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

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private record LoadedSnapshot(String relative, CanonicalSchemaSnapshot snapshot, DatabaseSchema schema) {}
    private record RemainingRow(String snapshot, String code, String path) {}
    private record ColumnRef(String table, String column) {}
    private record LocatedColumn(String schema, String table, Column column) {}
    private record Decision(String classification, String signatures, int evidenceCount, List<String> sources) {}

    private record HistoricalEvidence(Map<String, List<String>> signatures, int observationCount, List<String> sources) {}

    private static final class EvidenceAccumulator {
        private final Map<String, LinkedHashSet<String>> sourcesBySignature = new LinkedHashMap<>();

        void add(String signature, String source) {
            sourcesBySignature.computeIfAbsent(signature, ignored -> new LinkedHashSet<>()).add(source);
        }

        HistoricalEvidence build() {
            Map<String, List<String>> signatures = new LinkedHashMap<>();
            LinkedHashSet<String> allSources = new LinkedHashSet<>();
            int count = 0;
            for (Map.Entry<String, LinkedHashSet<String>> entry : sourcesBySignature.entrySet()) {
                List<String> sources = List.copyOf(entry.getValue());
                signatures.put(entry.getKey(), sources);
                allSources.addAll(sources);
                count += sources.size();
            }
            return new HistoricalEvidence(Map.copyOf(signatures), count, List.copyOf(allSources));
        }
    }
}
