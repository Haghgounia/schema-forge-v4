package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.SchemaForgeApiApplication;
import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.api.SchemaForgeController;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Explicit release-confidence runner for real Standard Word, Legacy Word and Legacy RTL Word ZIP corpora.
 *
 * <p>All three corpora are attempted before the test fails. This intentionally differs from a fail-fast unit test:
 * the release gate must expose the complete external corpus state in one run.</p>
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
    private static final String LEGACY_ZIP = "schemaforge.e2e.legacyZip";
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
        runLegacy("Legacy Word ZIP", "legacy-word-", LEGACY_ZIP, LEGACY_SCHEMA, null,
                outputRoot, platforms, auditFields, timestampSemantics, failOnErrors, rows, failures);
        runLegacy("Legacy RTL Word ZIP", "legacy-rtl-word-", LEGACY_RTL_ZIP, LEGACY_RTL_SCHEMA, LEGACY_SCHEMA,
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

    private void runLegacy(
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
            String schema = ExternalGenerationAcceptanceSupport.trimToNull(System.getProperty(schemaProperty));
            if (schema == null && fallbackSchemaProperty != null) {
                schema = ExternalGenerationAcceptanceSupport.trimToNull(System.getProperty(fallbackSchemaProperty));
            }
            if (schema == null) {
                throw new IllegalArgumentException("Required system property is missing: -D" + schemaProperty
                        + "=<schema>" + (fallbackSchemaProperty == null ? "" : " (or -D" + fallbackSchemaProperty + "=<schema>)"));
            }
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

    private static byte[] requireArchive(ResponseEntity<byte[]> response, String label) {
        assertEquals(200, response.getStatusCode().value(), label);
        byte[] archive = response.getBody();
        assertNotNull(archive, label + " response body");
        assertFalse(archive.length == 0, label + " response archive must not be empty");
        return archive;
    }
}
