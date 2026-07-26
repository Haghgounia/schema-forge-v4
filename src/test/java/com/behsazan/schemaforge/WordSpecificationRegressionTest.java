package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the behavior and regression expectations of Word Specification Regression.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class WordSpecificationRegressionTest {

    private static final Path INPUT_DIRECTORY =
            Path.of("src/test/resources/samples");

    private static final Path OUTPUT_DIRECTORY =
            Path.of("target/test-output");

    private static final Path SUMMARY_DIRECTORY =
            Path.of("target/test-reports");

    private final OutputFileNamer outputFileNamer =
            new OutputFileNamer();

    private final WordSpecificationParser parser =
            new WordSpecificationParser();

    private final SchemaPreparationService preparationService =
            new SchemaPreparationService();

    private final JsonExporter jsonExporter =
            new JsonExporter();

    private final DdlGenerator ddlGenerator =
            new DdlGenerator(new OracleDialect());

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    void shouldProcessAllWordSpecifications() throws Exception {
        assertFalse(
                Files.notExists(INPUT_DIRECTORY),
                "Input directory does not exist: "
                        + INPUT_DIRECTORY.toAbsolutePath()
        );

        List<Path> inputFiles = findWordDocuments();

        assertFalse(
                inputFiles.isEmpty(),
                "No Word documents were found in: "
                        + INPUT_DIRECTORY.toAbsolutePath()
        );

        Files.createDirectories(OUTPUT_DIRECTORY);
        Files.createDirectories(SUMMARY_DIRECTORY);

        List<RegressionResult> results = new ArrayList<>();

        for (Path inputFile : inputFiles) {
            results.add(processDocument(inputFile));
        }

        Path summaryFile = outputFileNamer.create(
                SUMMARY_DIRECTORY,
                "word-regression-summary.csv",
                "csv");
        writeSummary(results, summaryFile);
        printSummary(results, summaryFile);

        List<RegressionResult> failedResults = results.stream()
                .filter(result -> !result.success())
                .toList();

        if (!failedResults.isEmpty()) {
            String failureMessage = buildFailureMessage(
                    failedResults,
                    results.size(),
                    summaryFile
            );

            fail(failureMessage);
        }
    }

    private List<Path> findWordDocuments() throws Exception {
        try (Stream<Path> files = Files.list(INPUT_DIRECTORY)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(this::isWordDocument)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName()
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                    ))
                    .toList();
        }
    }

    private boolean isWordDocument(Path path) {
        return path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".docx");
    }

    private RegressionResult processDocument(Path inputFile) {
        Instant start = Instant.now();
        OutputFileNamer.OutputNames outputNames = outputFileNamer.create(
                OUTPUT_DIRECTORY,
                inputFile.getFileName().toString(),
                DatabasePlatform.ORACLE);
        Path outputFile = outputNames.jsonFile();
        Path sqlOutputFile = outputNames.sqlFile();

        try {
            DatabaseSchema parsedSchema = parse(inputFile);

            if (parsedSchema == null) {
                throw new IllegalStateException(
                        "Parser returned a null schema"
                );
            }

            PreparedSchema prepared = preparationService.prepare(parsedSchema);
            DatabaseSchema normalizedSchema = prepared.schema();
            ValidationReport validationReport = prepared.validationReport();

            if (validationReport == null) {
                throw new IllegalStateException(
                        "Validator returned a null report"
                );
            }

            Files.deleteIfExists(outputFile);

            jsonExporter.write(
                    outputFile,
                    normalizedSchema,
                    validationReport
            );

            verifyJsonOutput(outputFile);

            Files.deleteIfExists(sqlOutputFile);
            Files.writeString(
                    sqlOutputFile,
                    ddlGenerator.generate(normalizedSchema),
                    StandardCharsets.UTF_8
            );
            verifySqlOutput(sqlOutputFile, normalizedSchema);

            int tableCount = normalizedSchema.tables().size();

            int columnCount = normalizedSchema.tables()
                    .stream()
                    .mapToInt(table -> table.columns().size())
                    .sum();

            long elapsedMilliseconds =
                    Duration.between(start, Instant.now()).toMillis();

            if (!validationReport.valid()) {
                return RegressionResult.failure(
                        inputFile,
                        outputFile,
                        tableCount,
                        columnCount,
                        elapsedMilliseconds,
                        "Validation failed: "
                                + validationReport.issues()
                );
            }

            return RegressionResult.success(
                    inputFile,
                    outputFile,
                    tableCount,
                    columnCount,
                    elapsedMilliseconds
            );

        } catch (Exception exception) {
            long elapsedMilliseconds =
                    Duration.between(start, Instant.now()).toMillis();

            return RegressionResult.failure(
                    inputFile,
                    outputFile,
                    0,
                    0,
                    elapsedMilliseconds,
                    extractErrorMessage(exception)
            );
        }
    }

    private DatabaseSchema parse(Path inputFile) throws Exception {
        try (InputStream inputStream =
                     Files.newInputStream(inputFile)) {

            SpecificationSource source =
                    new SpecificationSource(
                            inputFile.getFileName().toString(),
                            inputStream
                    );

            return parser.parse(source);
        }
    }

    private void verifySqlOutput(
            Path outputFile,
            DatabaseSchema schema
    ) throws Exception {
        if (!Files.isRegularFile(outputFile) || Files.size(outputFile) == 0) {
            throw new IllegalStateException("Oracle SQL output was not created or is empty");
        }
        String sql = Files.readString(outputFile, StandardCharsets.UTF_8);
        for (var table : schema.tables()) {
            if (!sql.contains("CREATE TABLE " + table.qualifiedName())) {
                throw new IllegalStateException(
                        "SQL does not contain CREATE TABLE for " + table.qualifiedName()
                );
            }
        }
    }

    private void verifyJsonOutput(Path outputFile) throws Exception {
        if (!Files.isRegularFile(outputFile)) {
            throw new IllegalStateException(
                    "JSON output file was not created"
            );
        }

        if (Files.size(outputFile) == 0) {
            throw new IllegalStateException(
                    "JSON output file is empty"
            );
        }

        JsonNode root = objectMapper.readTree(outputFile.toFile());

        if (root == null || !root.isObject()) {
            throw new IllegalStateException(
                    "JSON root is not an object"
            );
        }

        if (!root.hasNonNull("source")) {
            throw new IllegalStateException(
                    "JSON does not contain source"
            );
        }

        if (!root.hasNonNull("schema")) {
            throw new IllegalStateException(
                    "JSON does not contain schema"
            );
        }

        if (!root.hasNonNull("validation")) {
            throw new IllegalStateException(
                    "JSON does not contain validation"
            );
        }

        JsonNode tables =
                root.path("schema").path("tables");

        if (!tables.isArray()) {
            throw new IllegalStateException(
                    "JSON schema.tables is not an array"
            );
        }

        if (tables.isEmpty()) {
            throw new IllegalStateException(
                    "JSON schema.tables is empty"
            );
        }
    }

    private void writeSummary(
            List<RegressionResult> results,
            Path summaryFile
    ) throws Exception {

        try (BufferedWriter writer = Files.newBufferedWriter(
                summaryFile,
                StandardCharsets.UTF_8
        )) {
            writer.write(
                    "input_file,status,table_count,column_count,"
                            + "elapsed_ms,output_file,error"
            );
            writer.newLine();

            for (RegressionResult result : results) {
                writer.write(toCsvRow(result));
                writer.newLine();
            }
        }
    }

    private String toCsvRow(RegressionResult result) {
        return String.join(
                ",",
                escapeCsv(result.inputFile().toString()),
                escapeCsv(result.success() ? "PASS" : "FAIL"),
                Integer.toString(result.tableCount()),
                Integer.toString(result.columnCount()),
                Long.toString(result.elapsedMilliseconds()),
                escapeCsv(result.outputFile().toString()),
                escapeCsv(result.errorMessage())
        );
    }

    private String escapeCsv(String value) {
        String safeValue = value == null ? "" : value;

        return "\""
                + safeValue.replace("\"", "\"\"")
                + "\"";
    }

    private void printSummary(
            List<RegressionResult> results,
            Path summaryFile
    ) {
        long passed = results.stream()
                .filter(RegressionResult::success)
                .count();

        long failed = results.size() - passed;

        int totalTables = results.stream()
                .mapToInt(RegressionResult::tableCount)
                .sum();

        int totalColumns = results.stream()
                .mapToInt(RegressionResult::columnCount)
                .sum();

        long totalElapsedMilliseconds = results.stream()
                .mapToLong(
                        RegressionResult::elapsedMilliseconds
                )
                .sum();

        System.out.println();
        System.out.println("Word regression test completed");
        System.out.println("Documents : " + results.size());
        System.out.println("Passed    : " + passed);
        System.out.println("Failed    : " + failed);
        System.out.println("Tables    : " + totalTables);
        System.out.println("Columns   : " + totalColumns);
        System.out.println(
                "Elapsed ms: " + totalElapsedMilliseconds
        );
        System.out.println(
                "Summary   : " + summaryFile.toAbsolutePath()
        );
        System.out.println();
    }

    private String buildFailureMessage(
            List<RegressionResult> failedResults,
            int totalCount,
            Path summaryFile
    ) {
        StringBuilder message = new StringBuilder();

        message.append(failedResults.size())
                .append(" of ")
                .append(totalCount)
                .append(" Word documents failed.")
                .append(System.lineSeparator());

        for (RegressionResult result : failedResults) {
            message.append("- ")
                    .append(result.inputFile().getFileName())
                    .append(": ")
                    .append(result.errorMessage())
                    .append(System.lineSeparator());
        }

        message.append("See report: ")
                .append(summaryFile.toAbsolutePath());

        return message.toString();
    }

    private String extractErrorMessage(Exception exception) {
        String message = exception.getMessage();

        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        return exception.getClass().getSimpleName()
                + ": "
                + message.replace(
                System.lineSeparator(),
                " "
        );
    }

    private record RegressionResult(
            Path inputFile,
            Path outputFile,
            boolean success,
            int tableCount,
            int columnCount,
            long elapsedMilliseconds,
            String errorMessage
    ) {

        static RegressionResult success(
                Path inputFile,
                Path outputFile,
                int tableCount,
                int columnCount,
                long elapsedMilliseconds
        ) {
            return new RegressionResult(
                    inputFile,
                    outputFile,
                    true,
                    tableCount,
                    columnCount,
                    elapsedMilliseconds,
                    ""
            );
        }

        static RegressionResult failure(
                Path inputFile,
                Path outputFile,
                int tableCount,
                int columnCount,
                long elapsedMilliseconds,
                String errorMessage
        ) {
            return new RegressionResult(
                    inputFile,
                    outputFile,
                    false,
                    tableCount,
                    columnCount,
                    elapsedMilliseconds,
                    errorMessage
            );
        }
    }
}
