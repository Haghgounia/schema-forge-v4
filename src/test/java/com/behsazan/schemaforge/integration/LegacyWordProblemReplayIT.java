package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordDirectoryClassifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fast v8 replay gate for Legacy Word directory classification.
 *
 * <p>This test deliberately does not rescan the complete Legacy directory and does not rerun
 * Standard/RTL ZIP generation or the Legacy production batch. It consumes the v7
 * legacy-directory-document-summary.csv, selects only rows previously classified as
 * CLASSIFICATION_FAILED, resolves those relative paths directly under the configured Legacy
 * directory, and replays only those documents through {@link LegacyWordDirectoryClassifier}.</p>
 *
 * <p>For the v8 POI security-policy classification change this reduces the replay set from tens
 * of thousands of Word files to the prior problem set only. Unchanged v7 rows are copied into a
 * merged v8 document summary without reopening their source documents.</p>
 */
class LegacyWordProblemReplayIT {
    private static final String LEGACY_DIR = "schemaforge.e2e.legacyDir";
    private static final String V7_DOCUMENT_SUMMARY = "schemaforge.e2e.legacyReplaySummary";
    private static final String OUTPUT_DIR = "schemaforge.e2e.outputDir";

    @Test
    void replaysOnlyPriorLegacyClassificationFailures() throws Exception {
        Path inputRoot = ExternalGenerationAcceptanceSupport.requiredDirectory(LEGACY_DIR);
        Path previousSummary = ExternalGenerationAcceptanceSupport.requiredFile(V7_DOCUMENT_SUMMARY);
        Path outputRoot = ExternalGenerationAcceptanceSupport.configuredOutputDirectory(
                OUTPUT_DIR, Path.of(".").toAbsolutePath().normalize(), "schemaforge-e2e-output");

        List<ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow> previousRows =
                readDocumentSummary(previousSummary);
        Assertions.assertFalse(previousRows.isEmpty(), "v7 legacy document summary must not be empty");

        List<ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow> mergedRows =
                new ArrayList<>(previousRows.size());
        List<ReplayRow> replayRows = new ArrayList<>();

        int selected = 0;
        int securitySkipped = 0;
        int classificationFailed = 0;
        int unexpectedOutcome = 0;
        int sourceMissing = 0;

        for (ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow previous : previousRows) {
            if (!"CLASSIFICATION_FAILED".equals(previous.classification())) {
                mergedRows.add(previous);
                continue;
            }

            selected++;
            Path document = resolveUnderRoot(inputRoot, previous.relativePath());
            if (!Files.isRegularFile(document)) {
                sourceMissing++;
                classificationFailed++;
                mergedRows.add(previous);
                replayRows.add(new ReplayRow(
                        previous.relativePath(), previous.fileName(), previous.classification(),
                        "REPLAY_SOURCE_MISSING", "", 0, "", "java.nio.file.NoSuchFileException",
                        document.toString()));
                continue;
            }

            var classification = LegacyWordDirectoryClassifier.classify(inputRoot, document);
            String newClassification = externalClassification(classification.outcome());
            String issueCodes = String.join("|", classification.issueCodes());
            var merged = new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                    normalize(Path.of(classification.relativePath())),
                    document.getFileName().toString(),
                    newClassification,
                    classification.parserStatus(),
                    classification.columnCount(),
                    issueCodes,
                    classification.errorClass(),
                    classification.errorMessage());
            mergedRows.add(merged);
            replayRows.add(new ReplayRow(
                    previous.relativePath(), previous.fileName(), previous.classification(),
                    newClassification, classification.parserStatus(), classification.columnCount(), issueCodes,
                    classification.errorClass(), classification.errorMessage()));

            switch (classification.outcome()) {
                case SECURITY_SKIPPED -> securitySkipped++;
                case CLASSIFICATION_FAILED -> classificationFailed++;
                case TABLE_SPEC, NOT_TABLE_SPEC -> unexpectedOutcome++;
            }
        }

        Assertions.assertTrue(selected > 0,
                "v7 summary contains no CLASSIFICATION_FAILED rows; targeted v8 replay has nothing to validate");

        Path replaySummary = writeReplaySummary(
                outputRoot.resolve("legacy-directory-problem-replay.csv"), replayRows);
        Path mergedSummary = ExternalGenerationAcceptanceSupport.writeLegacyDirectoryDocumentSummary(
                outputRoot, "legacy-directory-document-summary-v8.csv", mergedRows);
        Path totals = writeReplayTotals(
                outputRoot.resolve("legacy-directory-problem-replay-totals.csv"),
                previousSummary, selected, securitySkipped, classificationFailed, unexpectedOutcome, sourceMissing);

        System.out.println("[E2E] Legacy v8 targeted replay source : " + previousSummary);
        System.out.println("[E2E] Legacy v8 targeted replay rows   : " + selected);
        System.out.println("[E2E] Legacy v8 security-policy skips : " + securitySkipped);
        System.out.println("[E2E] Legacy v8 classification failed : " + classificationFailed);
        System.out.println("[E2E] Legacy v8 unexpected outcomes   : " + unexpectedOutcome);
        System.out.println("[E2E] Legacy v8 missing sources       : " + sourceMissing);
        System.out.println("[E2E] Legacy v8 replay summary        : " + replaySummary);
        System.out.println("[E2E] Legacy v8 merged summary        : " + mergedSummary);
        System.out.println("[E2E] Legacy v8 totals                : " + totals);

        Assertions.assertEquals(0, sourceMissing,
                "Every v7 CLASSIFICATION_FAILED row must resolve to its original Legacy Word source");
        Assertions.assertEquals(0, classificationFailed,
                "v8 targeted replay must eliminate prior classification failures");
        Assertions.assertEquals(0, unexpectedOutcome,
                "v8 POI security-policy replay should only reclassify the prior failures as security-policy skips");
        Assertions.assertEquals(selected, securitySkipped,
                "Every selected v7 CLASSIFICATION_FAILED document must become SKIPPED_SECURITY_POLICY");
    }

    private static String externalClassification(LegacyWordDirectoryClassifier.Outcome outcome) {
        return switch (outcome) {
            case TABLE_SPEC -> "TABLE_SPEC";
            case NOT_TABLE_SPEC -> "SKIPPED_NOT_TABLE_SPEC";
            case SECURITY_SKIPPED -> "SKIPPED_SECURITY_POLICY";
            case CLASSIFICATION_FAILED -> "CLASSIFICATION_FAILED";
        };
    }

    private static Path resolveUnderRoot(Path inputRoot, String relativePath) {
        String normalizedText = relativePath == null ? "" : relativePath.replace('/', File.separatorChar);
        Path root = inputRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(normalizedText).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Replay relative path escapes Legacy input root: " + relativePath);
        }
        return resolved;
    }

    private static List<ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow> readDocumentSummary(Path csv)
            throws IOException {
        List<List<String>> records = parseCsv(Files.readString(csv, StandardCharsets.UTF_8));
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> header = records.get(0);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') {
                name = name.substring(1);
            }
            index.put(name, i);
        }
        for (String required : List.of(
                "relativePath", "fileName", "classification", "parserStatus", "columnCount",
                "issueCodes", "errorClass", "errorMessage")) {
            if (!index.containsKey(required)) {
                throw new IllegalArgumentException("Missing required column in v7 replay summary: " + required);
            }
        }

        List<ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (record.size() == 1 && record.get(0).isBlank()) {
                continue;
            }
            rows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                    value(record, index, "relativePath"),
                    value(record, index, "fileName"),
                    value(record, index, "classification"),
                    value(record, index, "parserStatus"),
                    parseInt(value(record, index, "columnCount")),
                    value(record, index, "issueCodes"),
                    value(record, index, "errorClass"),
                    value(record, index, "errorMessage")));
        }
        return rows;
    }

    private static String value(List<String> row, Map<String, Integer> index, String column) {
        int position = index.get(column);
        return position < row.size() ? row.get(position) : "";
    }

    private static int parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Integer.parseInt(raw.trim());
    }

    /** Small RFC-4180 reader sufficient for SchemaForge summaries, including quoted newlines. */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && field.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted field in CSV replay summary");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static Path writeReplaySummary(Path output, List<ReplayRow> rows) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        StringBuilder csv = new StringBuilder();
        csv.append("relativePath,fileName,previousClassification,newClassification,parserStatus,columnCount,")
                .append("issueCodes,errorClass,errorMessage\n");
        for (ReplayRow row : rows) {
            csv.append(csv(row.relativePath())).append(',')
                    .append(csv(row.fileName())).append(',')
                    .append(csv(row.previousClassification())).append(',')
                    .append(csv(row.newClassification())).append(',')
                    .append(csv(row.parserStatus())).append(',')
                    .append(row.columnCount()).append(',')
                    .append(csv(row.issueCodes())).append(',')
                    .append(csv(row.errorClass())).append(',')
                    .append(csv(row.errorMessage())).append('\n');
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
        return output;
    }

    private static Path writeReplayTotals(
            Path output,
            Path previousSummary,
            int selected,
            int securitySkipped,
            int classificationFailed,
            int unexpectedOutcome,
            int sourceMissing) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("sourceSummary,selectedPriorFailures,securityPolicySkipped,classificationFailed,")
                .append("unexpectedOutcome,sourceMissing\n")
                .append(csv(previousSummary.toString())).append(',')
                .append(selected).append(',')
                .append(securitySkipped).append(',')
                .append(classificationFailed).append(',')
                .append(unexpectedOutcome).append(',')
                .append(sourceMissing).append('\n');
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
        return output;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record ReplayRow(
            String relativePath,
            String fileName,
            String previousClassification,
            String newClassification,
            String parserStatus,
            int columnCount,
            String issueCodes,
            String errorClass,
            String errorMessage) {
    }
}
