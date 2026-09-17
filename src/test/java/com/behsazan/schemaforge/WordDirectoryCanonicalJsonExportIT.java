package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.SpecificationValidator;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Offline batch utility test for converting standard (non-legacy) SchemaForge Word
 * specifications to canonical JSON. No Spring context or database connection is used.
 *
 * <p>Required property:</p>
 * <pre>
 * -Dschemaforge.wordJson.inputDir=D:\\path\\to\\word-files
 * </pre>
 *
 * <p>Optional properties:</p>
 * <pre>
 * -Dschemaforge.wordJson.outputDir=D:\\path\\to\\json-output
 * -Dschemaforge.wordJson.recursive=true
 * </pre>
 */
class WordDirectoryCanonicalJsonExportIT {

    private static final String INPUT_DIR_PROPERTY = "schemaforge.wordJson.inputDir";
    private static final String OUTPUT_DIR_PROPERTY = "schemaforge.wordJson.outputDir";
    private static final String RECURSIVE_PROPERTY = "schemaforge.wordJson.recursive";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    @Test
    void exportsAllStandardWordDocumentsToCanonicalJson() throws Exception {
        Path inputDir = requiredDirectory(INPUT_DIR_PROPERTY);
        Path outputDir = outputDirectory(inputDir);
        boolean recursive = Boolean.parseBoolean(System.getProperty(RECURSIVE_PROPERTY, "true"));

        Files.createDirectories(outputDir);

        List<Path> documents = findWordDocuments(inputDir, outputDir, recursive);
        if (documents.isEmpty()) {
            fail("No .docx files were found under: " + inputDir.toAbsolutePath());
        }

        WordSpecificationParser parser = new WordSpecificationParser();
        SpecificationNormalizer normalizer = new SpecificationNormalizer();
        SpecificationValidator validator = new SpecificationValidator();
        JsonExporter exporter = new JsonExporter();

        List<Result> results = new ArrayList<>();
        int index = 0;

        for (Path document : documents) {
            index++;
            Path relative = inputDir.relativize(document);
            Path jsonOutput = jsonOutput(outputDir, relative);
            Files.createDirectories(jsonOutput.getParent());

            System.out.printf("[%d/%d] %s%n", index, documents.size(), relative);

            try (InputStream input = Files.newInputStream(document)) {
                DatabaseSchema parsed = parser.parse(new SpecificationSource(
                        document.getFileName().toString(),
                        input));
                DatabaseSchema normalized = normalizer.normalize(parsed);
                ValidationReport validation = validator.validate(normalized);

                exporter.write(jsonOutput, normalized, validation);

                int tableCount = normalized.tables().size();
                int columnCount = normalized.tables().stream()
                        .mapToInt(table -> table.columns().size())
                        .sum();
                long errorCount = validation.issues().stream()
                        .filter(WordDirectoryCanonicalJsonExportIT::isError)
                        .count();
                int recoveryWarningCount = parseInt(normalized.metadata().get("recovery.warningCount"));

                String status = validation.valid() ? "VALID" : "INVALID";
                results.add(new Result(
                        relative.toString(),
                        status,
                        tableCount,
                        columnCount,
                        recoveryWarningCount,
                        errorCount,
                        outputDir.relativize(jsonOutput).toString(),
                        ""));

                System.out.printf(
                        "  %s tables=%d columns=%d validationErrors=%d recoveryWarnings=%d -> %s%n",
                        status,
                        tableCount,
                        columnCount,
                        errorCount,
                        recoveryWarningCount,
                        jsonOutput.toAbsolutePath());
            } catch (Exception exception) {
                results.add(new Result(
                        relative.toString(),
                        "ERROR",
                        0,
                        0,
                        0,
                        0,
                        "",
                        conciseMessage(exception)));
                System.out.printf("  ERROR: %s: %s%n",
                        exception.getClass().getSimpleName(),
                        conciseMessage(exception));
            }
        }

        Path summary = outputDir.resolve(
                "word-json-export-summary_" + LocalDateTime.now().format(STAMP) + ".csv");
        writeSummary(summary, results);

        long valid = results.stream().filter(result -> result.status().equals("VALID")).count();
        long invalid = results.stream().filter(result -> result.status().equals("INVALID")).count();
        long failed = results.stream().filter(result -> result.status().equals("ERROR")).count();

        System.out.println();
        System.out.println("Standard Word -> Canonical JSON batch completed");
        System.out.println("Documents : " + results.size());
        System.out.println("Valid     : " + valid);
        System.out.println("Invalid   : " + invalid + " (JSON still generated with validation findings)");
        System.out.println("Failed    : " + failed);
        System.out.println("Output    : " + outputDir.toAbsolutePath());
        System.out.println("Summary   : " + summary.toAbsolutePath());
        System.out.println("Database  : NOT USED");

        if (failed > 0) {
            fail(failed + " document(s) could not be converted. See summary: " + summary.toAbsolutePath());
        }
    }

    private static Path requiredDirectory(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required system property is missing: -D" + propertyName + "=<directory>");
        }
        Path directory = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Input directory does not exist: " + directory);
        }
        return directory;
    }

    private static Path outputDirectory(Path inputDir) {
        String configured = System.getProperty(OUTPUT_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return inputDir.resolve("_schemaforge-json").toAbsolutePath().normalize();
    }

    private static List<Path> findWordDocuments(Path inputDir, Path outputDir, boolean recursive) throws Exception {
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (var stream = Files.walk(inputDir, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isDocx(path.getFileName().toString()))
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputDir))
                    .sorted(Comparator.comparing(path -> inputDir.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static boolean isDocx(String fileName) {
        if (fileName == null || fileName.startsWith("~$")) {
            return false;
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    private static Path jsonOutput(Path outputDir, Path relativeDocx) {
        Path parent = relativeDocx.getParent();
        String fileName = relativeDocx.getFileName().toString();
        String jsonName = fileName.substring(0, fileName.length() - ".docx".length()) + ".json";
        return parent == null
                ? outputDir.resolve(jsonName)
                : outputDir.resolve(parent).resolve(jsonName);
    }

    private static boolean isError(ValidationIssue issue) {
        return issue != null && "ERROR".equalsIgnoreCase(issue.severity());
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static void writeSummary(Path summary, List<Result> results) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("source,status,tables,columns,recoveryWarnings,validationErrors,jsonOutput,error")
                .append(System.lineSeparator());
        for (Result result : results) {
            csv.append(csv(result.source())).append(',')
                    .append(csv(result.status())).append(',')
                    .append(result.tables()).append(',')
                    .append(result.columns()).append(',')
                    .append(result.recoveryWarnings()).append(',')
                    .append(result.validationErrors()).append(',')
                    .append(csv(result.jsonOutput())).append(',')
                    .append(csv(result.error()))
                    .append(System.lineSeparator());
        }
        Files.writeString(
                summary,
                csv.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private record Result(
            String source,
            String status,
            int tables,
            int columns,
            int recoveryWarnings,
            long validationErrors,
            String jsonOutput,
            String error) {
    }
}
