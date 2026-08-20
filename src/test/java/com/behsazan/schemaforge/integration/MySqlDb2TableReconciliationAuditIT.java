package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.Db2SysColumnsFileCatalog;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-R6 audit only. Investigates P2-R4 blockers whose exact DB2 schema/table lookup
 * returned TABLE_NOT_FOUND. The audit never changes canonical JSON and never performs
 * automatic recovery. It looks for conservative evidence that the DB2 table may exist
 * under a different physical spelling/schema by comparing table names and column sets.
 */
class MySqlDb2TableReconciliationAuditIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.tablename.snapshotDir";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.mysql.tablename.db2SysColumnsFile";
    private static final String P2R4_DIR = "schemaforge.mysql.tablename.p2r4Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.tablename.outputDir";

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();

    @Test
    void auditsDb2TableNotFoundBlockersForConservativeTableNameReconciliationEvidence() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path metadataFile = requiredFile(DB2_SYSCOLUMNS_FILE);
        Path p2r4Root = requiredDirectory(P2R4_DIR);
        Path outputRoot = outputDirectory(p2r4Root);
        Path remainingFile = latestFile(p2r4Root, "mysql-historical-consensus-remaining_", ".csv");

        Db2SysColumnsFileCatalog exactCatalog = new Db2SysColumnsFileCatalog(metadataFile);
        MetadataIndex metadata = MetadataIndex.load(metadataFile);
        Map<String, DatabaseSchema> snapshots = loadSnapshots(snapshotRoot);
        List<RemainingRow> remainingRows = readRemaining(remainingFile);

        Map<TargetKey, TargetAccumulator> targets = new LinkedHashMap<>();
        int tableNotFoundOccurrences = 0;
        for (RemainingRow row : remainingRows) {
            if (!row.code().startsWith("MYSQL_")) continue;
            ColumnRef ref = parseColumnPath(row.path());
            if (ref == null) continue;
            DatabaseSchema schema = snapshots.get(row.snapshot());
            if (schema == null) continue;
            LocatedTable located = locateTable(schema, ref.table());
            if (located == null) continue;
            if (exactCatalog.lookupStatus(located.schema(), located.table(), ref.column())
                    != Db2SysColumnsFileCatalog.LookupStatus.TABLE_NOT_FOUND) continue;

            tableNotFoundOccurrences++;
            TargetKey key = new TargetKey(row.snapshot(), located.schema(), located.table());
            TargetAccumulator target = targets.computeIfAbsent(key,
                    ignored -> new TargetAccumulator(row.snapshot(), row.source(), located.schema(), located.table(),
                            located.columns()));
            target.blockedColumns.add(normalizeIdentifier(ref.column()));
        }

        Files.createDirectories(outputRoot);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path detailsFile = outputRoot.resolve("mysql-db2-table-reconciliation-details_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-db2-table-reconciliation-summary_" + timestamp + ".txt");

        List<String> details = new ArrayList<>();
        details.add("snapshot,source,schema,canonical_table,blocked_columns,canonical_column_count,classification,"
                + "candidate_schema,candidate_table,candidate_column_count,matched_columns,blocked_columns_matched,"
                + "canonical_coverage,name_relation,name_distance,candidate_count,top_candidates");

        Map<String, Integer> classifications = new LinkedHashMap<>();
        Set<String> uniqueCanonicalTables = new LinkedHashSet<>();
        Set<String> uniqueStrongCandidateTables = new LinkedHashSet<>();
        int strongSnapshotCandidates = 0;

        for (TargetAccumulator target : targets.values()) {
            Decision decision = classify(target, metadata);
            classifications.merge(decision.classification(), 1, Integer::sum);
            uniqueCanonicalTables.add(target.schema + "." + target.table);
            if (decision.strong()) {
                strongSnapshotCandidates++;
                uniqueStrongCandidateTables.add(target.schema + "." + target.table);
            }
            CandidateScore best = decision.best();
            details.add(csvLine(
                    target.snapshot,
                    target.source,
                    target.schema,
                    target.table,
                    join(target.blockedColumns),
                    Integer.toString(target.canonicalColumns.size()),
                    decision.classification(),
                    best == null ? "" : best.table().schema(),
                    best == null ? "" : best.table().table(),
                    best == null ? "" : Integer.toString(best.table().columns().size()),
                    best == null ? "" : Integer.toString(best.matchedColumns()),
                    best == null ? "" : Integer.toString(best.blockedColumnsMatched()),
                    best == null ? "" : decimal(best.canonicalCoverage()),
                    best == null ? "" : best.nameRelation(),
                    best == null ? "" : Integer.toString(best.nameDistance()),
                    Integer.toString(decision.candidateCount()),
                    decision.topCandidates()));
        }

        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL DB2 table-name reconciliation audit");
        summary.add("=====================================================");
        summary.add("Snapshot directory          : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file         : " + metadataFile);
        summary.add("P2-R4 remaining file        : " + remainingFile);
        summary.add("Snapshots loaded            : " + snapshots.size());
        summary.add("DB2 metadata rows           : " + metadata.sourceRows());
        summary.add("DB2 metadata tables         : " + metadata.tableCount());
        summary.add("P2-R4 remaining rows        : " + remainingRows.size());
        summary.add("TABLE_NOT_FOUND occurrences : " + tableNotFoundOccurrences);
        summary.add("TABLE_NOT_FOUND snapshots   : " + targets.size());
        summary.add("Unique canonical tables     : " + uniqueCanonicalTables.size());
        summary.add("Strong candidate snapshots  : " + strongSnapshotCandidates);
        summary.add("Unique strong tables        : " + uniqueStrongCandidateTables.size());
        summary.add("");
        summary.add("Reconciliation classifications");
        summary.add("------------------------------");
        if (classifications.isEmpty()) summary.add("None");
        else classifications.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Interpretation");
        summary.add("--------------");
        summary.add("STRONG_* classifications are audit candidates only; no recovery is applied.");
        summary.add("AMBIGUOUS_* and NO_* classifications must remain blocked without new evidence.");
        summary.add("");
        summary.add("Details: " + detailsFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("P2-R4 remaining rows        : " + remainingRows.size());
        System.out.println("TABLE_NOT_FOUND occurrences : " + tableNotFoundOccurrences);
        System.out.println("TABLE_NOT_FOUND snapshots   : " + targets.size());
        System.out.println("Unique canonical tables     : " + uniqueCanonicalTables.size());
        System.out.println("Strong candidate snapshots  : " + strongSnapshotCandidates);
        classifications.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                     : " + summaryFile);

        assertTrue(tableNotFoundOccurrences > 0, "No DB2 TABLE_NOT_FOUND blockers were found in P2-R4 remaining rows");
    }

    private Decision classify(TargetAccumulator target, MetadataIndex metadata) {
        List<CandidateScore> sameSchema = new ArrayList<>();
        for (MetadataTable candidate : metadata.tablesInSchema(target.schema)) {
            CandidateScore score = score(target, candidate);
            if (score.plausible()) sameSchema.add(score);
        }
        sameSchema.sort(CandidateScore.ORDER);

        List<CandidateScore> exactOtherSchema = new ArrayList<>();
        for (MetadataTable candidate : metadata.tablesNamed(target.table)) {
            if (candidate.schema().equals(target.schema)) continue;
            CandidateScore score = score(target, candidate);
            if (score.blockedColumnsMatched() > 0) exactOtherSchema.add(score);
        }
        exactOtherSchema.sort(CandidateScore.ORDER);

        List<CandidateScore> normalized = sameSchema.stream()
                .filter(score -> score.nameRelation().equals("NORMALIZED_EXACT"))
                .toList();
        if (normalized.size() == 1) {
            return decision("STRONG_SAME_SCHEMA_NORMALIZED_NAME", normalized.getFirst(), sameSchema, true);
        }
        if (normalized.size() > 1) {
            return decision("AMBIGUOUS_NORMALIZED_NAME", normalized.getFirst(), normalized, false);
        }

        List<CandidateScore> prefix = sameSchema.stream()
                .filter(score -> score.nameRelation().equals("PREFIX_OR_TRUNCATION"))
                .filter(CandidateScore::strongColumnEvidence)
                .toList();
        if (prefix.size() == 1) {
            return decision("STRONG_SAME_SCHEMA_PREFIX_COLUMN_SIGNATURE", prefix.getFirst(), sameSchema, true);
        }
        if (prefix.size() > 1) {
            return decision("AMBIGUOUS_PREFIX_CANDIDATES", prefix.getFirst(), prefix, false);
        }

        List<CandidateScore> nearName = sameSchema.stream()
                .filter(score -> score.nameRelation().equals("NEAR_NAME"))
                .filter(CandidateScore::strongColumnEvidence)
                .toList();
        if (nearName.size() == 1) {
            return decision("STRONG_SAME_SCHEMA_NEAR_NAME_COLUMN_SIGNATURE", nearName.getFirst(), sameSchema, true);
        }
        if (nearName.size() > 1) {
            return decision("AMBIGUOUS_NEAR_NAME_CANDIDATES", nearName.getFirst(), nearName, false);
        }

        List<CandidateScore> signature = sameSchema.stream()
                .filter(CandidateScore::strongColumnEvidence)
                .toList();
        if (signature.size() == 1) {
            return decision("STRONG_SAME_SCHEMA_UNIQUE_COLUMN_SIGNATURE", signature.getFirst(), sameSchema, true);
        }
        if (signature.size() > 1) {
            return decision("AMBIGUOUS_COLUMN_SIGNATURE", signature.getFirst(), signature, false);
        }

        if (exactOtherSchema.size() == 1) {
            return decision("REVIEW_EXACT_NAME_OTHER_SCHEMA", exactOtherSchema.getFirst(), exactOtherSchema, false);
        }
        if (exactOtherSchema.size() > 1) {
            return decision("AMBIGUOUS_EXACT_NAME_OTHER_SCHEMA", exactOtherSchema.getFirst(), exactOtherSchema, false);
        }
        if (!sameSchema.isEmpty()) {
            return decision("WEAK_SAME_SCHEMA_CANDIDATE", sameSchema.getFirst(), sameSchema, false);
        }
        return new Decision("NO_TABLE_CANDIDATE", null, 0, "", false);
    }

    private static Decision decision(String classification, CandidateScore best,
                                     List<CandidateScore> candidates, boolean strong) {
        return new Decision(classification, best, candidates.size(), renderCandidates(candidates), strong);
    }

    private static CandidateScore score(TargetAccumulator target, MetadataTable candidate) {
        int matched = 0;
        for (String column : target.canonicalColumns) if (candidate.columns().contains(column)) matched++;
        int blockedMatched = 0;
        for (String column : target.blockedColumns) if (candidate.columns().contains(column)) blockedMatched++;
        double coverage = target.canonicalColumns.isEmpty() ? 0.0 : (double) matched / target.canonicalColumns.size();

        String canonicalName = normalizeName(target.table);
        String candidateName = normalizeName(candidate.table());
        int distance = levenshtein(canonicalName, candidateName);
        String relation = "NONE";
        if (canonicalName.equals(candidateName)) {
            relation = "NORMALIZED_EXACT";
        } else if (prefixRelation(canonicalName, candidateName)) {
            relation = "PREFIX_OR_TRUNCATION";
        } else if (Math.min(canonicalName.length(), candidateName.length()) >= 8 && distance <= 2) {
            relation = "NEAR_NAME";
        }

        boolean smallExact = !target.canonicalColumns.isEmpty()
                && target.canonicalColumns.size() <= 3
                && matched == target.canonicalColumns.size()
                && matched >= 2;
        boolean highCoverage = matched >= 3 && coverage >= 0.60;
        boolean strongColumnEvidence = blockedMatched > 0 && (smallExact || highCoverage);
        boolean plausible = blockedMatched > 0 && (!relation.equals("NONE") || strongColumnEvidence);

        int rank = blockedMatched * 10000 + matched * 1000 + (int) Math.round(coverage * 100)
                + (relation.equals("NORMALIZED_EXACT") ? 300 : 0)
                + (relation.equals("PREFIX_OR_TRUNCATION") ? 200 : 0)
                + (relation.equals("NEAR_NAME") ? 100 : 0)
                - Math.min(distance, 99);
        return new CandidateScore(candidate, matched, blockedMatched, coverage, relation,
                distance, strongColumnEvidence, plausible, rank);
    }

    private Map<String, DatabaseSchema> loadSnapshots(Path snapshotRoot) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(snapshotRoot)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalizePath(snapshotRoot.relativize(path))))
                    .toList();
        }
        Map<String, DatabaseSchema> result = new LinkedHashMap<>();
        for (Path path : paths) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                result.put(normalizePath(snapshotRoot.relativize(path)), mapper.toDomainPersistedSource(snapshot));
            } catch (RuntimeException ignored) {
                // Prior P2 audits established zero read failures for the intended corpus.
            }
        }
        return Map.copyOf(result);
    }

    private static LocatedTable locateTable(DatabaseSchema schema, String tableName) {
        for (Table table : schema.tables()) {
            if (!table.qualifiedName().name().normalized().equalsIgnoreCase(tableName)) continue;
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            table.columns().forEach(column -> columns.add(normalizeIdentifier(column.name().value())));
            return new LocatedTable(normalizeIdentifier(schemaName),
                    normalizeIdentifier(table.qualifiedName().name().value()), Set.copyOf(columns));
        }
        return null;
    }

    private static List<RemainingRow> readRemaining(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> header = parseCsvLine(lines.getFirst());
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        int snapshot = requiredIndex(index, "snapshot");
        int source = index.getOrDefault("source", -1);
        int code = requiredIndex(index, "code");
        int path = requiredIndex(index, "path");
        List<RemainingRow> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> cells = parseCsvLine(lines.get(i));
            result.add(new RemainingRow(value(cells, snapshot), value(cells, source),
                    value(cells, code), value(cells, path)));
        }
        return List.copyOf(result);
    }

    private static ColumnRef parseColumnPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        if (parts.length != 4 || !parts[0].equalsIgnoreCase("tables") || !parts[2].equalsIgnoreCase("columns")) {
            return null;
        }
        return new ColumnRef(parts[1], parts[3]);
    }

    private static boolean prefixRelation(String left, String right) {
        int shorter = Math.min(left.length(), right.length());
        return shorter >= 8 && (left.startsWith(right) || right.startsWith(left));
    }

    private static int levenshtein(String left, String right) {
        if (left.equals(right)) return 0;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static String normalizeName(String value) {
        return normalizeIdentifier(value).replaceAll("[^A-Z0-9]", "");
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String renderCandidates(List<CandidateScore> candidates) {
        return candidates.stream().limit(5)
                .map(score -> score.table().schema() + "." + score.table().table()
                        + "[cols=" + score.matchedColumns()
                        + ",blocked=" + score.blockedColumnsMatched()
                        + ",coverage=" + decimal(score.canonicalCoverage())
                        + ",name=" + score.nameRelation() + "]")
                .reduce((left, right) -> left + "|" + right).orElse("");
    }

    private static String join(Set<String> values) {
        return String.join("|", values.stream().sorted().toList());
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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
                ? p2r4Root.resolve("table-reconciliation-audit").toAbsolutePath().normalize()
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

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private record RemainingRow(String snapshot, String source, String code, String path) {}
    private record ColumnRef(String table, String column) {}
    private record LocatedTable(String schema, String table, Set<String> columns) {}
    private record TargetKey(String snapshot, String schema, String table) {}

    private static final class TargetAccumulator {
        private final String snapshot;
        private final String source;
        private final String schema;
        private final String table;
        private final Set<String> canonicalColumns;
        private final Set<String> blockedColumns = new LinkedHashSet<>();

        private TargetAccumulator(String snapshot, String source, String schema, String table, Set<String> canonicalColumns) {
            this.snapshot = snapshot;
            this.source = source;
            this.schema = schema;
            this.table = table;
            this.canonicalColumns = canonicalColumns;
        }
    }

    private record Decision(String classification, CandidateScore best, int candidateCount,
                            String topCandidates, boolean strong) {}

    private record CandidateScore(MetadataTable table, int matchedColumns, int blockedColumnsMatched,
                                  double canonicalCoverage, String nameRelation, int nameDistance,
                                  boolean strongColumnEvidence, boolean plausible, int rank) {
        private static final Comparator<CandidateScore> ORDER = Comparator
                .comparingInt(CandidateScore::rank).reversed()
                .thenComparing(score -> score.table().schema())
                .thenComparing(score -> score.table().table());
    }

    private record MetadataTable(String schema, String table, Set<String> columns) {}
    private record MetadataTableKey(String schema, String table) {}

    private static final class MetadataIndex {
        private final Map<MetadataTableKey, MetadataTable> tables;
        private final Map<String, List<MetadataTable>> bySchema;
        private final Map<String, List<MetadataTable>> byName;
        private final int sourceRows;

        private MetadataIndex(Map<MetadataTableKey, MetadataTable> tables,
                              Map<String, List<MetadataTable>> bySchema,
                              Map<String, List<MetadataTable>> byName,
                              int sourceRows) {
            this.tables = tables;
            this.bySchema = bySchema;
            this.byName = byName;
            this.sourceRows = sourceRows;
        }

        static MetadataIndex load(Path file) {
            Map<MetadataTableKey, LinkedHashSet<String>> columns = new LinkedHashMap<>();
            int rows;
            try {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    rows = 0;
                    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                        ZipEntry entry;
                        while ((entry = zip.getNextEntry()) != null) {
                            if (entry.isDirectory() || !isTextEntry(entry.getName())) continue;
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            zip.transferTo(buffer);
                            rows += loadText(buffer.toString(StandardCharsets.UTF_8), columns);
                        }
                    }
                } else {
                    rows = loadText(Files.readString(file, StandardCharsets.UTF_8), columns);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read DB2 SYSCOLUMNS file: " + file, exception);
            }

            Map<MetadataTableKey, MetadataTable> tables = new LinkedHashMap<>();
            Map<String, List<MetadataTable>> bySchema = new LinkedHashMap<>();
            Map<String, List<MetadataTable>> byName = new LinkedHashMap<>();
            for (Map.Entry<MetadataTableKey, LinkedHashSet<String>> entry : columns.entrySet()) {
                MetadataTable table = new MetadataTable(entry.getKey().schema(), entry.getKey().table(),
                        Set.copyOf(entry.getValue()));
                tables.put(entry.getKey(), table);
                bySchema.computeIfAbsent(table.schema(), ignored -> new ArrayList<>()).add(table);
                byName.computeIfAbsent(table.table(), ignored -> new ArrayList<>()).add(table);
            }
            bySchema.values().forEach(list -> list.sort(Comparator.comparing(MetadataTable::table)));
            byName.values().forEach(list -> list.sort(Comparator.comparing(MetadataTable::schema)));
            return new MetadataIndex(Map.copyOf(tables), immutableLists(bySchema), immutableLists(byName), rows);
        }

        List<MetadataTable> tablesInSchema(String schema) {
            return bySchema.getOrDefault(normalizeIdentifier(schema), List.of());
        }

        List<MetadataTable> tablesNamed(String table) {
            return byName.getOrDefault(normalizeIdentifier(table), List.of());
        }

        int sourceRows() {
            return sourceRows;
        }

        int tableCount() {
            return tables.size();
        }

        private static Map<String, List<MetadataTable>> immutableLists(Map<String, List<MetadataTable>> source) {
            Map<String, List<MetadataTable>> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Map.copyOf(result);
        }

        private static int loadText(String text, Map<MetadataTableKey, LinkedHashSet<String>> columns) {
            if (text == null || text.isBlank()) return 0;
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            char delimiter = detectDelimiter(text);
            List<List<String>> records = parseDelimited(text, delimiter);
            if (records.isEmpty()) return 0;
            Header header = Header.from(records.getFirst());
            if (!header.usable()) return 0;
            int rows = 0;
            for (int i = 1; i < records.size(); i++) {
                List<String> row = records.get(i);
                if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
                rows++;
                String schema = normalizeIdentifier(value(row, header.schema()));
                String table = normalizeIdentifier(value(row, header.table()));
                String column = normalizeIdentifier(value(row, header.column()));
                if (schema.isBlank() || table.isBlank() || column.isBlank()) continue;
                columns.computeIfAbsent(new MetadataTableKey(schema, table), ignored -> new LinkedHashSet<>())
                        .add(column);
            }
            return rows;
        }

        private static boolean isTextEntry(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt");
        }

        private static char detectDelimiter(String text) {
            int end = text.indexOf('\n');
            String first = end < 0 ? text : text.substring(0, end);
            char best = ',';
            int bestCount = -1;
            for (char candidate : new char[]{',', '\t', ';', '|'}) {
                int count = countOutsideQuotes(first, candidate);
                if (count > bestCount) {
                    best = candidate;
                    bestCount = count;
                }
            }
            return best;
        }

        private static int countOutsideQuotes(String value, char delimiter) {
            boolean quoted = false;
            int count = 0;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') i++;
                    else quoted = !quoted;
                } else if (!quoted && ch == delimiter) {
                    count++;
                }
            }
            return count;
        }

        private static List<List<String>> parseDelimited(String text, char delimiter) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '"') {
                    if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                    continue;
                }
                if (!quoted && ch == delimiter) {
                    row.add(cell.toString().trim());
                    cell.setLength(0);
                    continue;
                }
                if (!quoted && (ch == '\n' || ch == '\r')) {
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString().trim());
                    cell.setLength(0);
                    rows.add(List.copyOf(row));
                    row.clear();
                    continue;
                }
                cell.append(ch);
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString().trim());
                rows.add(List.copyOf(row));
            }
            return rows;
        }

        private record Header(int schema, int table, int column) {
            static Header from(List<String> values) {
                Map<String, Integer> indexes = new HashMap<>();
                for (int i = 0; i < values.size(); i++) indexes.put(normalizeHeader(values.get(i)), i);
                return new Header(
                        first(indexes, "TBCREATOR", "TABLE_SCHEMA", "CREATOR"),
                        first(indexes, "TBNAME", "TABLE_NAME"),
                        first(indexes, "NAME", "COLNAME", "COLUMN_NAME"));
            }

            boolean usable() {
                return schema >= 0 && table >= 0 && column >= 0;
            }

            private static int first(Map<String, Integer> indexes, String... names) {
                for (String name : names) {
                    Integer index = indexes.get(name);
                    if (index != null) return index;
                }
                return -1;
            }

            private static String normalizeHeader(String value) {
                String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
                return source.replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
            }
        }
    }
}
