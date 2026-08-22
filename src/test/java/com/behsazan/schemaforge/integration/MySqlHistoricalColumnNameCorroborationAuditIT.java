package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-R10 audit that tries to corroborate P2-R9 typo/prefix column candidates with an independent
 * historical canonical snapshot. Similarity alone is never accepted. A candidate is confirmed only
 * when the exact DB2 candidate column name appears in another snapshot of the same canonical
 * schema/table, remains in the exact-numeric family, the requested/misspelled name has no competing
 * historical occurrence, and the two names never coexist historically.
 *
 * <p>This is audit-only. It never mutates canonical JSON and never applies an overlay.</p>
 */
class MySqlHistoricalColumnNameCorroborationAuditIT {
    private static final String SNAPSHOT_DIR = "schemaforge.mysql.columnhistory.snapshotDir";
    private static final String P2R9_DIR = "schemaforge.mysql.columnhistory.p2r9Dir";
    private static final String OUTPUT_DIR = "schemaforge.mysql.columnhistory.outputDir";
    private static final String MIN_EVIDENCE = "schemaforge.mysql.columnhistory.minEvidence";

    private static final Set<String> EXACT_NUMERIC = Set.of(
            "NUMBER", "DECIMAL", "NUMERIC", "SMALLINT", "INTEGER", "BIGINT", "TINYINT");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();

    @Test
    void auditsP2R9ReviewCandidatesAgainstIndependentHistoricalColumnNames() throws Exception {
        Path snapshotRoot = requiredDirectory(SNAPSHOT_DIR);
        Path p2r9Root = requiredDirectory(P2R9_DIR);
        Path outputRoot = outputDirectory();
        int minEvidence = integerProperty(MIN_EVIDENCE, 1);
        if (minEvidence < 1) throw new IllegalArgumentException(MIN_EVIDENCE + " must be >= 1");

        Path p2r9Details = latestFile(p2r9Root, "mysql-column-reconciliation-details_", ".csv");
        List<Map<String, String>> allR9Rows = readCsv(p2r9Details);
        List<Map<String, String>> reviewRows = allR9Rows.stream()
                .filter(row -> row.getOrDefault("classification", "").startsWith("REVIEW_"))
                .toList();

        List<LoadedSnapshot> snapshots = loadSnapshots(snapshotRoot);
        Map<TableKey, List<TableOccurrence>> history = buildHistory(snapshots);

        Map<String, Integer> classifications = new LinkedHashMap<>();
        Set<String> reviewSnapshots = new LinkedHashSet<>();
        Set<String> confirmedSnapshots = new LinkedHashSet<>();
        Set<String> confirmedPairs = new LinkedHashSet<>();
        int confirmedOccurrences = 0;

        List<String> details = new ArrayList<>();
        details.add("snapshot,source,schema,table,column,p2r9_classification,candidate_column,metadata_type,historical_candidate_occurrences,historical_requested_occurrences,historical_coexist_occurrences,historical_candidate_type_conflicts,decision,evidence_sources,detail");

        for (Map<String, String> row : reviewRows) {
            String snapshot = normalizePath(row.get("snapshot"));
            String schema = upper(row.get("schema"));
            String table = upper(row.get("table"));
            String requested = upper(row.get("column"));
            String candidate = upper(row.get("candidate_column"));
            reviewSnapshots.add(snapshot);

            Evidence evidence = historicalEvidence(history.getOrDefault(new TableKey(schema, table), List.of()),
                    snapshot, requested, candidate);
            String decision;
            String detail;
            if (evidence.candidateOccurrences() < minEvidence) {
                decision = "NO_HISTORICAL_CANDIDATE_NAME";
                detail = "Exact candidate column name was not found often enough in other snapshots of the same schema/table.";
            } else if (evidence.candidateTypeConflicts() > 0) {
                decision = "REJECT_HISTORICAL_CANDIDATE_TYPE_CONFLICT";
                detail = "Historical candidate exists but at least one occurrence is outside the exact-numeric family.";
            } else if (evidence.coexistOccurrences() > 0) {
                decision = "REJECT_HISTORICAL_NAMES_COEXIST";
                detail = "Requested and candidate names coexist in historical snapshots, so they cannot be treated as a typo alias.";
            } else if (evidence.requestedOccurrences() > 0) {
                decision = "REVIEW_HISTORICAL_BOTH_NAMES";
                detail = "Both names occur historically in different snapshots; this may be a rename and needs stronger evidence.";
            } else {
                decision = "CONFIRMED_HISTORICAL_CANDIDATE_NAME";
                detail = "Independent historical canonical evidence contains only the exact DB2 candidate name in the same schema/table and exact-numeric family.";
                confirmedOccurrences++;
                confirmedSnapshots.add(snapshot);
                confirmedPairs.add(schema + "." + table + "." + requested + "->" + candidate);
            }
            classifications.merge(decision, 1, Integer::sum);
            details.add(csvLine(snapshot, row.get("source"), schema, table, requested,
                    row.get("classification"), candidate, row.get("metadata_type"),
                    Integer.toString(evidence.candidateOccurrences()), Integer.toString(evidence.requestedOccurrences()),
                    Integer.toString(evidence.coexistOccurrences()), Integer.toString(evidence.candidateTypeConflicts()),
                    decision, String.join("|", evidence.sources()), detail));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path detailsFile = outputRoot.resolve("mysql-historical-column-corroboration-details_" + timestamp + ".csv");
        Path summaryFile = outputRoot.resolve("mysql-historical-column-corroboration-summary_" + timestamp + ".txt");
        Files.writeString(detailsFile, String.join(System.lineSeparator(), details) + System.lineSeparator(), StandardCharsets.UTF_8);

        List<String> summary = new ArrayList<>();
        summary.add("SchemaForge MySQL P2-R10 historical column-name corroboration audit");
        summary.add("=================================================================");
        summary.add("Snapshot directory             : " + snapshotRoot);
        summary.add("P2-R9 details                  : " + p2r9Details);
        summary.add("Snapshots loaded               : " + snapshots.size());
        summary.add("Minimum historical evidence    : " + minEvidence);
        summary.add("P2-R9 review occurrences       : " + reviewRows.size());
        summary.add("P2-R9 review snapshots         : " + reviewSnapshots.size());
        summary.add("Confirmed occurrences          : " + confirmedOccurrences);
        summary.add("Confirmed snapshots            : " + confirmedSnapshots.size());
        summary.add("Unique confirmed mappings      : " + confirmedPairs.size());
        summary.add("");
        summary.add("Historical corroboration decisions");
        summary.add("------------------------------------");
        if (classifications.isEmpty()) summary.add("None");
        else classifications.forEach((key, value) -> summary.add(key + " : " + value));
        summary.add("");
        summary.add("Interpretation");
        summary.add("--------------");
        summary.add("CONFIRMED_HISTORICAL_CANDIDATE_NAME is evidence only; P2-R10 does not apply recovery.");
        summary.add("Similarity-only, coexistence, historical rename ambiguity, and type-family conflicts remain blocked.");
        summary.add("Details: " + detailsFile);
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("P2-R9 review occurrences       : " + reviewRows.size());
        System.out.println("P2-R9 review snapshots         : " + reviewSnapshots.size());
        System.out.println("Confirmed occurrences          : " + confirmedOccurrences);
        System.out.println("Confirmed snapshots            : " + confirmedSnapshots.size());
        System.out.println("Unique confirmed mappings      : " + confirmedPairs.size());
        classifications.forEach((key, value) -> System.out.println(key + " : " + value));
        System.out.println("Summary                         : " + summaryFile);

        assertEquals(40, reviewRows.size(), "P2-R9 review baseline changed unexpectedly");
        assertTrue(reviewSnapshots.size() <= reviewRows.size());
    }

    private List<LoadedSnapshot> loadSnapshots(Path root) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json"))
                    .sorted(Comparator.comparing(path -> normalizePath(root.relativize(path).toString())))
                    .toList();
        }
        List<LoadedSnapshot> result = new ArrayList<>();
        for (Path path : paths) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                result.add(new LoadedSnapshot(normalizePath(root.relativize(path).toString()), schema));
            } catch (RuntimeException ignored) {
                // Keep this evidence-only audit resilient to non-canonical/corrupt snapshot artifacts.
                // The canonical corpus is independently validated by the earlier P2 audits.
            }
        }
        return List.copyOf(result);
    }

    private static Map<TableKey, List<TableOccurrence>> buildHistory(List<LoadedSnapshot> snapshots) {
        Map<TableKey, List<TableOccurrence>> result = new LinkedHashMap<>();
        for (LoadedSnapshot loaded : snapshots) {
            DatabaseSchema schema = loaded.schema();
            for (Table table : schema.tables()) {
                String schemaName = table.qualifiedName().schemaName()
                        .map(identifier -> identifier.value()).orElse(schema.name().value());
                String tableName = table.qualifiedName().name().value();
                Map<String, Column> columns = new LinkedHashMap<>();
                for (Column column : table.columns()) columns.put(upper(column.name().value()), column);
                result.computeIfAbsent(new TableKey(upper(schemaName), upper(tableName)), ignored -> new ArrayList<>())
                        .add(new TableOccurrence(loaded.relative(), Map.copyOf(columns)));
            }
        }
        return result;
    }

    private static Evidence historicalEvidence(List<TableOccurrence> occurrences, String currentSnapshot,
                                               String requested, String candidate) {
        int candidateOccurrences = 0;
        int requestedOccurrences = 0;
        int coexistOccurrences = 0;
        int candidateTypeConflicts = 0;
        Set<String> sources = new LinkedHashSet<>();
        for (TableOccurrence occurrence : occurrences) {
            if (normalizePath(occurrence.snapshot()).equals(normalizePath(currentSnapshot))) continue;
            boolean hasCandidate = occurrence.columns().containsKey(candidate);
            boolean hasRequested = occurrence.columns().containsKey(requested);
            if (hasCandidate) {
                candidateOccurrences++;
                sources.add(occurrence.snapshot());
                if (!isExactNumeric(occurrence.columns().get(candidate).dataType())) candidateTypeConflicts++;
            }
            if (hasRequested) requestedOccurrences++;
            if (hasCandidate && hasRequested) coexistOccurrences++;
        }
        return new Evidence(candidateOccurrences, requestedOccurrences, coexistOccurrences,
                candidateTypeConflicts, List.copyOf(sources));
    }

    private static boolean isExactNumeric(DataType type) {
        return type != null && EXACT_NUMERIC.contains(type.name().normalized().toUpperCase(Locale.ROOT));
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

    private static Path latestFile(Path root, String prefix, String suffix) throws Exception {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException("No " + prefix + "*" + suffix + " file found in " + root));
        }
    }

    private static Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException(property + " must point to a directory: " + path);
        return path;
    }

    private static Path outputDirectory() throws Exception {
        String value = System.getProperty(OUTPUT_DIR);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + OUTPUT_DIR);
        Path path = Path.of(value).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    private static int integerProperty(String property, int defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value.trim());
    }

    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String normalizePath(String value) { return value == null ? "" : value.replace('\\', '/'); }
    private static String value(List<String> row, int index) { return index < 0 || index >= row.size() ? "" : row.get(index); }

    private record LoadedSnapshot(String relative, DatabaseSchema schema) { }
    private record TableKey(String schema, String table) { }
    private record TableOccurrence(String snapshot, Map<String, Column> columns) { }
    private record Evidence(int candidateOccurrences, int requestedOccurrences, int coexistOccurrences,
                            int candidateTypeConflicts, List<String> sources) { }
}
