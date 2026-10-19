package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.SchemaForgeApiApplication;
import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.api.SchemaForgeController;
import com.behsazan.schemaforge.application.BatchArchiveSupport;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordDirectoryClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Explicit release-confidence runner for the real Word corpora:
 * Standard Word ZIP, recursive Legacy Word directory, and Legacy RTL Word ZIP.
 *
 * <p>The recursive Legacy directory is content-classified with the production legacy parser
 * before generation. A Word file is not assumed to be a table specification merely because it
 * has a .doc/.docx extension. Parser-ignored documents are reported as SKIPPED_NOT_TABLE_SPEC
 * and are not submitted to generation. Documents rejected by Apache POI's explicit ZIP-bomb
 * inflate-ratio guard are reported as SKIPPED_SECURITY_POLICY rather than as table-classification
 * failures; the harness never weakens or overrides that security guard. Accepted table specifications are packed with their
 * relative directory paths intact and sent through the strict LEGACY_WORD batch pipeline.</p>
 */
@SpringBootTest(
        classes = SchemaForgeApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "schemaforge.metadata.oracle.enabled=false",
                "schemaforge.metadata.postgresql.enabled=false",
                "schemaforge.metadata.db2zos.enabled=false",
                "schemaforge.metadata.db2luw.enabled=false",
                "schemaforge.metadata.sqlserver.enabled=false",
                "schemaforge.metadata.mysql.enabled=false",
                "schemaforge.metadata.mariadb.enabled=false",
                "schemaforge.spell-check.enabled=false"
        })
class ExternalZipBatchEndToEndIT {
    private static final String STANDARD_ZIP = "schemaforge.e2e.standardZip";
    private static final String LEGACY_DIR = "schemaforge.e2e.legacyDir";
    private static final String LEGACY_RTL_ZIP = "schemaforge.e2e.legacyRtlZip";
    private static final String LEGACY_SCHEMA = "schemaforge.e2e.legacySchema";
    private static final String LEGACY_RTL_SCHEMA = "schemaforge.e2e.legacyRtlSchema";
    private static final String OUTPUT_DIR = "schemaforge.e2e.outputDir";

    @Autowired
    private SchemaForgeApiService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesAllRealWordCorporaWithConfiguredReleaseOptions() throws Exception {
        Path outputRoot = ExternalGenerationAcceptanceSupport.configuredOutputDirectory(
                OUTPUT_DIR, Path.of(".").toAbsolutePath().normalize(), "schemaforge-e2e-output");
        List<String> platforms = ExternalGenerationAcceptanceSupport.configuredPlatforms();
        String auditFields = ExternalGenerationAcceptanceSupport.configuredAuditFields();
        String timestampSemantics = ExternalGenerationAcceptanceSupport.configuredTimestampSemantics();
        boolean failOnErrors = ExternalGenerationAcceptanceSupport.failOnErrors();

        List<ExternalGenerationAcceptanceSupport.RunSummaryRow> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        runStandard(STANDARD_ZIP, outputRoot, platforms, auditFields, timestampSemantics, failOnErrors, rows, failures);
        runLegacyDirectory(outputRoot, platforms, auditFields, timestampSemantics, failOnErrors, rows, failures);
        runLegacyZip("Legacy RTL Word ZIP", "legacy-rtl-word-", LEGACY_RTL_ZIP, LEGACY_RTL_SCHEMA, LEGACY_SCHEMA,
                outputRoot, platforms, auditFields, timestampSemantics, failOnErrors, rows, failures);

        Path summary = ExternalGenerationAcceptanceSupport.writeSummaryCsv(
                outputRoot, "word-release-confidence-summary.csv", rows);
        System.out.println("[E2E] Word release-confidence summary: " + summary);

        if (ExternalGenerationAcceptanceSupport.shouldFailGate(failOnErrors, failures)) {
            Assertions.fail(ExternalGenerationAcceptanceSupport.combinedFailureMessage(
                    "Word release-confidence gate failed after processing all three corpora", summary, failures));
        }
        if (!failures.isEmpty()) {
            System.out.println("[E2E] Word observation completed with " + failures.size()
                    + " recorded failure(s); failOnErrors=false keeps the Maven gate green. See: " + summary);
        }
    }

    private void runStandard(
            String inputProperty,
            Path outputRoot,
            List<String> platforms,
            String auditFields,
            String timestampSemantics,
            boolean failOnErrors,
            List<ExternalGenerationAcceptanceSupport.RunSummaryRow> rows,
            List<String> failures) {
        String label = "Standard Word ZIP";
        Path input = ExternalGenerationAcceptanceSupport.configuredPathForSummary(inputProperty);
        Path output = null;
        try {
            input = ExternalGenerationAcceptanceSupport.requiredFile(inputProperty);
            MockMultipartFile upload = new MockMultipartFile(
                    "file", input.getFileName().toString(), "application/zip", Files.readAllBytes(input));
            SchemaForgeController controller = new SchemaForgeController(service);
            ResponseEntity<byte[]> response = controller.zip(
                    upload, platforms, auditFields, timestampSemantics, null, null);
            byte[] archive = requireArchive(response, label);
            output = outputRoot.resolve("standard-word-" + input.getFileName());
            Files.write(output, archive);

            Map<String, byte[]> entries = ExternalGenerationAcceptanceSupport.unzip(archive);
            JsonNode manifest = ExternalGenerationAcceptanceSupport.manifest(objectMapper, entries);
            rows.add(ExternalGenerationAcceptanceSupport.summaryRow(
                    label, input, output, manifest, ExternalGenerationAcceptanceSupport.blockingOutcomeSummary(manifest)));

            ExternalGenerationAcceptanceSupport.assertOrigin(manifest, "ZIP_BATCH");
            ExternalGenerationAcceptanceSupport.assertConfiguredGenerationOptions(
                    manifest, platforms, auditFields, timestampSemantics);
            ExternalGenerationAcceptanceSupport.assertManifestMatchesArchive(entries, manifest);
            assertFalse(ExternalGenerationAcceptanceSupport.canonicalJsonEntries(entries).isEmpty(),
                    "Standard Word ZIP must generate canonical schema JSON artifacts");
            ExternalGenerationAcceptanceSupport.assertRequestStatus(manifest, failOnErrors, label);
            System.out.println("[E2E] Standard Word ZIP output: " + output);
        } catch (Exception | AssertionError failure) {
            failures.add(label + " - " + ExternalGenerationAcceptanceSupport.conciseFailure(failure));
            ExternalGenerationAcceptanceSupport.recordFailure(rows, label, input, output, failure);
        }
    }

    private void runLegacyDirectory(
            Path outputRoot,
            List<String> platforms,
            String auditFields,
            String timestampSemantics,
            boolean failOnErrors,
            List<ExternalGenerationAcceptanceSupport.RunSummaryRow> rows,
            List<String> failures) {
        String label = "Legacy Word Directory";
        Path input = ExternalGenerationAcceptanceSupport.configuredPathForSummary(LEGACY_DIR);
        Path output = null;
        try {
            input = ExternalGenerationAcceptanceSupport.requiredDirectory(LEGACY_DIR);
            String schema = requiredSchema(LEGACY_SCHEMA, null);
            List<Path> documents = findLegacyWordDocuments(input);
            List<Path> tableDocuments = new ArrayList<>();
            List<ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow> documentRows = new ArrayList<>();
            long nonTable = 0;
            long inputFiltered = 0;
            long securitySkipped = 0;
            long classificationFailed = 0;

            for (Path document : documents) {
                Path relative = input.relativize(document);
                String preFilterReason = BatchArchiveSupport.skippedLegacyWordInputReason(relative);
                if (preFilterReason != null) {
                    inputFiltered++;
                    documentRows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                            normalize(relative), document.getFileName().toString(), "SKIPPED_INPUT_FILTER", "",
                            0, "", "", preFilterReason));
                    continue;
                }

                var classification = LegacyWordDirectoryClassifier.classify(input, document);
                String issueCodes = String.join("|", classification.issueCodes());
                switch (classification.outcome()) {
                    case TABLE_SPEC -> {
                        tableDocuments.add(document);
                        documentRows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                                normalize(relative), document.getFileName().toString(), "TABLE_SPEC",
                                classification.parserStatus(), classification.columnCount(), issueCodes,
                                classification.errorClass(), classification.errorMessage()));
                    }
                    case NOT_TABLE_SPEC -> {
                        nonTable++;
                        documentRows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                                normalize(relative), document.getFileName().toString(), "SKIPPED_NOT_TABLE_SPEC",
                                classification.parserStatus(), classification.columnCount(), issueCodes,
                                classification.errorClass(), classification.errorMessage()));
                    }
                    case SECURITY_SKIPPED -> {
                        securitySkipped++;
                        documentRows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                                normalize(relative), document.getFileName().toString(), "SKIPPED_SECURITY_POLICY",
                                classification.parserStatus(), classification.columnCount(), issueCodes,
                                classification.errorClass(), classification.errorMessage()));
                    }
                    case CLASSIFICATION_FAILED -> {
                        classificationFailed++;
                        String failureDetail = normalize(relative) + " - legacy classification failed: "
                                + classification.errorClass() + ": " + classification.errorMessage();
                        failures.add(label + " - " + failureDetail);
                        documentRows.add(new ExternalGenerationAcceptanceSupport.LegacyDirectoryDocumentRow(
                                normalize(relative), document.getFileName().toString(), "CLASSIFICATION_FAILED",
                                classification.parserStatus(), classification.columnCount(), issueCodes,
                                classification.errorClass(), classification.errorMessage()));
                    }
                }
            }

            Path documentSummary = ExternalGenerationAcceptanceSupport.writeLegacyDirectoryDocumentSummary(
                    outputRoot, "legacy-directory-document-summary.csv", documentRows);
            System.out.println("[E2E] Legacy directory document summary: " + documentSummary);

            if (tableDocuments.isEmpty()) {
                throw new IllegalStateException("No Legacy Word table specifications were accepted from directory: " + input);
            }

            Path filteredZip = ExternalGenerationAcceptanceSupport.createLegacyDirectoryTableSpecZip(
                    input, outputRoot.resolve("legacy-directory-table-specs-input.zip"), tableDocuments);
            MockMultipartFile upload = new MockMultipartFile(
                    "file", filteredZip.getFileName().toString(), "application/zip", Files.readAllBytes(filteredZip));
            SchemaForgeController controller = new SchemaForgeController(service);
            ResponseEntity<byte[]> response = controller.legacyWordZip(
                    upload, schema, platforms, auditFields, timestampSemantics, null, null);
            byte[] archive = requireArchive(response, label);
            output = outputRoot.resolve("legacy-word-directory-result.zip");
            Files.write(output, archive);

            Map<String, byte[]> entries = ExternalGenerationAcceptanceSupport.unzip(archive);
            JsonNode manifest = ExternalGenerationAcceptanceSupport.manifest(objectMapper, entries);
            ExternalGenerationAcceptanceSupport.LegacyDirectoryCounts counts =
                    new ExternalGenerationAcceptanceSupport.LegacyDirectoryCounts(
                            documents.size(), tableDocuments.size(), nonTable, inputFiltered,
                            securitySkipped, classificationFailed);
            String detail = ExternalGenerationAcceptanceSupport.blockingOutcomeSummary(manifest);
            String classificationDetail = "directoryClassification: tableSpecs=" + tableDocuments.size()
                    + ", nonTable=" + nonTable
                    + ", inputFiltered=" + inputFiltered
                    + ", securitySkipped=" + securitySkipped
                    + ", classificationFailed=" + classificationFailed;
            rows.add(ExternalGenerationAcceptanceSupport.legacyDirectorySummaryRow(
                    label, input, output, manifest, counts,
                    detail.isBlank() ? classificationDetail : classificationDetail + " | " + detail));

            ExternalGenerationAcceptanceSupport.assertOrigin(manifest, "LEGACY_WORD_BATCH");
            ExternalGenerationAcceptanceSupport.assertConfiguredGenerationOptions(
                    manifest, platforms, auditFields, timestampSemantics);
            ExternalGenerationAcceptanceSupport.assertManifestMatchesArchive(entries, manifest);
            assertEquals("LEGACY_WORD", manifest.path("extensions").path("batchInput").path("inputMode").asText());
            assertEquals(schema, manifest.path("extensions").path("generationOptions").path("legacyWordSchema").asText());
            assertEquals(tableDocuments.size(),
                    manifest.path("extensions").path("batchInput").path("regularFileCount").asLong(),
                    "Only content-classified table specifications must enter the production Legacy batch");
            assertFalse(ExternalGenerationAcceptanceSupport.canonicalJsonEntries(entries).isEmpty(),
                    label + " must generate canonical schema JSON artifacts");
            for (JsonNode model : manifest.path("models")) {
                assertEquals(schema, model.path("schema").asText(), label + " schema must propagate to every model");
            }
            ExternalGenerationAcceptanceSupport.assertRequestStatus(manifest, failOnErrors, label);
            System.out.println("[E2E] Legacy Word Directory output: " + output);
        } catch (Exception | AssertionError failure) {
            failures.add(label + " - " + ExternalGenerationAcceptanceSupport.conciseFailure(failure));
            ExternalGenerationAcceptanceSupport.recordFailure(rows, label, input, output, failure);
        }
    }

    private void runLegacyZip(
            String label,
            String outputPrefix,
            String inputProperty,
            String schemaProperty,
            String fallbackSchemaProperty,
            Path outputRoot,
            List<String> platforms,
            String auditFields,
            String timestampSemantics,
            boolean failOnErrors,
            List<ExternalGenerationAcceptanceSupport.RunSummaryRow> rows,
            List<String> failures) {
        Path input = ExternalGenerationAcceptanceSupport.configuredPathForSummary(inputProperty);
        Path output = null;
        try {
            input = ExternalGenerationAcceptanceSupport.requiredFile(inputProperty);
            String schema = requiredSchema(schemaProperty, fallbackSchemaProperty);
            MockMultipartFile upload = new MockMultipartFile(
                    "file", input.getFileName().toString(), "application/zip", Files.readAllBytes(input));
            SchemaForgeController controller = new SchemaForgeController(service);
            ResponseEntity<byte[]> response = controller.legacyWordZip(
                    upload, schema, platforms, auditFields, timestampSemantics, null, null);
            byte[] archive = requireArchive(response, label);
            output = outputRoot.resolve(outputPrefix + input.getFileName());
            Files.write(output, archive);

            Map<String, byte[]> entries = ExternalGenerationAcceptanceSupport.unzip(archive);
            JsonNode manifest = ExternalGenerationAcceptanceSupport.manifest(objectMapper, entries);
            rows.add(ExternalGenerationAcceptanceSupport.summaryRow(
                    label, input, output, manifest, ExternalGenerationAcceptanceSupport.blockingOutcomeSummary(manifest)));

            ExternalGenerationAcceptanceSupport.assertOrigin(manifest, "LEGACY_WORD_BATCH");
            ExternalGenerationAcceptanceSupport.assertConfiguredGenerationOptions(
                    manifest, platforms, auditFields, timestampSemantics);
            ExternalGenerationAcceptanceSupport.assertManifestMatchesArchive(entries, manifest);
            assertEquals("LEGACY_WORD", manifest.path("extensions").path("batchInput").path("inputMode").asText());
            assertEquals(schema, manifest.path("extensions").path("generationOptions").path("legacyWordSchema").asText());
            assertFalse(ExternalGenerationAcceptanceSupport.canonicalJsonEntries(entries).isEmpty(),
                    label + " must generate canonical schema JSON artifacts");
            for (JsonNode model : manifest.path("models")) {
                assertEquals(schema, model.path("schema").asText(), label + " schema must propagate to every model");
            }
            ExternalGenerationAcceptanceSupport.assertRequestStatus(manifest, failOnErrors, label);
            System.out.println("[E2E] " + label + " output: " + output);
        } catch (Exception | AssertionError failure) {
            failures.add(label + " - " + ExternalGenerationAcceptanceSupport.conciseFailure(failure));
            ExternalGenerationAcceptanceSupport.recordFailure(rows, label, input, output, failure);
        }
    }

    private static List<Path> findLegacyWordDocuments(Path inputRoot) throws Exception {
        try (var stream = Files.walk(inputRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".doc") || name.endsWith(".docx");
                    })
                    .sorted(Comparator.comparing(
                            path -> normalize(inputRoot.relativize(path)), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static String requiredSchema(String schemaProperty, String fallbackSchemaProperty) {
        String schema = ExternalGenerationAcceptanceSupport.trimToNull(System.getProperty(schemaProperty));
        if (schema == null && fallbackSchemaProperty != null) {
            schema = ExternalGenerationAcceptanceSupport.trimToNull(System.getProperty(fallbackSchemaProperty));
        }
        if (schema == null) {
            throw new IllegalArgumentException("Required system property is missing: -D" + schemaProperty
                    + "=<schema>" + (fallbackSchemaProperty == null ? "" : " (or -D" + fallbackSchemaProperty + "=<schema>)"));
        }
        return schema;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static byte[] requireArchive(ResponseEntity<byte[]> response, String label) {
        assertEquals(200, response.getStatusCode().value(), label);
        byte[] archive = response.getBody();
        assertNotNull(archive, label + " response body");
        assertFalse(archive.length == 0, label + " response archive must not be empty");
        return archive;
    }
}
