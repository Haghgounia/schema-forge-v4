package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only corpus probe for the P7 legacy UK/UQ routing fix.
 *
 * <p>The probe reparses Word files directly with {@link LegacyWordSpecificationParser} and reports
 * unique constraints materialized in the canonical domain model. It never writes or mutates the
 * recovered canonical snapshot corpus.</p>
 */
class LegacyUniqueKeyRecoveryProbeIT {
    private static final String INPUT_DIR = "schemaforge.uk.probe.wordRoot";
    private static final String LEGACY_SCHEMA = "schemaforge.uk.probe.legacySchema";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.uk.probe.db2SysColumnsFile";
    private static final String OUTPUT_DIR = "schemaforge.uk.probe.outputDir";
    private static final String FAIL_ON_ERRORS = "schemaforge.uk.probe.failOnErrors";
    private static final String MAX_DOCUMENTS = "schemaforge.uk.probe.maxDocuments";
    private static final String EXPECTED_MIN_DOCUMENTS = "schemaforge.uk.probe.expectedMinDocuments";

    @Test
    void probesRecoveredUniqueKeysWithoutMutatingCanonicalSnapshots() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        String schema = System.getProperty(LEGACY_SCHEMA, "TSTSHMA").trim();
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
        int maxDocuments = nonNegativeInt(MAX_DOCUMENTS, 0);
        int expectedMinDocuments = nonNegativeInt(EXPECTED_MIN_DOCUMENTS, 1);

        LegacyWordSpecificationParser parser = parser();
        List<Path> documents;
        try (var paths = Files.walk(inputRoot)) {
            documents = paths.filter(Files::isRegularFile)
                    .filter(LegacyUniqueKeyRecoveryProbeIT::isWordDocument)
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        int discovered = documents.size();
        if (maxDocuments > 0 && documents.size() > maxDocuments) {
            documents = documents.subList(0, maxDocuments);
        }

        List<ResultRow> rows = new ArrayList<>();
        List<FailureRow> failures = new ArrayList<>();
        int parsedDocuments = 0;
        int skippedNoTable = 0;
        int tables = 0;
        int tablesWithUniqueKeys = 0;
        int uniqueKeys = 0;
        int uniqueKeyColumns = 0;

        for (Path document : documents) {
            String relative = normalize(inputRoot.relativize(document));
            try {
                DatabaseSchema parsed = parser.parse(inputRoot, document, schema);
                parsedDocuments++;
                for (Table table : parsed.tables()) {
                    tables++;
                    if (!table.uniqueKeys().isEmpty()) {
                        tablesWithUniqueKeys++;
                    }
                    for (UniqueKey uniqueKey : table.uniqueKeys()) {
                        uniqueKeys++;
                        uniqueKeyColumns += uniqueKey.columns().size();
                        rows.add(new ResultRow(
                                relative,
                                table.qualifiedName().toString(),
                                uniqueKey.name().value(),
                                uniqueKey.columns().stream().map(column -> column.value()).toList()));
                    }
                }
            } catch (IllegalArgumentException exception) {
                String message = safeMessage(exception);
                if (message.startsWith("No legacy table definition was accepted")) {
                    skippedNoTable++;
                } else {
                    failures.add(new FailureRow(relative, exception.getClass().getSimpleName(), message));
                }
            } catch (Exception exception) {
                failures.add(new FailureRow(relative, exception.getClass().getSimpleName(), safeMessage(exception)));
            }
        }

        Path reportDirectory = reportDirectory();
        Files.createDirectories(reportDirectory);
        Path summary = reportDirectory.resolve("legacy-unique-key-probe-summary.txt");
        Path csv = reportDirectory.resolve("legacy-unique-key-probe.csv");
        Path errorCsv = reportDirectory.resolve("legacy-unique-key-probe-errors.csv");

        String summaryText = """
                SchemaForge Legacy Unique Key recovery probe (P7)
                ================================================
                Word root             : %s
                Documents discovered  : %d
                Documents selected    : %d
                Parsed documents      : %d
                Skipped no table      : %d
                Parse failures        : %d
                Tables parsed         : %d
                Tables with unique key: %d
                Unique keys recovered : %d
                Unique-key columns    : %d
                Parser version        : %s
                Report directory      : %s
                """.formatted(
                inputRoot,
                discovered,
                documents.size(),
                parsedDocuments,
                skippedNoTable,
                failures.size(),
                tables,
                tablesWithUniqueKeys,
                uniqueKeys,
                uniqueKeyColumns,
                LegacyWordSpecificationParser.PARSER_VERSION,
                reportDirectory);
        Files.writeString(summary, summaryText, StandardCharsets.UTF_8);
        writeResults(csv, rows);
        writeFailures(errorCsv, failures);
        System.out.println(summaryText);

        assertTrue(discovered >= expectedMinDocuments,
                "Word corpus smaller than expected: " + discovered + " < " + expectedMinDocuments);
        assertTrue(parsedDocuments > 0, "No legacy Word table document was parsed");
        if (failOnErrors) {
            assertTrue(failures.isEmpty(), "Legacy unique-key probe produced parse failures; see " + errorCsv);
        }
    }

    private static LegacyWordSpecificationParser parser() {
        String value = trimToNull(System.getProperty(DB2_SYSCOLUMNS_FILE));
        if (value == null) {
            return new LegacyWordSpecificationParser();
        }
        Path file = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(DB2_SYSCOLUMNS_FILE + " must point to a file: " + file);
        }
        return LegacyWordSpecificationParser.withDb2SysColumns(file);
    }

    private static Path reportDirectory() {
        String configured = trimToNull(System.getProperty(OUTPUT_DIR));
        Path root = configured == null
                ? Path.of("target", "legacy-unique-key-probe")
                : Path.of(configured);
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return root.toAbsolutePath().normalize().resolve(runId);
    }

    private static Path requiredDirectory(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) {
            throw new IllegalArgumentException("Missing required system property -D" + property + "=<directory>");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(property + " must point to an existing directory: " + path);
        }
        return path;
    }

    private static int nonNegativeInt(String property, int defaultValue) {
        String raw = trimToNull(System.getProperty(property));
        if (raw == null) return defaultValue;
        int value = Integer.parseInt(raw);
        if (value < 0) throw new IllegalArgumentException(property + " must be >= 0");
        return value;
    }

    private static boolean isWordDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx");
    }

    private static void writeResults(Path file, List<ResultRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("source,table,unique_key,columns");
        for (ResultRow row : rows) {
            lines.add(csv(row.source()) + "," + csv(row.table()) + "," + csv(row.uniqueKey()) + ","
                    + csv(String.join("|", row.columns())));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static void writeFailures(Path file, List<FailureRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("source,error_type,message");
        for (FailureRow row : rows) {
            lines.add(csv(row.source()) + "," + csv(row.errorType()) + "," + csv(row.message()));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ResultRow(String source, String table, String uniqueKey, List<String> columns) {}
    private record FailureRow(String source, String errorType, String message) {}
}
