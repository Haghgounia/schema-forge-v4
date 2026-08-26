package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;
import com.behsazan.schemaforge.validation.DdlValidationReportWriter;
import com.behsazan.schemaforge.validation.DdlValidationResult;
import com.behsazan.schemaforge.validation.DdlValidationStatus;
import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import com.behsazan.schemaforge.validation.JdbcDdlValidationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Generates Oracle and PostgreSQL scripts for a directory and optionally executes them through JDBC.
 *
 * Usage:
 *   DirectoryDualDatabaseValidationRunner <input-directory> <output-directory> [generate|execute]
 *
 * Execution mode properties:
 *   -Dschemaforge.oracle.url=...
 *   -Dschemaforge.oracle.user=...
 *   -Dschemaforge.oracle.password=...
 *   -Dschemaforge.postgresql.url=...
 *   -Dschemaforge.postgresql.user=...
 *   -Dschemaforge.postgresql.password=...
 */
public final class DirectoryDualDatabaseValidationRunner {
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private DirectoryDualDatabaseValidationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: DirectoryDualDatabaseValidationRunner <input-directory> <output-directory> [generate|execute]");
        }
        boolean execute = args.length == 3 && "execute".equalsIgnoreCase(args[2]);
        if (args.length == 3 && !execute && !"generate".equalsIgnoreCase(args[2])) {
            throw new IllegalArgumentException("Mode must be generate or execute");
        }

        BatchValidationResult result = run(Path.of(args[0]), Path.of(args[1]), execute);
        System.out.printf(
                "Documents: %d, Generated: %d, Passed: %d, Failed: %d, Report: %s%n",
                result.documents(), result.generated(), result.passed(), result.failed(), result.reportFile());
        if (result.failed() > 0) {
            throw new IllegalStateException("One or more DDL scripts failed execution validation");
        }
    }

    static BatchValidationResult run(Path inputDirectory, Path outputDirectory, boolean execute) throws Exception {
        Path input = inputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Input directory does not exist: " + input);
        }
        Files.createDirectories(output);

        List<Path> documents;
        try (Stream<Path> files = Files.list(input)) {
            documents = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No .docx files found in: " + input);
        }

        JdbcConnectionSettings oracleSettings = execute ? settings(DatabasePlatform.ORACLE) : null;
        JdbcConnectionSettings postgreSqlSettings = execute ? settings(DatabasePlatform.POSTGRESQL) : null;
        SchemaGenerationService generationService = new SchemaGenerationService();
        JdbcDdlValidationService validationService = new JdbcDdlValidationService();
        List<DdlValidationResult> results = new ArrayList<>();

        List<DatabasePlatform> validationPlatforms = List.of(
                DatabasePlatform.ORACLE,
                DatabasePlatform.POSTGRESQL);
        for (Path document : documents) {
            for (DatabasePlatform platform : validationPlatforms) {
                try {
                    GenerationOutput generated = generationService.generate(document, output, platform);
                    if (!generated.valid()) {
                        results.add(new DdlValidationResult(document, generated.sqlFile(), platform,
                                DdlValidationStatus.FAILED, 0, "Specification validation failed"));
                        continue;
                    }
                    JdbcConnectionSettings connection = platform == DatabasePlatform.ORACLE
                            ? oracleSettings : postgreSqlSettings;
                    results.add(execute
                            ? validationService.validate(document, generated.sqlFile(), platform, connection)
                            : validationService.generatedOnly(document, generated.sqlFile(), platform));
                } catch (IllegalArgumentException generationBlocked) {
                    Path blockedTarget = output.resolve(
                            document.getFileName().toString() + "."
                                    + platform.commandLineName() + ".blocked.sql");
                    results.add(new DdlValidationResult(
                            document, blockedTarget, platform, DdlValidationStatus.FAILED, 0,
                            "Generation blocked: " + generationBlocked.getMessage()));
                }
            }
        }

        Path report = output.resolve("ddl-validation-report_" + REPORT_TIME.format(LocalDateTime.now()) + ".csv");
        new DdlValidationReportWriter().write(report, results);
        long passed = results.stream().filter(result -> result.status() == DdlValidationStatus.PASSED).count();
        long failed = results.stream().filter(result -> result.status() == DdlValidationStatus.FAILED).count();
        return new BatchValidationResult(documents.size(), results.size(), (int) passed, (int) failed, report);
    }

    private static JdbcConnectionSettings settings(DatabasePlatform platform) {
        String prefix = "schemaforge." + platform.commandLineName();
        String url = requiredProperty(prefix + ".url");
        return new JdbcConnectionSettings(
                url,
                System.getProperty(prefix + ".user", ""),
                System.getProperty(prefix + ".password", ""));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + name);
        }
        return value;
    }

    record BatchValidationResult(int documents, int generated, int passed, int failed, Path reportFile) {
    }
}
