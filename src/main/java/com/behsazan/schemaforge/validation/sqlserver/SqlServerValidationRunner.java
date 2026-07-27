package com.behsazan.schemaforge.validation.sqlserver;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;
import com.behsazan.schemaforge.validation.DdlValidationReportWriter;
import com.behsazan.schemaforge.validation.DdlValidationResult;
import com.behsazan.schemaforge.validation.DdlValidationStatus;
import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import com.behsazan.schemaforge.validation.JdbcDdlValidationService;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Microsoft SQL Server validation utility with three explicit modes: offline generation,
 * read-only connection probing and opt-in live DDL execution.
 */
public final class SqlServerValidationRunner {
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final String EXECUTION_CONFIRMATION =
            "I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE";

    private SqlServerValidationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "probe".equalsIgnoreCase(args[0])) {
            SqlServerConnectionProbeResult result = probe();
            printProbe(result);
            if (!result.successful()) {
                throw new IllegalStateException(result.message());
            }
            return;
        }

        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SqlServerValidationRunner probe | "
                            + "SqlServerValidationRunner <generate|execute> <input-directory> <output-directory>");
        }

        Mode mode = Mode.parse(args[0]);
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        BatchResult result = run(mode, input, output);
        System.out.printf(
                "Documents: %d, Generated: %d, Offline failures: %d, Executed: %d, "
                        + "Execution failures: %d, Offline report: %s%s%n",
                result.documents(), result.generated(), result.offlineFailures(), result.executed(),
                result.executionFailures(), result.offlineReport(),
                result.executionReport() == null ? "" : ", Execution report: " + result.executionReport());
        if (result.offlineFailures() > 0 || result.executionFailures() > 0) {
            throw new IllegalStateException("One or more SQL Server validation checks failed");
        }
    }

    public static BatchResult run(Mode mode, Path inputDirectory, Path outputDirectory) throws Exception {
        if (!Files.isDirectory(inputDirectory)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDirectory);
        }
        Files.createDirectories(outputDirectory);

        List<Path> documents;
        try (var stream = Files.list(inputDirectory)) {
            documents = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No .docx files found in: " + inputDirectory);
        }

        JdbcConnectionSettings settings = null;
        String driverClassName = propertyOrEnvironment(
                "schemaforge.sqlserver.driver",
                "SCHEMAFORGE_SQLSERVER_DRIVER",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        if (mode == Mode.EXECUTE) {
            requireExecutionConfirmation();
            SqlServerConnectionProbeResult probe = probe();
            printProbe(probe);
            if (!probe.successful()) {
                throw new IllegalStateException("SQL Server probe failed: " + probe.message());
            }
            Class.forName(driverClassName);
            settings = connectionSettings();
        }

        SchemaGenerationService generationService = new SchemaGenerationService();
        SqlServerOfflineDdlValidator offlineValidator = new SqlServerOfflineDdlValidator();
        JdbcDdlValidationService executionService = new JdbcDdlValidationService();
        List<OfflineRow> offlineRows = new ArrayList<>();
        List<DdlValidationResult> executionRows = new ArrayList<>();

        int generated = 0;
        int offlineFailures = 0;
        int executed = 0;
        int executionFailures = 0;

        for (Path document : documents) {
            GenerationOutput generatedOutput = generationService.generate(
                    document, outputDirectory, DatabasePlatform.SQLSERVER);
            generated++;
            String sql = Files.readString(generatedOutput.sqlFile(), StandardCharsets.UTF_8);
            SqlServerOfflineValidationResult offline = offlineValidator.validate(sql);
            if (!generatedOutput.valid()) {
                offlineRows.add(new OfflineRow(
                        document,
                        generatedOutput.sqlFile(),
                        "SPECIFICATION_INVALID",
                        0,
                        "Canonical specification validation failed."));
                offlineFailures++;
            }
            if (offline.issues().isEmpty()) {
                offlineRows.add(new OfflineRow(
                        document,
                        generatedOutput.sqlFile(),
                        "PASSED",
                        0,
                        "Static SQL Server preflight passed; statements=" + offline.statementCount()));
            } else {
                for (SqlServerOfflineValidationIssue issue : offline.issues()) {
                    offlineRows.add(new OfflineRow(
                            document,
                            generatedOutput.sqlFile(),
                            issue.code(),
                            issue.statementNumber(),
                            issue.severity() + ": " + issue.message()));
                }
                if (!offline.valid()) offlineFailures++;
            }

            if (mode == Mode.EXECUTE && generatedOutput.valid() && offline.valid()) {
                DdlValidationResult execution = executionService.validate(
                        document, generatedOutput.sqlFile(), DatabasePlatform.SQLSERVER, settings);
                executionRows.add(execution);
                executed++;
                if (execution.status() == DdlValidationStatus.FAILED) executionFailures++;
            }
        }

        String timestamp = REPORT_TIME.format(LocalDateTime.now());
        Path offlineReport = outputDirectory.resolve(
                "sqlserver-offline-validation-report_" + timestamp + ".csv");
        writeOfflineReport(offlineReport, offlineRows);

        Path executionReport = null;
        if (!executionRows.isEmpty()) {
            executionReport = outputDirectory.resolve(
                    "sqlserver-execution-validation-report_" + timestamp + ".csv");
            new DdlValidationReportWriter().write(executionReport, executionRows);
        }

        return new BatchResult(
                documents.size(), generated, offlineFailures, executed,
                executionFailures, offlineReport, executionReport);
    }

    public static SqlServerConnectionProbeResult probe() {
        return new SqlServerConnectionProbeService().probe(
                connectionSettings(),
                propertyOrEnvironment(
                        "schemaforge.sqlserver.driver",
                        "SCHEMAFORGE_SQLSERVER_DRIVER",
                        "com.microsoft.sqlserver.jdbc.SQLServerDriver"));
    }

    private static JdbcConnectionSettings connectionSettings() {
        return new JdbcConnectionSettings(
                requiredPropertyOrEnvironment("schemaforge.sqlserver.url", "SCHEMAFORGE_SQLSERVER_URL"),
                propertyOrEnvironment("schemaforge.sqlserver.user", "SCHEMAFORGE_SQLSERVER_USERNAME", ""),
                propertyOrEnvironment("schemaforge.sqlserver.password", "SCHEMAFORGE_SQLSERVER_PASSWORD", ""));
    }

    private static void requireExecutionConfirmation() {
        String value = propertyOrEnvironment(
                "schemaforge.sqlserver.execution.confirm",
                "SCHEMAFORGE_SQLSERVER_EXECUTION_CONFIRM",
                "");
        if (!EXECUTION_CONFIRMATION.equals(value)) {
            throw new IllegalArgumentException(
                    "Live SQL Server DDL execution is blocked. Set schemaforge.sqlserver.execution.confirm="
                            + EXECUTION_CONFIRMATION
                            + " only for an approved disposable validation database.");
        }
    }

    private static String requiredPropertyOrEnvironment(String property, String environment) {
        String value = propertyOrEnvironment(property, environment, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required setting: -D" + property + " or " + environment);
        }
        return value;
    }

    private static String propertyOrEnvironment(
            String property,
            String environment,
            String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static void printProbe(SqlServerConnectionProbeResult result) {
        System.out.println("Microsoft SQL Server connection probe");
        System.out.println("Successful       : " + result.successful());
        System.out.println("Product          : " + result.productName() + " " + result.productVersion());
        System.out.println("Driver           : " + result.driverName() + " " + result.driverVersion());
        System.out.println("Server           : " + result.serverName());
        System.out.println("Database         : " + result.databaseName());
        System.out.println("Default schema   : " + result.defaultSchema());
        System.out.println("Catalog readable : " + result.catalogAccessible());
        System.out.println("Message          : " + result.message());
    }

    private static void writeOfflineReport(Path file, List<OfflineRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("source_document,sql_file,code,statement_number,message");
            writer.newLine();
            for (OfflineRow row : rows) {
                writer.write(csv(row.document().toString()));
                writer.write(',');
                writer.write(csv(row.sqlFile().toString()));
                writer.write(',');
                writer.write(csv(row.code()));
                writer.write(',');
                writer.write(Integer.toString(row.statementNumber()));
                writer.write(',');
                writer.write(csv(row.message()));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    public enum Mode {
        GENERATE,
        EXECUTE;

        static Mode parse(String value) {
            if ("generate".equalsIgnoreCase(value)) return GENERATE;
            if ("execute".equalsIgnoreCase(value)) return EXECUTE;
            throw new IllegalArgumentException("Mode must be generate or execute");
        }
    }

    public record BatchResult(
            int documents,
            int generated,
            int offlineFailures,
            int executed,
            int executionFailures,
            Path offlineReport,
            Path executionReport) {
    }

    private record OfflineRow(
            Path document,
            Path sqlFile,
            String code,
            int statementNumber,
            String message) {
    }
}
