package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the M10.1 table-selection manifest for a historical canonical JSON corpus.
 *
 * <p>The runner never chooses between conflicting historical definitions. A qualified table with
 * exactly one definition is auto-selected. A duplicate group classified by the preceding duplicate
 * audit as SAFE_EXACT_LOGICAL is collapsed to one deterministic representative because all members
 * have the same logical signature. Every other duplicate group is emitted as REQUIRES_REVIEW with
 * no selected snapshot.</p>
 *
 * <p>This is intentionally a test-only reconciliation artifact. Production integrated deployment
 * remains strict and continues to require exactly one approved definition per qualified table.</p>
 */
class CanonicalJsonSelectionManifestIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.selection.inputDir";
    private static final String AUDIT_GROUPS = "schemaforge.snapshot.selection.auditGroups";
    private static final String AUDIT_MEMBERS = "schemaforge.snapshot.selection.auditMembers";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.selection.outputDir";
    private static final String CLEAN_OUTPUT = "schemaforge.snapshot.selection.cleanOutput";
    private static final String FAIL_ON_AUDIT_MISMATCH = "schemaforge.snapshot.selection.failOnAuditMismatch";
    private static final String FAIL_ON_REVIEW = "schemaforge.snapshot.selection.failOnReview";

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();

    @Test
    void buildsDeterministicNoGuessSelectionManifest() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path groupsFile = requiredFile(AUDIT_GROUPS);
        Path membersFile = requiredFile(AUDIT_MEMBERS);
        Path outputRoot = outputDirectory(inputRoot);
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "true"));
        boolean failOnAuditMismatch = Boolean.parseBoolean(System.getProperty(FAIL_ON_AUDIT_MISMATCH, "true"));
        boolean failOnReview = Boolean.parseBoolean(System.getProperty(FAIL_ON_REVIEW, "false"));

        validateNonOverlapping(inputRoot, outputRoot);
        if (cleanOutput) cleanDirectory(outputRoot);
        Files.createDirectories(outputRoot);

        Map<String, List<Occurrence>> byTable = loadCorpus(inputRoot);
        Map<String, AuditGroup> groups = readGroups(groupsFile);
        Map<String, List<AuditMember>> members = readMembers(membersFile);

        List<String> mismatches = new ArrayList<>();
        mismatches.add("table,code,message");

        int corpusDuplicateGroups = 0;
        int corpusDuplicateOccurrences = 0;
        for (Map.Entry<String, List<Occurrence>> entry : byTable.entrySet()) {
            if (entry.getValue().size() > 1) {
                corpusDuplicateGroups++;
                corpusDuplicateOccurrences += entry.getValue().size();
            }
        }

        if (groups.size() != corpusDuplicateGroups) {
            mismatches.add(csvLine("", "AUDIT_GROUP_COUNT_MISMATCH",
                    "audit=" + groups.size() + "; corpus=" + corpusDuplicateGroups));
        }
        int auditMemberCount = members.values().stream().mapToInt(List::size).sum();
        if (auditMemberCount != corpusDuplicateOccurrences) {
            mismatches.add(csvLine("", "AUDIT_MEMBER_COUNT_MISMATCH",
                    "audit=" + auditMemberCount + "; corpus=" + corpusDuplicateOccurrences));
        }

        List<ManifestRow> manifest = new ArrayList<>();
        List<ReviewCandidate> reviewCandidates = new ArrayList<>();
        int autoUnique = 0;
        int autoExact = 0;
        int review = 0;

        for (Map.Entry<String, List<Occurrence>> entry : byTable.entrySet()) {
            String table = entry.getKey();
            List<Occurrence> occurrences = entry.getValue().stream()
                    .sorted(Comparator.comparing(Occurrence::snapshot).thenComparing(Occurrence::source))
                    .toList();

            if (occurrences.size() == 1) {
                Occurrence selected = occurrences.getFirst();
                manifest.add(new ManifestRow(
                        table,
                        SelectionStatus.AUTO_SELECTED_UNIQUE,
                        "UNIQUE_TABLE_DEFINITION",
                        "UNIQUE",
                        "NOT_APPLICABLE",
                        1,
                        selected.snapshot(),
                        selected.source(),
                        selected.sourceSha256(),
                        "",
                        ""));
                autoUnique++;
                continue;
            }

            AuditGroup group = groups.get(table);
            List<AuditMember> groupMembers = members.getOrDefault(table, List.of());
            if (group == null) {
                mismatches.add(csvLine(table, "AUDIT_GROUP_MISSING",
                        "Corpus has " + occurrences.size() + " definitions but audit group is absent"));
                manifest.add(new ManifestRow(
                        table, SelectionStatus.REQUIRES_REVIEW, "AUDIT_GROUP_MISSING", "UNKNOWN", "UNKNOWN",
                        occurrences.size(), "", "", "", "", ""));
                addCorpusCandidates(reviewCandidates, table, "UNKNOWN", "UNKNOWN", occurrences);
                review++;
                continue;
            }

            if (group.occurrences() != occurrences.size()) {
                mismatches.add(csvLine(table, "AUDIT_OCCURRENCE_MISMATCH",
                        "audit=" + group.occurrences() + "; corpus=" + occurrences.size()));
            }
            if (groupMembers.size() != occurrences.size()) {
                mismatches.add(csvLine(table, "AUDIT_MEMBER_GROUP_MISMATCH",
                        "audit=" + groupMembers.size() + "; corpus=" + occurrences.size()));
            }

            if (group.safeExactLogical()) {
                List<String> signatures = groupMembers.stream()
                        .map(AuditMember::exactLogicalSha256)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
                if (signatures.size() != 1) {
                    mismatches.add(csvLine(table, "EXACT_GROUP_SIGNATURE_MISMATCH",
                            "SAFE_EXACT_LOGICAL group has " + signatures.size() + " exact signatures"));
                    manifest.add(new ManifestRow(
                            table, SelectionStatus.REQUIRES_REVIEW, "AUDIT_SIGNATURE_MISMATCH",
                            group.classification(), group.collapseSafety(), occurrences.size(),
                            "", "", "", "", group.differenceFlags()));
                    addAuditCandidates(reviewCandidates, group, groupMembers);
                    review++;
                    continue;
                }

                AuditMember representative = groupMembers.stream()
                        .sorted(Comparator.comparing(AuditMember::snapshot)
                                .thenComparing(AuditMember::source)
                                .thenComparing(AuditMember::sourceSha256))
                        .findFirst()
                        .orElse(null);
                if (representative == null) {
                    mismatches.add(csvLine(table, "EXACT_GROUP_MEMBER_MISSING", "No audit member was found"));
                    manifest.add(new ManifestRow(
                            table, SelectionStatus.REQUIRES_REVIEW, "AUDIT_MEMBER_MISSING",
                            group.classification(), group.collapseSafety(), occurrences.size(),
                            "", "", "", "", group.differenceFlags()));
                    addCorpusCandidates(reviewCandidates, table, group.classification(), group.collapseSafety(), occurrences);
                    review++;
                    continue;
                }

                Occurrence selected = occurrences.stream()
                        .filter(value -> value.snapshot().equals(normalizePath(representative.snapshot())))
                        .findFirst()
                        .orElse(null);
                if (selected == null) {
                    mismatches.add(csvLine(table, "SELECTED_SNAPSHOT_NOT_IN_CORPUS", representative.snapshot()));
                    manifest.add(new ManifestRow(
                            table, SelectionStatus.REQUIRES_REVIEW, "AUDIT_MEMBER_NOT_IN_CORPUS",
                            group.classification(), group.collapseSafety(), occurrences.size(),
                            "", "", "", signatures.getFirst(), group.differenceFlags()));
                    addAuditCandidates(reviewCandidates, group, groupMembers);
                    review++;
                    continue;
                }

                manifest.add(new ManifestRow(
                        table,
                        SelectionStatus.AUTO_SELECTED_EXACT_DUPLICATE,
                        "SAFE_EXACT_LOGICAL_REPRESENTATIVE",
                        group.classification(),
                        group.collapseSafety(),
                        occurrences.size(),
                        selected.snapshot(),
                        selected.source(),
                        selected.sourceSha256(),
                        signatures.getFirst(),
                        group.differenceFlags()));
                autoExact++;
            } else {
                manifest.add(new ManifestRow(
                        table,
                        SelectionStatus.REQUIRES_REVIEW,
                        "HISTORICAL_CONFLICT_NO_AUTOMATIC_WINNER",
                        group.classification(),
                        group.collapseSafety(),
                        occurrences.size(),
                        "", "", "", "", group.differenceFlags()));
                addAuditCandidates(reviewCandidates, group, groupMembers);
                review++;
            }
        }

        manifest.sort(Comparator.comparing(ManifestRow::table));
        reviewCandidates.sort(Comparator.comparing(ReviewCandidate::table)
                .thenComparing(ReviewCandidate::snapshot)
                .thenComparing(ReviewCandidate::source));

        String timestamp = outputFileNamer.timestamp();
        Path manifestPath = outputRoot.resolve("selection-manifest_" + timestamp + ".csv");
        Path selectedPath = outputRoot.resolve("selection-auto-selected_" + timestamp + ".csv");
        Path reviewPath = outputRoot.resolve("selection-review-candidates_" + timestamp + ".csv");
        Path overridesPath = outputRoot.resolve("selection-review-decisions-template_" + timestamp + ".csv");
        Path mismatchesPath = outputRoot.resolve("selection-audit-mismatches_" + timestamp + ".csv");
        Path summaryPath = outputRoot.resolve("selection-summary_" + timestamp + ".txt");

        writeManifest(manifestPath, manifest);
        writeSelected(selectedPath, manifest);
        writeReviewCandidates(reviewPath, reviewCandidates);
        writeDecisionTemplate(overridesPath, manifest);
        Files.write(mismatchesPath, mismatches, StandardCharsets.UTF_8);

        long selectedCount = manifest.stream().filter(ManifestRow::selected).count();
        String summary = summary(
                inputRoot, groupsFile, membersFile, byTable.size(), corpusDuplicateGroups,
                corpusDuplicateOccurrences, autoUnique, autoExact, review, selectedCount,
                mismatches.size() - 1, manifestPath, selectedPath, reviewPath, overridesPath,
                mismatchesPath, summaryPath);
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        System.out.println(summary);

        assertTrue(manifest.size() == byTable.size(),
                "Manifest row count must equal distinct qualified table count");
        if (failOnAuditMismatch) {
            assertTrue(mismatches.size() == 1,
                    "Selection audit mismatches were reported in " + mismatchesPath);
        }
        if (failOnReview) {
            assertTrue(review == 0,
                    "Selection still has " + review + " REQUIRES_REVIEW tables; see " + reviewPath);
        }
    }

    private Map<String, List<Occurrence>> loadCorpus(Path inputRoot) throws Exception {
        Map<String, List<Occurrence>> byTable = new TreeMap<>();
        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonSelectionManifestIT::isSnapshot)
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertTrue(!snapshots.isEmpty(), "No *.schema.json snapshots found under " + inputRoot);

        for (Path snapshotPath : snapshots) {
            CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
            DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
            String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
            String source = snapshot.source() == null ? "" : nullToEmpty(snapshot.source().relativePath());
            String sourceSha256 = snapshot.source() == null ? "" : nullToEmpty(snapshot.source().sha256());
            for (Table table : schema.tables()) {
                Occurrence occurrence = new Occurrence(
                        tableKey(table), relativeSnapshot, source, sourceSha256,
                        table.columns().size(), table.foreignKeys().size(), table.primaryKey().isPresent());
                byTable.computeIfAbsent(occurrence.table(), ignored -> new ArrayList<>()).add(occurrence);
            }
        }
        return byTable;
    }

    private static Map<String, AuditGroup> readGroups(Path file) throws Exception {
        Map<String, AuditGroup> result = new TreeMap<>();
        for (Map<String, String> row : readCsv(file)) {
            AuditGroup value = new AuditGroup(
                    required(row, "table").toUpperCase(Locale.ROOT),
                    Integer.parseInt(required(row, "occurrences")),
                    required(row, "classification"),
                    required(row, "collapse_safety"),
                    row.getOrDefault("difference_flags", ""));
            AuditGroup previous = result.put(value.table(), value);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate table in audit groups: " + value.table());
            }
        }
        return result;
    }

    private static Map<String, List<AuditMember>> readMembers(Path file) throws Exception {
        Map<String, List<AuditMember>> result = new TreeMap<>();
        for (Map<String, String> row : readCsv(file)) {
            AuditMember value = new AuditMember(
                    required(row, "table").toUpperCase(Locale.ROOT),
                    normalizePath(required(row, "snapshot")),
                    row.getOrDefault("source", ""),
                    row.getOrDefault("source_sha256", ""),
                    row.getOrDefault("exact_logical_sha256", ""),
                    row.getOrDefault("column_count", ""),
                    row.getOrDefault("foreign_key_count", ""),
                    row.getOrDefault("unique_key_count", ""),
                    row.getOrDefault("check_count", ""),
                    row.getOrDefault("has_primary_key", ""));
            result.computeIfAbsent(value.table(), ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static void addAuditCandidates(
            List<ReviewCandidate> target,
            AuditGroup group,
            List<AuditMember> members) {
        for (AuditMember member : members) {
            target.add(new ReviewCandidate(
                    group.table(), group.classification(), group.collapseSafety(), group.differenceFlags(),
                    member.snapshot(), member.source(), member.sourceSha256(), member.exactLogicalSha256(),
                    member.columnCount(), member.foreignKeyCount(), member.uniqueKeyCount(), member.checkCount(),
                    member.hasPrimaryKey()));
        }
    }

    private static void addCorpusCandidates(
            List<ReviewCandidate> target,
            String table,
            String classification,
            String collapseSafety,
            List<Occurrence> occurrences) {
        for (Occurrence occurrence : occurrences) {
            target.add(new ReviewCandidate(
                    table, classification, collapseSafety, "",
                    occurrence.snapshot(), occurrence.source(), occurrence.sourceSha256(), "",
                    Integer.toString(occurrence.columnCount()), Integer.toString(occurrence.foreignKeyCount()),
                    "", "", Boolean.toString(occurrence.hasPrimaryKey())));
        }
    }

    private static void writeManifest(Path path, List<ManifestRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("table,status,reason,classification,collapse_safety,candidate_count,selected_snapshot,selected_source,selected_source_sha256,exact_logical_sha256,difference_flags");
        for (ManifestRow row : rows) {
            lines.add(csvLine(
                    row.table(), row.status().name(), row.reason(), row.classification(), row.collapseSafety(),
                    Integer.toString(row.candidateCount()), row.selectedSnapshot(), row.selectedSource(),
                    row.selectedSourceSha256(), row.exactLogicalSha256(), row.differenceFlags()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeSelected(Path path, List<ManifestRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("table,status,selected_snapshot,selected_source,selected_source_sha256,exact_logical_sha256");
        for (ManifestRow row : rows) {
            if (!row.selected()) continue;
            lines.add(csvLine(row.table(), row.status().name(), row.selectedSnapshot(), row.selectedSource(),
                    row.selectedSourceSha256(), row.exactLogicalSha256()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeReviewCandidates(Path path, List<ReviewCandidate> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("table,classification,collapse_safety,difference_flags,snapshot,source,source_sha256,exact_logical_sha256,column_count,foreign_key_count,unique_key_count,check_count,has_primary_key");
        for (ReviewCandidate row : rows) {
            lines.add(csvLine(
                    row.table(), row.classification(), row.collapseSafety(), row.differenceFlags(),
                    row.snapshot(), row.source(), row.sourceSha256(), row.exactLogicalSha256(),
                    row.columnCount(), row.foreignKeyCount(), row.uniqueKeyCount(), row.checkCount(),
                    row.hasPrimaryKey()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeDecisionTemplate(Path path, List<ManifestRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("table,classification,difference_flags,selected_snapshot,decision_note");
        for (ManifestRow row : rows) {
            if (row.status() != SelectionStatus.REQUIRES_REVIEW) continue;
            lines.add(csvLine(row.table(), row.classification(), row.differenceFlags(), "", ""));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String summary(
            Path inputRoot,
            Path groupsFile,
            Path membersFile,
            int distinctTables,
            int duplicateGroups,
            int duplicateOccurrences,
            int autoUnique,
            int autoExact,
            int review,
            long selectedCount,
            int mismatchCount,
            Path manifestPath,
            Path selectedPath,
            Path reviewPath,
            Path overridesPath,
            Path mismatchesPath,
            Path summaryPath) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge M10.1 selection manifest").append(System.lineSeparator());
        text.append("===================================").append(System.lineSeparator());
        text.append("Canonical input             : ").append(inputRoot).append(System.lineSeparator());
        text.append("Duplicate groups audit      : ").append(groupsFile).append(System.lineSeparator());
        text.append("Duplicate members audit     : ").append(membersFile).append(System.lineSeparator());
        text.append("Distinct qualified tables   : ").append(distinctTables).append(System.lineSeparator());
        text.append("Duplicate table groups      : ").append(duplicateGroups).append(System.lineSeparator());
        text.append("Definitions in dup groups   : ").append(duplicateOccurrences).append(System.lineSeparator());
        text.append("AUTO_SELECTED_UNIQUE        : ").append(autoUnique).append(System.lineSeparator());
        text.append("AUTO_SELECTED_EXACT_DUP     : ").append(autoExact).append(System.lineSeparator());
        text.append("AUTO_SELECTED_TOTAL         : ").append(selectedCount).append(System.lineSeparator());
        text.append("REQUIRES_REVIEW             : ").append(review).append(System.lineSeparator());
        text.append("Audit mismatches            : ").append(mismatchCount).append(System.lineSeparator());
        text.append("Decision rule               : NO_GUESS; only unique or SAFE_EXACT_LOGICAL auto-select").append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("Outputs").append(System.lineSeparator());
        text.append("-------").append(System.lineSeparator());
        text.append("Summary                     : ").append(summaryPath).append(System.lineSeparator());
        text.append("Manifest                    : ").append(manifestPath).append(System.lineSeparator());
        text.append("Auto-selected               : ").append(selectedPath).append(System.lineSeparator());
        text.append("Review candidates           : ").append(reviewPath).append(System.lineSeparator());
        text.append("Review decision template    : ").append(overridesPath).append(System.lineSeparator());
        text.append("Audit mismatches            : ").append(mismatchesPath).append(System.lineSeparator());
        return text.toString();
    }

    private static List<Map<String, String>> readCsv(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return List.of();
        List<String> headers = parseCsvLine(lines.getFirst());
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = parseCsvLine(lines.get(i));
            if (values.size() != headers.size()) {
                throw new IllegalArgumentException("Malformed CSV row " + (i + 1) + " in " + file
                        + ": expected " + headers.size() + " cells but found " + values.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) row.put(headers.get(j), values.get(j));
            result.add(row);
        }
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV value: " + line);
        values.add(current.toString());
        return values;
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required CSV column is blank: " + column);
        }
        return value.trim();
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Directory does not exist: " + path);
        return path;
    }

    private static Path requiredFile(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-selection-manifest").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static void validateNonOverlapping(Path inputRoot, Path outputRoot) {
        if (outputRoot.startsWith(inputRoot) || inputRoot.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Input and output directories must not overlap: input="
                    + inputRoot + ", output=" + outputRoot);
        }
    }

    private static void cleanDirectory(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String tableKey(Table table) {
        return table.qualifiedName().toString().toUpperCase(Locale.ROOT);
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String text = value == null ? "" : value;
            escaped.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String normalize(Path path) {
        return normalizePath(path.toString());
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private enum SelectionStatus {
        AUTO_SELECTED_UNIQUE,
        AUTO_SELECTED_EXACT_DUPLICATE,
        REQUIRES_REVIEW
    }

    private record Occurrence(
            String table,
            String snapshot,
            String source,
            String sourceSha256,
            int columnCount,
            int foreignKeyCount,
            boolean hasPrimaryKey) {
        private Occurrence {
            Objects.requireNonNull(table);
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(source);
            Objects.requireNonNull(sourceSha256);
        }
    }

    private record AuditGroup(
            String table,
            int occurrences,
            String classification,
            String collapseSafety,
            String differenceFlags) {
        private boolean safeExactLogical() {
            return "EXACT_LOGICAL_DUPLICATE".equals(classification)
                    && "SAFE_EXACT_LOGICAL".equals(collapseSafety);
        }
    }

    private record AuditMember(
            String table,
            String snapshot,
            String source,
            String sourceSha256,
            String exactLogicalSha256,
            String columnCount,
            String foreignKeyCount,
            String uniqueKeyCount,
            String checkCount,
            String hasPrimaryKey) {
    }

    private record ManifestRow(
            String table,
            SelectionStatus status,
            String reason,
            String classification,
            String collapseSafety,
            int candidateCount,
            String selectedSnapshot,
            String selectedSource,
            String selectedSourceSha256,
            String exactLogicalSha256,
            String differenceFlags) {
        private boolean selected() {
            return status != SelectionStatus.REQUIRES_REVIEW && !selectedSnapshot.isBlank();
        }
    }

    private record ReviewCandidate(
            String table,
            String classification,
            String collapseSafety,
            String differenceFlags,
            String snapshot,
            String source,
            String sourceSha256,
            String exactLogicalSha256,
            String columnCount,
            String foreignKeyCount,
            String uniqueKeyCount,
            String checkCount,
            String hasPrimaryKey) {
    }
}
