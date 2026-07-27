package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Phase1 Pipeline.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 *
 * @deprecated Replaced by service-level and REST regression tests.
 * @since 4.1
 */
@Deprecated(since = "4.1", forRemoval = true)
class Phase1PipelineTest {

    @Test
    void shouldParseNormalizeValidateAndExportWordDocument() throws Exception {
        Path input = TestSamplePaths.PROVINCES_V1_1;

        Path output = new OutputFileNamer().create(
                Path.of("target/test-output"),
                input.getFileName().toString(),
                "json");

        Files.createDirectories(output.getParent());
        Files.deleteIfExists(output);

        DatabaseSchema parsedSchema;

        try (InputStream inputStream = Files.newInputStream(input)) {
            SpecificationSource source = new SpecificationSource(
                    input.getFileName().toString(),
                    inputStream
            );

            parsedSchema = new WordSpecificationParser().parse(source);
        }

        assertNotNull(parsedSchema);

        assertFalse(
                parsedSchema.tables().isEmpty(),
                "No table was extracted from the Word document"
        );

        String recoveryWarnings = parsedSchema.metadata().get("recovery.warnings");
        assertNotNull(recoveryWarnings, "Recovery warnings metadata was not created");
        assertTrue(
                recoveryWarnings.contains("DUPLICATE_COLUMN|name=IS_ACTIVE"),
                "Duplicate IS_ACTIVE definition was not reported"
        );
        assertEquals(
                1,
                parsedSchema.tables().getFirst().columns().stream()
                        .filter(column -> column.name().normalized().equals("IS_ACTIVE"))
                        .count(),
                "Only the first duplicate column definition must remain executable"
        );

        assertTrue(
                parsedSchema.tables().getFirst().columns().stream()
                        .anyMatch(column -> column.name().normalized().equals("POPULATION")),
                "A non-empty row with missing data type must remain in the parsed model"
        );
        assertTrue(
                parsedSchema.tables().getFirst().columns().stream()
                        .anyMatch(column -> column.name().normalized().equals("HOUSEHOLD")),
                "A non-empty row with missing Persian description must remain in the parsed model"
        );
        assertTrue(
                recoveryWarnings.contains("COLUMN_DATATYPE_MISSING|name=POPULATION"),
                "Missing datatype warning was not reported for POPULATION"
        );
        assertTrue(
                recoveryWarnings.contains("COLUMN_DESCRIPTION_MISSING|name=HOUSEHOLD"),
                "Missing description warning was not reported for HOUSEHOLD"
        );

        DatabaseSchema normalizedSchema =
                new SpecificationNormalizer().normalize(parsedSchema);

        assertNotNull(normalizedSchema);

        ValidationReport report =
                new SpecificationValidator().validate(normalizedSchema);

        assertNotNull(report);

        assertTrue(
                report.valid(),
                () -> "Validation issues: " + report.issues()
        );

        new JsonExporter().write(
                output,
                normalizedSchema,
                report
        );

        assertTrue(
                Files.isRegularFile(output),
                "JSON output file was not created"
        );

        assertTrue(
                Files.size(output) > 0,
                "JSON output file is empty"
        );

        JsonNode json =
                new ObjectMapper().readTree(output.toFile());

        assertTrue(
                json.has("source"),
                "JSON does not contain source information"
        );

        assertTrue(
                json.has("schema"),
                "JSON does not contain schema information"
        );

        assertTrue(
                json.has("validation"),
                "JSON does not contain validation information"
        );

        assertTrue(
                json.path("schema").path("tables").isArray(),
                "JSON schema.tables is not an array"
        );

        assertFalse(
                json.path("schema").path("tables").isEmpty(),
                "JSON schema.tables is empty"
        );

        System.out.println(
                "Test JSON created: " + output.toAbsolutePath()
        );
    }
}
