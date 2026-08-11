package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisIssue;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalysisResult;
import com.behsazan.schemaforge.deployment.ForeignKeyAnalyzer;
import com.behsazan.schemaforge.deployment.IntegratedSchemaAssembler;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit analysis runner for integrated-schema foreign keys loaded only from canonical JSON.
 *
 * <p>The runner does not open Word documents and does not generate or execute DBMS-specific SQL.
 * It enforces the production input contract that a qualified table appears only once. Historical
 * regression corpora containing multiple versions are reported as duplicate input and are not
 * silently resolved.</p>
 */
class CanonicalJsonForeignKeyAnalysisIT {
    private static final String INPUT_DIR = "schemaforge.fk.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.fk.outputDir";
    private static final String FAIL_ON_BLOCKERS = "schemaforge.fk.failOnBlockers";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final IntegratedSchemaAssembler assembler = new IntegratedSchemaAssembler();
    private final ForeignKeyAnalyzer analyzer = new ForeignKeyAnalyzer();

    @Test
    void analyzesIntegratedForeignKeysFromCanonicalJson() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        boolean failOnBlockers = Boolean.parseBoolean(System.getProperty(FAIL_ON_BLOCKERS, "true"));
        Files.createDirectories(outputRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonForeignKeyAnalysisIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }

        List<LoadedSchema> loaded = new ArrayList<>();
        List<String> snapshotErrors = new ArrayList<>();
        snapshotErrors.add("snapshot,source,error");
        for (Path snapshotPath : snapshots) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                DatabaseSchema schema = mapper.toDomain(snapshot);
                String source = snapshot.source() == null || snapshot.source().relativePath() == null
                        ? "" : snapshot.source().relativePath();
                loaded.add(new LoadedSchema(snapshotPath, source, schema));
            } catch (Exception exception) {
                snapshotErrors.add(csvLine(normalize(inputRoot.relativize(snapshotPath)), "",
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            }
        }

        List<DuplicateTable> duplicates = findDuplicateTables(inputRoot, loaded);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path duplicateReport = outputRoot.resolve("canonical-json-fk-duplicates_" + timestamp + ".csv");
        Path snapshotErrorReport = outputRoot.resolve("canonical-json-fk-snapshot-errors_" + timestamp + ".csv");
        Files.writeString(duplicateReport, duplicateCsv(duplicates), StandardCharsets.UTF_8);
        Files.writeString(snapshotErrorReport, String.join(System.lineSeparator(), snapshotErrors)
                + System.lineSeparator(), StandardCharsets.UTF_8);

        ForeignKeyAnalysisResult result = null;
        List<String> issues = new ArrayList<>();
        issues.add("severity,code,table,foreign_key,referenced_table,message");
        if (duplicates.isEmpty() && snapshotErrors.size() == 1) {
            DatabaseSchema integrated = assembler.assemble("INTEGRATED",
                    loaded.stream().map(LoadedSchema::schema).toList());
            result = analyzer.analyze(integrated);
            for (ForeignKeyAnalysisIssue issue : result.issues()) {
                issues.add(csvLine(issue.severity().name(), issue.code().name(), issue.table(),
                        issue.foreignKey(), issue.referencedTable(), issue.message()));
            }
        }

        Path issueReport = outputRoot.resolve("canonical-json-fk-issues_" + timestamp + ".csv");
        Path summaryReport = outputRoot.resolve("canonical-json-fk-summary_" + timestamp + ".txt");
        Files.writeString(issueReport, String.join(System.lineSeparator(), issues) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(summaryReport, summary(inputRoot, snapshots.size(), loaded.size(),
                snapshotErrors.size() - 1, duplicates.size(), result), StandardCharsets.UTF_8);

        System.out.print(summary(inputRoot, snapshots.size(), loaded.size(), snapshotErrors.size() - 1,
                duplicates.size(), result));
        System.out.println("Summary report     : " + summaryReport);
        System.out.println("Issues report      : " + issueReport);
        System.out.println("Duplicates report  : " + duplicateReport);
        System.out.println("Snapshot errors    : " + snapshotErrorReport);

        if (failOnBlockers) {
            assertTrue(snapshotErrors.size() == 1,
                    "Canonical snapshot read failures exist; see " + snapshotErrorReport);
            assertTrue(duplicates.isEmpty(),
                    "INPUT_DUPLICATE_TABLE definitions exist; integrated input must contain one version per table. See "
                            + duplicateReport);
            assertTrue(result != null && result.deployable(),
                    "Foreign-key blockers exist; see " + issueReport);
        }
    }

    private static List<DuplicateTable> findDuplicateTables(Path inputRoot, List<LoadedSchema> loaded) {
        Map<String, TableSource> first = new LinkedHashMap<>();
        List<DuplicateTable> duplicates = new ArrayList<>();
        for (LoadedSchema loadedSchema : loaded) {
            for (Table table : loadedSchema.schema().tables()) {
                String key = table.qualifiedName().toString().toUpperCase(Locale.ROOT);
                TableSource current = new TableSource(
                        normalize(inputRoot.relativize(loadedSchema.snapshot())), loadedSchema.source());
                TableSource previous = first.putIfAbsent(key, current);
                if (previous != null) {
                    duplicates.add(new DuplicateTable(table.qualifiedName().toString(), previous, current));
                }
            }
        }
        return List.copyOf(duplicates);
    }

    private static String duplicateCsv(List<DuplicateTable> duplicates) {
        List<String> lines = new ArrayList<>();
        lines.add("table,first_snapshot,first_source,duplicate_snapshot,duplicate_source");
        for (DuplicateTable duplicate : duplicates) {
            lines.add(csvLine(duplicate.table(), duplicate.first().snapshot(), duplicate.first().source(),
                    duplicate.duplicate().snapshot(), duplicate.duplicate().source()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String summary(
            Path inputRoot,
            int discovered,
            int loaded,
            int snapshotFailures,
            int duplicateTables,
            ForeignKeyAnalysisResult result) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge integrated FK analysis").append(System.lineSeparator());
        text.append("==================================").append(System.lineSeparator());
        text.append("Input directory      : ").append(inputRoot).append(System.lineSeparator());
        text.append("Snapshots discovered : ").append(discovered).append(System.lineSeparator());
        text.append("Snapshots loaded     : ").append(loaded).append(System.lineSeparator());
        text.append("Snapshot failures    : ").append(snapshotFailures).append(System.lineSeparator());
        text.append("Duplicate tables     : ").append(duplicateTables).append(System.lineSeparator());
        if (result == null) {
            text.append("Integrated analysis  : BLOCKED BY INPUT").append(System.lineSeparator());
            return text.toString();
        }
        text.append("Tables               : ").append(result.tables()).append(System.lineSeparator());
        text.append("Foreign keys         : ").append(result.foreignKeys()).append(System.lineSeparator());
        text.append("Physical FKs         : ").append(result.physicalForeignKeys()).append(System.lineSeparator());
        text.append("Logical FKs          : ").append(result.logicalForeignKeys()).append(System.lineSeparator());
        text.append("Resolved physical FKs: ").append(result.resolvedPhysicalForeignKeys()).append(System.lineSeparator());
        text.append("Self references      : ").append(result.selfReferences()).append(System.lineSeparator());
        text.append("Dependency cycles    : ").append(result.cycleGroups()).append(System.lineSeparator());
        text.append("Warnings             : ").append(result.warningCount()).append(System.lineSeparator());
        text.append("Blockers             : ").append(result.errorCount()).append(System.lineSeparator());
        text.append("Deployable           : ").append(result.deployable()).append(System.lineSeparator());
        return text.toString();
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Input directory does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-fk-analysis").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String text = value == null ? "" : value;
            escaped.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record LoadedSchema(Path snapshot, String source, DatabaseSchema schema) {
    }

    private record TableSource(String snapshot, String source) {
    }

    private record DuplicateTable(String table, TableSource first, TableSource duplicate) {
    }
}
