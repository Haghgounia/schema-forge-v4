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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-R9 evidence-only audit for the post-R8 MySQL blocker set.
 *
 * <p>The runner first reconstructs the exact projected remaining snapshot set after P2-R7 and P2-R8.
 * It then isolates residual {@code METADATA_COLUMN_NOT_FOUND} cases and searches only the exact DB2
 * schema/table for conservative column-name candidates. No recovery is applied and persisted canonical
 * JSON is never modified. Exact normalized-name matches are reported separately from prefix/edit-distance
 * review candidates; ambiguous matches remain blocked.</p>
 */
class MySqlRemainingColumnReconciliationAuditIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.columnfix.snapshotDir";
    private static final String DB2_FILE = "schemaforge.mysql.columnfix.db2SysColumnsFile";
    private static final String P2R2_DIR = "schemaforge.mysql.columnfix.p2r2Dir";
    private static final String P2R4_DIR = "schemaforge.mysql.columnfix.p2r4Dir";
    private static final String P2R7_DIR = "schemaforge.mysql.columnfix.p2r7Dir";
    private static final String P2R8_DIR = "schemaforge.mysql.columnfix.p2r8Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.columnfix.outputDir";

    private static final Set<String> EXACT_NUMERIC = Set.of(
            "DECIMAL", "NUMERIC", "SMALLINT", "INTEGER", "BIGINT", "TINYINT");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final MySqlTypeMapper mySqlTypeMapper = new MySqlTypeMapper();

    @Test
    void auditsResidualColumnNotFoundCandidatesWithoutApplyingRecovery() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path db2File = requiredFile(DB2_FILE);
        Path p2r2Root = requiredDirectory(P2R2_DIR);
        Path p2r4Root = requiredDirectory(P2R4_DIR);
        Path p2r7Root = requiredDirectory(P2R7_DIR);
        Path p2r8Root = requiredDirectory(P2R8_DIR);
        Path outputRoot = outputDirectory();

        Path p2r2Details = latestFile(p2r2Root, "mysql-metadata-recovery-details_", ".csv");
        Path p2r4Remaining = latestFile(p2r4Root, "mysql-historical-consensus-remaining_", ".csv");
        Path p2r7Details = latestFile(p2r7Root, "mysql-strong-table-reconciliation-details_", ".csv");
        Path p2r8Details = latestFile(p2r8Root, "mysql-cross-schema-reconciliation-details_", ".csv");

        List<Map<String, String>> r2Rows = readCsv(p2r2Details);
        List<Map<String, String>> r4Rows = readCsv(p2r4Remaining);
        Set<String> p2r4BlockedSnapshots = uniqueValues(r4Rows, "snapshot");
        Set<String> generatedR7 = snapshotsWithDecision(readCsv(p2r7Details), "CONFIRMED_AND_GENERATED");
        Set<String> generatedR8 = snapshotsWithDecision(readCsv(p2r8Details), "CONFIRMED_AND_GENERATED");

        Set<String> residualSnapshots = new LinkedHashSet<>(p2r4BlockedSnapshots);
        residualSnapshots.removeAll(generatedR7);
        residualSnapshots.removeAll(generatedR8);

        Map<IssueKey, Map<String, String>> r2ByIssue = new LinkedHashMap<>();
        for (Map<String, String> row : r2Rows) {
            r2ByIssue.put(new IssueKey(normalizePath(row.get("snapshot")), upper(row.get("table")),
                    upper(row.get("column")), row.get("issue_code")), row);
        }

        Map<String, Set<String>> classesBySnapshot = new LinkedHashMap<>();
        Map<String, Integer> residualCodes = new LinkedHashMap<>();
        List<MissingColumnCase> columnCases = new ArrayList<>();
        for (Map<String, String> row : r4Rows) {
            String snapshot = normalizePath(row.get("snapshot"));
            if (!residualSnapshots.contains(snapshot)) continue;
            TableColumn tc = parsePath(row.get("path"));
            IssueKey key = new IssueKey(snapshot, upper(tc.table()), upper(tc.column()), row.get("code"));
            Map<String, String> original = r2ByIssue.get(key);
            String classification = original == null ? "UNJOINED" : original.get("classification");
            classesBySnapshot.computeIfAbsent(snapshot, ignored -> new LinkedHashSet<>()).add(classification);
            residualCodes.merge(row.get("code"), 1, Integer::sum);
            if ("METADATA_COLUMN_NOT_FOUND".equals(classification)) {
                columnCases.add(new MissingColumnCase(snapshot, original.get("source"), original.get("schema"),
                        original.get("table"), original.get("column"), original.get("canonical_type"), row.get("code")));
            }
        }

        Map<String, Integer> snapshotClassifications = classifySnapshots(classesBySnapshot);
        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(db2File);
        Map<TableKey, Set<String>> db2Columns = readDb2ColumnNames(db2File);
        Map<String, DatabaseSchema> schemaCache = new HashMap<>();

        List<String> details = new ArrayList<>();
        details.add("snapshot,source,schema,table,column,canonical_type,issue_code,classification,candidate_column,metadata_type,score,candidate_count,detail");
        Map<String, Integer> candidateClasses = new LinkedHashMap<>();
        Set<String> columnNotFoundSnapshots = new LinkedHashSet<>();
        Set<String> strongSnapshots = new LinkedHashSet<>();
        int strongOccurrences = 0;

        for (MissingColumnCase item : columnCases) {
            columnNotFoundSnapshots.add(item.snapshot());
            DatabaseSchema canonicalSchema = schemaCache.computeIfAbsent(item.snapshot(), key -> loadSchema(snapshotRoot, key));
            Table canonicalTable = locateTable(canonicalSchema, item.table());
            Set<String> canonicalColumns = new LinkedHashSet<>();
            if (canonicalTable != null) {
                for (Column column : canonicalTable.columns()) canonicalColumns.add(upper(column.name().value()));
            }

            Set<String> sourceColumns = db2Columns.getOrDefault(new TableKey(upper(item.schema()), upper(item.table())), Set.of());
            CandidateResult result = chooseCandidate(item, sourceColumns, canonicalColumns, catalog);
            candidateClasses.merge(result.classification(), 1, Integer::sum);
            if ("STRONG_NORMALIZED_NAME_EXACT_NUMERIC".equals(result.classification())) {
                strongOccurrences++;
                strongSnapshots.add(item.snapshot());
            }
            details.add(csvLine(item.snapshot(), item.source(), item.schema(), item.table(), item.column(),
                    item.canonicalType(), item.issueCode(), result.classification(), result.column(),
                    result.metadataType(), Integer.toString(result.score()), Integer.toString(result.candidateCount()), result.detail()));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path detailsFile = outputRoot.resolve("mysql-column-reconciliation-details_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-column-reconciliation-summary_" + timestamp + ".txt");
        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(), StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL P2-R9 remaining blocker / column reconciliation audit");
        summary.add("====================================================================");
        summary.add("Snapshot directory             : " + snapshotRoot);
        summary.add("DB2 SYSCOLUMNS file            : " + db2File);
        summary.add("P2-R2 details                  : " + p2r2Details);
        summary.add("P2-R4 remaining                : " + p2r4Remaining);
        summary.add("P2-R7 details                  : " + p2r7Details);
        summary.add("P2-R8 details                  : " + p2r8Details);
        summary.add("P2-R4 blocked snapshots        : " + p2r4BlockedSnapshots.size());
        summary.add("P2-R7 generated snapshots      : " + generatedR7.size());
        summary.add("P2-R8 generated snapshots      : " + generatedR8.size());
        summary.add("Projected remaining snapshots  : " + residualSnapshots.size());
        summary.add("Column-not-found snapshots     : " + columnNotFoundSnapshots.size());
        summary.add("Column-not-found occurrences   : " + columnCases.size());
        summary.add("Strong normalized occurrences  : " + strongOccurrences);
        summary.add("Strong normalized snapshots    : " + strongSnapshots.size());
        summary.add("");
        summary.add("Residual snapshot classifications");
        summary.add("---------------------------------");
        snapshotClassifications.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Residual blocker occurrences");
        summary.add("----------------------------");
        residualCodes.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Column candidate classifications");
        summary.add("--------------------------------");
        candidateClasses.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Interpretation");
        summary.add("--------------");
        summary.add("STRONG_NORMALIZED_NAME_EXACT_NUMERIC is audit evidence only; no recovery is applied in P2-R9.");
        summary.add("REVIEW_* and AMBIGUOUS_* remain blocked until an independent evidence rule is added.");
        summary.add("Details: " + detailsFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("Projected remaining snapshots : " + residualSnapshots.size());
        System.out.println("Column-not-found snapshots    : " + columnNotFoundSnapshots.size());
        System.out.println("Column-not-found occurrences  : " + columnCases.size());
        System.out.println("Strong normalized snapshots   : " + strongSnapshots.size());
        candidateClasses.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                       : " + summaryFile);

        assertEquals(619, residualSnapshots.size(), "P2-R8 projected blocker baseline changed unexpectedly");
        assertTrue(columnNotFoundSnapshots.size() <= residualSnapshots.size());
    }

    private CandidateResult chooseCandidate(MissingColumnCase item, Set<String> sourceColumns,
                                            Set<String> canonicalColumns, Db2SysColumnsFileCatalog catalog) {
        List<ScoredCandidate> candidates = new ArrayList<>();
        String wanted = normalizedName(item.column());
        for (String candidate : sourceColumns) {
            if (canonicalColumns.contains(upper(candidate))) continue;
            DataType type = catalog.findType(item.schema(), item.table(), candidate).orElse(null);
            if (!usableExactNumeric(type)) continue;
            String actual = normalizedName(candidate);
            int score;
            String tier;
            if (wanted.equals(actual)) {
                score = 100;
                tier = "NORMALIZED";
            } else if (prefixCompatible(wanted, actual)) {
                score = 80;
                tier = "PREFIX";
            } else {
                int distance = levenshtein(wanted, actual);
                if (distance == 1 && Math.min(wanted.length(), actual.length()) >= 6) {
                    score = 70;
                    tier = "EDIT1";
                } else if (distance == 2 && Math.min(wanted.length(), actual.length()) >= 10) {
                    score = 60;
                    tier = "EDIT2";
                } else {
                    continue;
                }
            }
            candidates.add(new ScoredCandidate(candidate, type, score, tier));
        }
        if (candidates.isEmpty()) return new CandidateResult("NO_COLUMN_CANDIDATE", "", "", 0, 0, "No unused exact-numeric near-name DB2 column candidate.");
        candidates.sort(Comparator.comparingInt(ScoredCandidate::score).reversed().thenComparing(ScoredCandidate::column));
        int best = candidates.getFirst().score();
        List<ScoredCandidate> top = candidates.stream().filter(candidate -> candidate.score() == best).toList();
        if (top.size() > 1) {
            return new CandidateResult("AMBIGUOUS_" + top.getFirst().tier() + "_CANDIDATES", "", "", best, top.size(),
                    "Multiple equally ranked unused exact-numeric candidates in the exact DB2 table.");
        }
        ScoredCandidate selected = top.getFirst();
        String classification = switch (selected.tier()) {
            case "NORMALIZED" -> "STRONG_NORMALIZED_NAME_EXACT_NUMERIC";
            case "PREFIX" -> "REVIEW_PREFIX_NAME_EXACT_NUMERIC";
            case "EDIT1" -> "REVIEW_EDIT_DISTANCE_1_EXACT_NUMERIC";
            default -> "REVIEW_EDIT_DISTANCE_2_EXACT_NUMERIC";
        };
        return new CandidateResult(classification, selected.column(), renderType(selected.type()), selected.score(), 1,
                "Candidate is unused by the canonical table and is MySQL-mappable exact numeric metadata.");
    }

    private boolean usableExactNumeric(DataType type) {
        if (type == null || !EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT))) return false;
        try {
            mySqlTypeMapper.map(type);
            return true;
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private DatabaseSchema loadSchema(Path root, String relative) {
        try {
            Path path = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
            CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
            return mapper.toDomainPersistedSource(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load canonical snapshot " + relative, exception);
        }
    }

    private static Table locateTable(DatabaseSchema schema, String tableName) {
        if (schema == null) return null;
        for (Table table : schema.tables()) {
            if (upper(table.qualifiedName().name().value()).equals(upper(tableName))) return table;
        }
        return null;
    }

    private static Map<String, Integer> classifySnapshots(Map<String, Set<String>> classesBySnapshot) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Set<String> classes : classesBySnapshot.values()) {
            String key;
            if (classes.equals(Set.of("METADATA_TABLE_NOT_FOUND"))) key = "METADATA_TABLE_NOT_FOUND_ONLY";
            else if (classes.equals(Set.of("CANONICAL_METADATA_TYPE_CONFLICT"))) key = "CANONICAL_METADATA_TYPE_CONFLICT_ONLY";
            else if (classes.equals(Set.of("METADATA_COLUMN_NOT_FOUND"))) key = "METADATA_COLUMN_NOT_FOUND_ONLY";
            else if (classes.equals(Set.of("CANONICAL_METADATA_TYPE_CONFLICT", "METADATA_COLUMN_NOT_FOUND"))) key = "TYPE_CONFLICT_PLUS_COLUMN_NOT_FOUND";
            else if (classes.equals(Set.of("RECOVERABLE_WITH_CANONICAL_CONFLICT"))) key = "RECOVERABLE_WITH_CANONICAL_CONFLICT_ONLY";
            else if (classes.equals(Set.of("METADATA_COLUMN_NOT_FOUND", "RECOVERABLE_WITH_CANONICAL_CONFLICT"))) key = "COLUMN_NOT_FOUND_PLUS_RECOVERABLE_CONFLICT";
            else if (classes.equals(Set.of("ROWID_PHYSICAL_ARTIFACT_REVIEW"))) key = "ROWID_PHYSICAL_ARTIFACT_REVIEW_ONLY";
            else key = "OTHER:" + String.join("|", classes.stream().sorted().toList());
            result.merge(key, 1, Integer::sum);
        }
        return result;
    }

    private static Map<TableKey, Set<String>> readDb2ColumnNames(Path file) throws Exception {
        Map<TableKey, Set<String>> result = new LinkedHashMap<>();
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !isText(entry.getName())) continue;
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    zip.transferTo(buffer);
                    addDb2Text(buffer.toString(StandardCharsets.UTF_8), result);
                }
            }
        } else {
            addDb2Text(Files.readString(file, StandardCharsets.UTF_8), result);
        }
        return result;
    }

    private static void addDb2Text(String text, Map<TableKey, Set<String>> result) {
        if (text == null || text.isBlank()) return;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        char delimiter = detectDelimiter(text);
        List<List<String>> rows = parseDelimited(text, delimiter);
        if (rows.isEmpty()) return;
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < rows.getFirst().size(); i++) header.put(normalizeHeader(rows.getFirst().get(i)), i);
        int schema = first(header, "TBCREATOR", "TABLE_SCHEMA", "CREATOR");
        int table = first(header, "TBNAME", "TABLE_NAME");
        int column = first(header, "NAME", "COLNAME", "COLUMN_NAME");
        if (schema < 0 || table < 0 || column < 0) return;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String s = upper(value(row, schema));
            String t = upper(value(row, table));
            String c = upper(value(row, column));
            if (s.isBlank() || t.isBlank() || c.isBlank()) continue;
            result.computeIfAbsent(new TableKey(s, t), ignored -> new LinkedHashSet<>()).add(c);
        }
    }

    private static boolean isText(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt");
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        char best = ',';
        int bestCount = -1;
        for (char candidate : new char[]{',', '\t', ';', '|'}) {
            int count = 0;
            boolean quoted = false;
            for (int i = 0; i < first.length(); i++) {
                char ch = first.charAt(i);
                if (ch == '"') quoted = !quoted;
                else if (!quoted && ch == candidate) count++;
            }
            if (count > bestCount) { best = candidate; bestCount = count; }
        }
        return best;
    }

    private static List<List<String>> parseDelimited(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (!quoted && ch == delimiter) {
                row.add(cell.toString().trim()); cell.setLength(0);
            } else if (!quoted && (ch == '\n' || ch == '\r')) {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim()); cell.setLength(0); rows.add(List.copyOf(row)); row.clear();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString().trim()); rows.add(List.copyOf(row)); }
        return rows;
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
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j).trim().toLowerCase(Locale.ROOT), value(values, j));
            rows.add(row);
        }
        return rows;
    }

    private static Set<String> snapshotsWithDecision(List<Map<String, String>> rows, String decision) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, String> row : rows) if (decision.equals(row.get("decision"))) result.add(normalizePath(row.get("snapshot")));
        return result;
    }

    private static Set<String> uniqueValues(Collection<Map<String, String>> rows, String column) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, String> row : rows) result.add(normalizePath(row.get(column)));
        return result;
    }

    private static TableColumn parsePath(String path) {
        String[] parts = path == null ? new String[0] : path.split("\\.");
        if (parts.length >= 4 && "tables".equals(parts[0]) && "columns".equals(parts[2])) return new TableColumn(parts[1], parts[3]);
        return new TableColumn("", "");
    }

    private static boolean prefixCompatible(String a, String b) {
        int min = Math.min(a.length(), b.length());
        return min >= 6 && Math.abs(a.length() - b.length()) <= 4 && (a.startsWith(b) || b.startsWith(a));
    }

    private static int levenshtein(String left, String right) {
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

    private static String normalizedName(String value) {
        return upper(value).replaceAll("[^A-Z0-9]", "");
    }

    private static String renderType(DataType type) {
        if (type == null) return "";
        String name = type.name().value();
        if (type.length() != null) return name + "(" + type.length() + ")";
        if (type.precision() != null) return name + "(" + type.precision() + (type.scale() == null ? "" : "," + type.scale()) + ")";
        return name;
    }

    private static Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException(property + " must point to a directory: " + path);
        return path;
    }

    private static Path requiredFile(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(property + " must point to a file: " + path);
        return path;
    }

    private static Path outputDirectory() throws Exception {
        String value = System.getProperty(OUTPUT_DIR);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + OUTPUT_DIR);
        Path path = Path.of(value).toAbsolutePath().normalize();
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

    private static int first(Map<String, Integer> index, String... names) {
        for (String name : names) { Integer value = index.get(name); if (value != null) return value; }
        return -1;
    }

    private static String normalizeHeader(String value) {
        return upper(value).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String normalizePath(String value) { return value == null ? "" : value.replace('\\', '/'); }
    private static String value(List<String> row, int index) { return index < 0 || index >= row.size() ? "" : row.get(index); }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                result.add(cell.toString()); cell.setLength(0);
            } else cell.append(ch);
        }
        result.add(cell.toString());
        return result;
    }

    private static String csvLine(String... values) {
        List<String> cells = new ArrayList<>();
        for (String value : values) {
            String safe = value == null ? "" : value;
            cells.add('"' + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + '"');
        }
        return String.join(",", cells);
    }

    private record IssueKey(String snapshot, String table, String column, String code) { }
    private record TableColumn(String table, String column) { }
    private record TableKey(String schema, String table) { }
    private record MissingColumnCase(String snapshot, String source, String schema, String table, String column,
                                     String canonicalType, String issueCode) { }
    private record ScoredCandidate(String column, DataType type, int score, String tier) { }
    private record CandidateResult(String classification, String column, String metadataType, int score,
                                   int candidateCount, String detail) { }
}
