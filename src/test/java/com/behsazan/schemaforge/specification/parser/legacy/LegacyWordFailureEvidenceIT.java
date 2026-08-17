package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.snapshot.CanonicalSnapshotManifest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused diagnostic for legacy Word sources that still fail canonical conversion.
 *
 * <p>The test deliberately performs no recovery and changes no production semantics. It re-runs the
 * low-level legacy table extractor for sources listed as PARSE_FAILED in a prior snapshot manifest and
 * writes the raw extracted row evidence needed to decide whether another recovery rule is safe.</p>
 */
class LegacyWordFailureEvidenceIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.word.inputDir";
    private static final String SOURCE_MANIFEST = "schemaforge.snapshot.sourceManifest";
    private static final String OUTPUT_FILE = "schemaforge.snapshot.failureEvidence.output";
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;

    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "(?i)(?:legacy column|for column|column)\\s+([A-Za-z0-9_$#.-]+)");

    @Test
    void writesRawEvidenceForRemainingLegacyFailures() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path manifestPath = requiredFile(SOURCE_MANIFEST);
        Path output = outputFile(manifestPath);

        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        CanonicalSnapshotManifest manifest = mapper.readValue(
                manifestPath.toFile(), CanonicalSnapshotManifest.class);

        List<CanonicalSnapshotManifest.Entry> failures = manifest.entries() == null
                ? List.of()
                : manifest.entries().stream()
                .filter(entry -> "PARSE_FAILED".equalsIgnoreCase(entry.status()))
                .toList();

        WordTableParser parser = new SchemaForgeWordTableParser(MAX_FILE_BYTES);
        List<String> lines = new ArrayList<>();
        lines.add(csv(
                "source", "sha256", "manifest_error", "target_column", "match_status",
                "table_name", "source_table_index", "source_row_index", "technical_name",
                "logical_type_raw", "logical_type", "logical_type_confidence",
                "length_raw", "normalized_length", "length", "precision", "scale",
                "physical_type_raw", "physical_type", "physical_type_confidence",
                "physical_length_raw", "normalized_physical_length", "raw_cells", "parser_issues"));

        int documentsParsed = 0;
        int matchedRows = 0;
        int missingSources = 0;
        int lowLevelFailures = 0;

        for (CanonicalSnapshotManifest.Entry failure : failures) {
            Path document = inputRoot.resolve(failure.source().replace('/', java.io.File.separatorChar)).normalize();
            if (!Files.isRegularFile(document)) {
                missingSources++;
                lines.add(csv(
                        failure.source(), failure.sha256(), failure.error(), targetColumn(failure.error()),
                        "SOURCE_NOT_FOUND", "", "", "", "", "", "", "", "", "", "", "", "",
                        "", "", "", "", "", "", ""));
                continue;
            }

            String target = targetColumn(failure.error());
            try {
                WordTableParseResult result = parser.parse(inputRoot, document);
                documentsParsed++;
                String tableName = result.table() == null ? "" : safe(result.table().technicalName());
                String issues = result.issues().stream()
                        .map(issue -> safe(issue.code()) + ":" + safe(issue.rawValue()))
                        .reduce((left, right) -> left + " | " + right)
                        .orElse("");

                List<ParsedWordColumn> matches = matchingColumns(result.columns(), target);
                if (matches.isEmpty()) {
                    lines.add(csv(
                            failure.source(), failure.sha256(), failure.error(), target, "TARGET_NOT_FOUND",
                            tableName, "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                            "", "", "", issues));
                    continue;
                }

                for (ParsedWordColumn column : matches) {
                    matchedRows++;
                    lines.add(csv(
                            failure.source(), failure.sha256(), failure.error(), target,
                            target.isBlank() ? "CONTEXT_ROW" : "MATCHED",
                            tableName,
                            Integer.toString(column.sourceTableIndex()),
                            Integer.toString(column.sourceRowIndex()),
                            safe(column.technicalName()),
                            safe(column.logicalTypeRaw()),
                            safe(column.logicalType()),
                            column.logicalTypeConfidence().name(),
                            safe(column.lengthRaw()),
                            safe(column.normalizedLength()),
                            number(column.length()),
                            number(column.precision()),
                            number(column.scale()),
                            safe(column.physicalTypeRaw()),
                            safe(column.physicalType()),
                            column.physicalTypeConfidence().name(),
                            safe(column.physicalLengthRaw()),
                            safe(column.normalizedPhysicalLength()),
                            String.join(" || ", column.rawCells()),
                            issues));
                }
            } catch (Exception exception) {
                lowLevelFailures++;
                lines.add(csv(
                        failure.source(), failure.sha256(), failure.error(), target,
                        "LOW_LEVEL_PARSE_FAILED", "", "", "", "", "", "", "", "", "", "", "", "",
                        "", "", "", "", "", "",
                        exception.getClass().getSimpleName() + ": " + safe(exception.getMessage())));
            }
        }

        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, lines, StandardCharsets.UTF_8);

        System.out.println("Manifest failures  : " + failures.size());
        System.out.println("Documents parsed   : " + documentsParsed);
        System.out.println("Matched rows       : " + matchedRows);
        System.out.println("Missing sources    : " + missingSources);
        System.out.println("Low-level failures : " + lowLevelFailures);
        System.out.println("Evidence CSV       : " + output);

        assertTrue(!failures.isEmpty(), "Source manifest contains no PARSE_FAILED entries");
        assertTrue(Files.isRegularFile(output), "Evidence CSV was not written");
    }

    private static List<ParsedWordColumn> matchingColumns(List<ParsedWordColumn> columns, String target) {
        if (columns == null || columns.isEmpty()) return List.of();
        if (target == null || target.isBlank()) return columns;
        return columns.stream()
                .filter(column -> target.equalsIgnoreCase(safe(column.technicalName()))
                        || target.equalsIgnoreCase(safe(column.technicalNameRaw())))
                .toList();
    }

    private static String targetColumn(String error) {
        if (error == null || error.isBlank()) return "";
        Matcher matcher = COLUMN_PATTERN.matcher(error);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Path requiredDirectory(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Directory does not exist: " + path);
        return path;
    }

    private static Path requiredFile(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File does not exist: " + path);
        return path;
    }

    private static Path outputFile(Path manifestPath) {
        String configured = trimToNull(System.getProperty(OUTPUT_FILE));
        if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
        Path parent = manifestPath.toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("legacy-failure-evidence.csv").toAbsolutePath().normalize();
    }

    private static String csv(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String number(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
