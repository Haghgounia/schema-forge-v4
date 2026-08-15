package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit test-only coverage scan for self-references and dependency cycles in historical canonical JSON.
 *
 * <p>This runner intentionally accepts multiple historical definitions of the same qualified table. It never
 * chooses an effective production version and never produces deployment SQL. Multi-table cycles are therefore
 * reported as <em>historical aggregate cycle candidates</em>: an SCC can be formed by edges from versions that
 * would not coexist in one production input. Self-references, by contrast, are intrinsic to a table definition
 * and can be reported directly.</p>
 */
class CanonicalJsonDependencyCoverageIT {
    private static final String INPUT_DIR = "schemaforge.dependency.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.dependency.outputDir";
    private static final String FAIL_ON_SNAPSHOT_ERRORS = "schemaforge.dependency.failOnSnapshotErrors";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final HistoricalDependencyCoverage coverage = new HistoricalDependencyCoverage();

    @Test
    void reportsHistoricalSelfReferencesAndAggregateDependencyCycles() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        boolean failOnSnapshotErrors = Boolean.parseBoolean(
                System.getProperty(FAIL_ON_SNAPSHOT_ERRORS, "true"));
        Files.createDirectories(outputRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonDependencyCoverageIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertFalse(snapshots.isEmpty(), "No canonical JSON snapshots found below " + inputRoot);

        List<HistoricalDependencyCoverage.Definition> definitions = new ArrayList<>();
        List<String> snapshotErrors = new ArrayList<>();
        snapshotErrors.add("snapshot,source,error");
        for (Path snapshotPath : snapshots) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(snapshotPath);
                DatabaseSchema schema = mapper.toDomain(snapshot);
                String source = snapshot.source() == null || snapshot.source().relativePath() == null
                        ? "" : snapshot.source().relativePath();
                definitions.add(new HistoricalDependencyCoverage.Definition(
                        normalize(inputRoot.relativize(snapshotPath)), source, schema));
            } catch (Exception exception) {
                snapshotErrors.add(csvLine(normalize(inputRoot.relativize(snapshotPath)), "",
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            }
        }

        HistoricalDependencyCoverage.Result result = coverage.analyze(definitions);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);

        Path summaryReport = outputRoot.resolve("canonical-json-dependency-coverage-summary_" + timestamp + ".txt");
        Path selfReferenceReport = outputRoot.resolve(
                "canonical-json-dependency-self-references_" + timestamp + ".csv");
        Path cycleReport = outputRoot.resolve("canonical-json-dependency-cycles_" + timestamp + ".csv");
        Path edgeReport = outputRoot.resolve("canonical-json-dependency-edges_" + timestamp + ".csv");
        Path missingTargetReport = outputRoot.resolve(
                "canonical-json-dependency-missing-targets_" + timestamp + ".csv");
        Path snapshotErrorReport = outputRoot.resolve(
                "canonical-json-dependency-snapshot-errors_" + timestamp + ".csv");

        Files.writeString(summaryReport, summary(inputRoot, snapshots.size(), definitions.size(),
                snapshotErrors.size() - 1, result), StandardCharsets.UTF_8);
        Files.writeString(selfReferenceReport, selfReferencesCsv(result), StandardCharsets.UTF_8);
        Files.writeString(cycleReport, cyclesCsv(result), StandardCharsets.UTF_8);
        Files.writeString(edgeReport, edgesCsv(result), StandardCharsets.UTF_8);
        Files.writeString(missingTargetReport, missingTargetsCsv(result), StandardCharsets.UTF_8);
        Files.writeString(snapshotErrorReport, String.join(System.lineSeparator(), snapshotErrors)
                + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.print(summary(inputRoot, snapshots.size(), definitions.size(),
                snapshotErrors.size() - 1, result));
        System.out.println("Summary report      : " + summaryReport);
        System.out.println("Self-reference report: " + selfReferenceReport);
        System.out.println("Cycle report        : " + cycleReport);
        System.out.println("Dependency edges    : " + edgeReport);
        System.out.println("Missing targets     : " + missingTargetReport);
        System.out.println("Snapshot errors     : " + snapshotErrorReport);

        if (failOnSnapshotErrors) {
            assertTrue(snapshotErrors.size() == 1,
                    "Canonical snapshot read failures exist; see " + snapshotErrorReport);
        }
    }

    private static String summary(
            Path inputRoot,
            int snapshotsDiscovered,
            int snapshotsLoaded,
            int snapshotFailures,
            HistoricalDependencyCoverage.Result result) {
        StringBuilder text = new StringBuilder();
        text.append("SchemaForge historical dependency coverage").append(System.lineSeparator());
        text.append("==========================================").append(System.lineSeparator());
        text.append("Input directory                  : ").append(inputRoot).append(System.lineSeparator());
        text.append("Analysis mode                    : TEST_ONLY_HISTORICAL_AGGREGATE").append(System.lineSeparator());
        text.append("Snapshots discovered             : ").append(snapshotsDiscovered).append(System.lineSeparator());
        text.append("Snapshots loaded                 : ").append(snapshotsLoaded).append(System.lineSeparator());
        text.append("Snapshot failures                : ").append(snapshotFailures).append(System.lineSeparator());
        text.append("Table definitions                : ").append(result.tableDefinitions()).append(System.lineSeparator());
        text.append("Distinct table names             : ").append(result.distinctTables()).append(System.lineSeparator());
        text.append("Duplicate occurrences            : ").append(result.duplicateOccurrences()).append(System.lineSeparator());
        text.append("Foreign-key definitions          : ").append(result.foreignKeys()).append(System.lineSeparator());
        text.append("Physical FK definitions          : ").append(result.physicalForeignKeys()).append(System.lineSeparator());
        text.append("Logical FK definitions           : ").append(result.logicalForeignKeys()).append(System.lineSeparator());
        text.append("Distinct physical FK relations   : ").append(result.distinctPhysicalRelations()).append(System.lineSeparator());
        text.append("Aggregate dependency edges       : ").append(result.aggregateDependencyEdges()).append(System.lineSeparator());
        text.append("Missing target definitions       : ").append(result.missingReferencedTableDefinitions()).append(System.lineSeparator());
        text.append("Self-reference definitions       : ").append(result.selfReferenceDefinitions()).append(System.lineSeparator());
        text.append("Distinct self-reference relations: ").append(result.distinctSelfReferenceRelations()).append(System.lineSeparator());
        text.append("Aggregate cycle candidate groups : ").append(result.aggregateCycleGroups()).append(System.lineSeparator());
        text.append("Tables in aggregate cycles       : ").append(result.aggregateCycleTables()).append(System.lineSeparator());
        text.append(System.lineSeparator());
        text.append("NOTE: aggregate multi-table cycles are coverage candidates only because historical versions").append(System.lineSeparator());
        text.append("may contribute edges that would not coexist in a normal one-version-per-table integrated input.").append(System.lineSeparator());
        return text.toString();
    }

    private static String selfReferencesCsv(HistoricalDependencyCoverage.Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("snapshot,source,table,foreign_key,columns,referenced_table,referenced_columns");
        for (HistoricalDependencyCoverage.SelfReference reference : result.selfReferences()) {
            lines.add(csvLine(reference.snapshot(), reference.source(), reference.table(), reference.foreignKey(),
                    reference.columns(), reference.referencedTable(), reference.referencedColumns()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String cyclesCsv(HistoricalDependencyCoverage.Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("cycle_group,member_count,members,note");
        int index = 0;
        for (HistoricalDependencyCoverage.CycleGroup cycle : result.cycles()) {
            index++;
            lines.add(csvLine(Integer.toString(index), Integer.toString(cycle.members().size()),
                    String.join(" | ", cycle.members()), "HISTORICAL_AGGREGATE_CANDIDATE"));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String edgesCsv(HistoricalDependencyCoverage.Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("owner_table,referenced_table");
        result.dependencyEdges().stream()
                .sorted(Comparator.comparing(HistoricalDependencyCoverage.DependencyEdge::owner)
                        .thenComparing(HistoricalDependencyCoverage.DependencyEdge::target))
                .forEach(edge -> lines.add(csvLine(edge.owner(), edge.target())));
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String missingTargetsCsv(HistoricalDependencyCoverage.Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("snapshot,source,table,foreign_key,referenced_table");
        for (HistoricalDependencyCoverage.MissingTarget missing : result.missingTargets()) {
            lines.add(csvLine(missing.snapshot(), missing.source(), missing.table(), missing.foreignKey(),
                    missing.referencedTable()));
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
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
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-dependency-coverage")
                .toAbsolutePath().normalize()
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
}
