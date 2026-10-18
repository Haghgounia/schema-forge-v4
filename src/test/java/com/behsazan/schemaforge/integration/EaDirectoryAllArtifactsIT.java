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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit release-confidence runner for a directory of real Enterprise Architect XML/XMI files.
 *
 * <p>Every EA input is attempted even when earlier files are PARTIAL_SUCCESS or otherwise fail validation.
 * A single aggregate assertion is raised only after the complete directory has been processed.</p>
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
class EaDirectoryAllArtifactsIT {
    private static final String INPUT_DIR = "schemaforge.ea.e2e.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.ea.e2e.outputDir";
    private static final String SCHEMA = "schemaforge.ea.e2e.schema";
    private static final String PRIMARY_KEY_AS_IDENTITY = "schemaforge.ea.e2e.primaryKeyAsIdentity";

    @Autowired
    private SchemaForgeApiService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesEveryEaFileWithConfiguredReleaseOptions() throws Exception {
        Path inputRoot = ExternalGenerationAcceptanceSupport.requiredDirectory(INPUT_DIR);
        Path outputRoot = ExternalGenerationAcceptanceSupport.configuredOutputDirectory(
                OUTPUT_DIR, inputRoot, "schemaforge-ea-e2e-output");
        String schema = ExternalGenerationAcceptanceSupport.trimToNull(System.getProperty(SCHEMA));
        boolean primaryKeyAsIdentity = Boolean.parseBoolean(System.getProperty(PRIMARY_KEY_AS_IDENTITY, "false"));
        List<String> platforms = ExternalGenerationAcceptanceSupport.configuredPlatforms();
        String auditFields = ExternalGenerationAcceptanceSupport.configuredAuditFields();
        String timestampSemantics = ExternalGenerationAcceptanceSupport.configuredTimestampSemantics();
        boolean failOnErrors = ExternalGenerationAcceptanceSupport.failOnErrors();

        List<Path> eaFiles;
        try (var paths = Files.walk(inputRoot)) {
            eaFiles = paths.filter(Files::isRegularFile)
                    .filter(EaDirectoryAllArtifactsIT::isEaFile)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> inputRoot.relativize(path).toString()))
                    .toList();
        }
        assertFalse(eaFiles.isEmpty(), "EA corpus contains no .xml or .xmi files: " + inputRoot);

        List<ExternalGenerationAcceptanceSupport.RunSummaryRow> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<Path> claimedOutputs = new HashSet<>();
        int attempted = 0;

        for (Path input : eaFiles) {
            attempted++;
            String label = "EA " + inputRoot.relativize(input);
            Path output = null;
            try {
                output = ExternalGenerationAcceptanceSupport.stableEaOutputPath(outputRoot, inputRoot, input);
                Path normalizedOutput = output.toAbsolutePath().normalize();
                assertTrue(claimedOutputs.add(normalizedOutput),
                        () -> "EA output-name collision detected for " + input + ": " + normalizedOutput);
                MockMultipartFile upload = new MockMultipartFile(
                        "file", input.getFileName().toString(), "application/xml", Files.readAllBytes(input));
                SchemaForgeController controller = new SchemaForgeController(service);
                ResponseEntity<byte[]> response = controller.eaXml(
                        upload, schema, platforms, auditFields, timestampSemantics, null, null, primaryKeyAsIdentity);
                byte[] archive = requireArchive(response, input);
                Files.write(output, archive);

                Map<String, byte[]> entries = ExternalGenerationAcceptanceSupport.unzip(archive);
                JsonNode manifest = ExternalGenerationAcceptanceSupport.manifest(objectMapper, entries);
                rows.add(ExternalGenerationAcceptanceSupport.summaryRow(
                        label, input, output, manifest, ExternalGenerationAcceptanceSupport.blockingOutcomeSummary(manifest)));

                ExternalGenerationAcceptanceSupport.assertOrigin(manifest, "ENTERPRISE_ARCHITECT");
                ExternalGenerationAcceptanceSupport.assertConfiguredGenerationOptions(
                        manifest, platforms, auditFields, timestampSemantics);
                ExternalGenerationAcceptanceSupport.assertManifestMatchesArchive(entries, manifest);
                ExternalGenerationAcceptanceSupport.assertEaIdentityPolicy(
                        objectMapper, entries, primaryKeyAsIdentity);

                if (schema != null) {
                    for (JsonNode model : manifest.path("models")) {
                        assertEquals(schema, model.path("schema").asText(),
                                input + " schema override must propagate to every model");
                    }
                }
                assertFalse(ExternalGenerationAcceptanceSupport.canonicalJsonEntries(entries).isEmpty(),
                        input + " must generate canonical schema JSON artifacts");
                ExternalGenerationAcceptanceSupport.assertRequestStatus(manifest, failOnErrors, label);
                System.out.println("[E2E] EA output: " + output);
            } catch (Exception | AssertionError failure) {
                failures.add(label + " - " + ExternalGenerationAcceptanceSupport.conciseFailure(failure));
                ExternalGenerationAcceptanceSupport.recordFailure(rows, label, input, output, failure);
            }
        }

        Path summary = ExternalGenerationAcceptanceSupport.writeSummaryCsv(
                outputRoot, "ea-release-confidence-summary.csv", rows);
        System.out.println("[E2E] EA release-confidence summary: " + summary);

        assertEquals(eaFiles.size(), attempted, "Every EA input file must be attempted");
        assertTrue(attempted > 0);
        if (ExternalGenerationAcceptanceSupport.shouldFailGate(failOnErrors, failures)) {
            Assertions.fail(ExternalGenerationAcceptanceSupport.combinedFailureMessage(
                    "EA release-confidence gate failed after processing the complete EA directory", summary, failures));
        }
        if (!failures.isEmpty()) {
            System.out.println("[E2E] EA observation completed with " + failures.size()
                    + " recorded failure(s); failOnErrors=false keeps the Maven gate green. See: " + summary);
        }
    }

    private static boolean isEaFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".xmi");
    }

    private static byte[] requireArchive(ResponseEntity<byte[]> response, Path input) {
        assertEquals(200, response.getStatusCode().value(), input.toString());
        byte[] archive = response.getBody();
        assertNotNull(archive, input + " response body");
        assertFalse(archive.length == 0, input + " response archive must not be empty");
        return archive;
    }
}
