package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Manual test utility for bulk generation and parser diagnostics.
 *
 * Usage:
 *   java ... DirectoryDualDatabaseGenerationRunner <input-directory> <output-directory>
 *
 * The input tree is scanned recursively. For every .docx file, this runner generates Oracle and
 * PostgreSQL scripts and writes batch diagnostics for indexes, unique indexes and unique keys.
 */
public final class DirectoryDualDatabaseGenerationRunner {
    private static final String SUMMARY_FILE = "batch-generation-summary.csv";
    private static final String INDEX_DETAIL_FILE = "batch-index-details.csv";
    private static final String RECOVERY_WARNING_FILE = "batch-recovery-warnings.csv";
    private static final String ERROR_FILE = "batch-generation-errors.log";

    private DirectoryDualDatabaseGenerationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: DirectoryDualDatabaseGenerationRunner <input-directory> <output-directory>");
        }

        BatchResult result = generateAll(Path.of(args[0]), Path.of(args[1]));
        System.out.printf(
                "Documents: %d, Passed: %d, Failed: %d, Oracle scripts: %d, "
                        + "PostgreSQL scripts: %d, Invalid: %d, Indexes: %d, "
                        + "Unique indexes: %d, Unique keys: %d%n",
                result.documents(), result.passedDocuments(), result.failedDocuments(),
                result.oracleScripts(), result.postgreSqlScripts(), result.invalidDocuments(),
                result.indexes(), result.uniqueIndexes(), result.uniqueKeys());
        System.out.println("Summary : " + result.summaryFile());
        System.out.println("Indexes : " + result.indexDetailFile());
        System.out.println("Warnings: " + result.recoveryWarningFile());
        System.out.println("Errors  : " + result.errorFile());

        if (result.invalidDocuments() > 0 || result.failedDocuments() > 0) {
            throw new IllegalStateException(
                    "One or more specifications failed or were invalid. Review the batch reports.");
        }
    }

    static BatchResult generateAll(Path inputDirectory, Path outputDirectory) throws Exception {
        Path input = inputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Input directory does not exist: " + input);
        }
        Files.createDirectories(output);

        List<Path> documents;
        try (Stream<Path> files = Files.walk(input)) {
            documents = files
                    .filter(Files::isRegularFile)
                    .filter(DirectoryDualDatabaseGenerationRunner::isWordDocument)
                    .sorted(Comparator.comparing(path ->
                            input.relativize(path).toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No .docx files found in: " + input);
        }

        Path summaryFile = output.resolve(SUMMARY_FILE);
        Path indexDetailFile = output.resolve(INDEX_DETAIL_FILE);
        Path recoveryWarningFile = output.resolve(RECOVERY_WARNING_FILE);
        Path errorFile = output.resolve(ERROR_FILE);
        initializeReports(summaryFile, indexDetailFile, recoveryWarningFile, errorFile);

        SchemaGenerationService service = new SchemaGenerationService();
        int oracleScripts = 0;
        int postgreSqlScripts = 0;
        int invalidDocuments = 0;
        int passedDocuments = 0;
        int failedDocuments = 0;
        int totalIndexes = 0;
        int totalUniqueIndexes = 0;
        int totalUniqueKeys = 0;

        int sequence = 0;
        for (Path document : documents) {
            sequence++;
            Instant started = Instant.now();
            String relativeDocument = normalizePath(input.relativize(document));
            System.out.printf("[%d/%d] %s%n", sequence, documents.size(), relativeDocument);

            try {
                DatabaseSchema parsed = parse(document);
                DocumentStatistics statistics = statistics(parsed);
                writeIndexDetails(indexDetailFile, relativeDocument, parsed);
                writeRecoveryWarnings(recoveryWarningFile, relativeDocument, parsed);

                GenerationOutput oracle = service.generate(document, output, DatabasePlatform.ORACLE);
                GenerationOutput postgreSql = service.generate(document, output, DatabasePlatform.POSTGRESQL);

                boolean oracleCreated = Files.isRegularFile(oracle.sqlFile());
                boolean postgreSqlCreated = Files.isRegularFile(postgreSql.sqlFile());
                oracleScripts += oracleCreated ? 1 : 0;
                postgreSqlScripts += postgreSqlCreated ? 1 : 0;
                boolean valid = oracle.valid() && postgreSql.valid();
                if (!valid) {
                    invalidDocuments++;
                }

                totalIndexes += statistics.indexes();
                totalUniqueIndexes += statistics.uniqueIndexes();
                totalUniqueKeys += statistics.uniqueKeys();
                passedDocuments++;

                long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
                appendSummary(summaryFile, new SummaryRow(
                        sequence,
                        relativeDocument,
                        "SUCCESS",
                        valid,
                        statistics.tables(),
                        statistics.columns(),
                        statistics.primaryKeys(),
                        statistics.foreignKeys(),
                        statistics.uniqueKeys(),
                        statistics.indexes(),
                        statistics.uniqueIndexes(),
                        oracleCreated,
                        postgreSqlCreated,
                        normalizePath(output.relativize(oracle.sqlFile())),
                        normalizePath(output.relativize(postgreSql.sqlFile())),
                        elapsedMillis,
                        ""));

                System.out.printf(
                        "  tables=%d columns=%d pk=%d fk=%d uniqueKeys=%d indexes=%d uniqueIndexes=%d valid=%s%n",
                        statistics.tables(), statistics.columns(), statistics.primaryKeys(),
                        statistics.foreignKeys(), statistics.uniqueKeys(), statistics.indexes(),
                        statistics.uniqueIndexes(), valid);
            } catch (Exception exception) {
                failedDocuments++;
                long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
                appendSummary(summaryFile, new SummaryRow(
                        sequence,
                        relativeDocument,
                        "FAILED",
                        false,
                        0, 0, 0, 0, 0, 0, 0,
                        false,
                        false,
                        "",
                        "",
                        elapsedMillis,
                        exception.getClass().getName() + ": " + safeMessage(exception)));
                appendError(errorFile, sequence, relativeDocument, exception);
                System.out.println("  FAILED: " + exception.getClass().getSimpleName()
                        + ": " + safeMessage(exception));
            }
        }

        return new BatchResult(
                documents.size(),
                oracleScripts,
                postgreSqlScripts,
                invalidDocuments,
                passedDocuments,
                failedDocuments,
                totalIndexes,
                totalUniqueIndexes,
                totalUniqueKeys,
                summaryFile,
                indexDetailFile,
                recoveryWarningFile,
                errorFile);
    }

    private static DatabaseSchema parse(Path document) throws IOException {
        try (InputStream stream = Files.newInputStream(document)) {
            return new WordSpecificationParser().parse(
                    new SpecificationSource(document.getFileName().toString(), stream));
        }
    }

    private static DocumentStatistics statistics(DatabaseSchema schema) {
        int tables = schema.tables().size();
        int columns = schema.tables().stream().mapToInt(table -> table.columns().size()).sum();
        int primaryKeys = (int) schema.tables().stream().filter(table -> table.primaryKey().isPresent()).count();
        int foreignKeys = schema.tables().stream().mapToInt(table -> table.foreignKeys().size()).sum();
        int uniqueKeys = schema.tables().stream().mapToInt(table -> table.uniqueKeys().size()).sum();
        int indexes = schema.tables().stream().mapToInt(table -> table.indexes().size()).sum();
        int uniqueIndexes = schema.tables().stream()
                .flatMap(table -> table.indexes().stream())
                .mapToInt(index -> index.type() == IndexType.UNIQUE ? 1 : 0)
                .sum();
        return new DocumentStatistics(
                tables, columns, primaryKeys, foreignKeys, uniqueKeys, indexes, uniqueIndexes);
    }

    private static void initializeReports(
            Path summaryFile,
            Path indexDetailFile,
            Path recoveryWarningFile,
            Path errorFile) throws IOException {
        Files.writeString(
                summaryFile,
                "sequence,document,status,valid,tables,columns,primary_keys,foreign_keys,"
                        + "unique_keys,indexes,unique_indexes,oracle_script_created,"
                        + "postgresql_script_created,oracle_script,postgresql_script,"
                        + "elapsed_ms,error\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                indexDetailFile,
                "document,table,object_type,object_name,index_type,columns,include_columns,predicate\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                recoveryWarningFile,
                "document,warning_type,details\n",
                StandardCharsets.UTF_8);
        Files.writeString(errorFile, "", StandardCharsets.UTF_8);
    }

    private static void writeIndexDetails(Path report, String document, DatabaseSchema schema)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (Table table : schema.tables()) {
            String tableName = table.qualifiedName().toString();
            for (UniqueKey uniqueKey : table.uniqueKeys()) {
                lines.add(csvLine(
                        document,
                        tableName,
                        "UNIQUE_KEY",
                        uniqueKey.name().toString(),
                        "CONSTRAINT",
                        uniqueKey.columns().stream().map(Object::toString).toList().toString(),
                        "",
                        ""));
            }
            for (Index index : table.indexes()) {
                String columns = index.columns().stream()
                        .map(DirectoryDualDatabaseGenerationRunner::describeIndexColumn)
                        .toList()
                        .toString();
                String includeColumns = index.includeColumns().stream()
                        .map(Object::toString)
                        .toList()
                        .toString();
                lines.add(csvLine(
                        document,
                        tableName,
                        index.type() == IndexType.UNIQUE ? "UNIQUE_INDEX" : "INDEX",
                        index.name().toString(),
                        index.type().name(),
                        columns,
                        includeColumns,
                        index.predicate() == null ? "" : index.predicate()));
            }
        }
        if (!lines.isEmpty()) {
            Files.write(
                    report,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
    }


    private static void writeRecoveryWarnings(Path report, String document, DatabaseSchema schema)
            throws IOException {
        String warnings = schema.metadata().get("recovery.warnings");
        if (warnings == null || warnings.isBlank()) {
            return;
        }
        List<String> lines = warnings.lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    int separator = line.indexOf('|');
                    String type = separator < 0 ? line : line.substring(0, separator);
                    String details = separator < 0 ? "" : line.substring(separator + 1);
                    return csvLine(document, type, details);
                })
                .toList();
        if (!lines.isEmpty()) {
            Files.write(report, lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private static String describeIndexColumn(IndexColumn indexColumn) {
        String value = indexColumn.expressionBased()
                ? "EXPR=" + indexColumn.expression()
                : indexColumn.column().toString();
        return value + " " + indexColumn.direction().name();
    }

    private static void appendSummary(Path report, SummaryRow row) throws IOException {
        Files.writeString(
                report,
                csvLine(
                        Integer.toString(row.sequence()),
                        row.document(),
                        row.status(),
                        Boolean.toString(row.valid()),
                        Integer.toString(row.tables()),
                        Integer.toString(row.columns()),
                        Integer.toString(row.primaryKeys()),
                        Integer.toString(row.foreignKeys()),
                        Integer.toString(row.uniqueKeys()),
                        Integer.toString(row.indexes()),
                        Integer.toString(row.uniqueIndexes()),
                        Boolean.toString(row.oracleScriptCreated()),
                        Boolean.toString(row.postgreSqlScriptCreated()),
                        row.oracleScript(),
                        row.postgreSqlScript(),
                        Long.toString(row.elapsedMillis()),
                        row.error()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static void appendError(Path report, int sequence, String document, Exception exception)
            throws IOException {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        String block = "============================================================\n"
                + "Sequence : " + sequence + "\n"
                + "Document : " + document + "\n"
                + "Error    : " + exception.getClass().getName() + ": " + safeMessage(exception) + "\n"
                + "------------------------------------------------------------\n"
                + stackTrace
                + "\n";
        Files.writeString(
                report,
                block,
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static String csvLine(String... values) {
        return Stream.of(values)
                .map(DirectoryDualDatabaseGenerationRunner::csv)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? "" : throwable.getMessage();
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static boolean isWordDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".docx") && !fileName.startsWith("~$");
    }

    record DocumentStatistics(
            int tables,
            int columns,
            int primaryKeys,
            int foreignKeys,
            int uniqueKeys,
            int indexes,
            int uniqueIndexes) {
    }

    record SummaryRow(
            int sequence,
            String document,
            String status,
            boolean valid,
            int tables,
            int columns,
            int primaryKeys,
            int foreignKeys,
            int uniqueKeys,
            int indexes,
            int uniqueIndexes,
            boolean oracleScriptCreated,
            boolean postgreSqlScriptCreated,
            String oracleScript,
            String postgreSqlScript,
            long elapsedMillis,
            String error) {
    }

    record BatchResult(
            int documents,
            int oracleScripts,
            int postgreSqlScripts,
            int invalidDocuments,
            int passedDocuments,
            int failedDocuments,
            int indexes,
            int uniqueIndexes,
            int uniqueKeys,
            Path summaryFile,
            Path indexDetailFile,
            Path recoveryWarningFile,
            Path errorFile) {
    }
}
