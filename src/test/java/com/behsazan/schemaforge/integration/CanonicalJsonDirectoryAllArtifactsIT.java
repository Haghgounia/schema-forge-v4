package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizBatchDiagramExporter;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidBatchDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramExporter;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineDdlValidator;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.behsazan.schemaforge.validation.postgresql.PostgreSqlDdlSanityChecker;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerOfflineDdlValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full offline artifact audit from persisted canonical JSON snapshots.
 *
 * <p>This runner deliberately does not open Word documents and does not require a live database.
 * It is intended for corpus-wide validation after parser/generator/diagram changes. Every readable
 * canonical snapshot is mapped back to the domain model once and then used to generate all
 * artifacts that are semantically derivable without Actual database metadata:</p>
 *
 * <ul>
 *   <li>DDL for every currently registered {@link DatabasePlatform};</li>
 *   <li>API-style canonical JSON export;</li>
 *   <li>Mermaid ER, dependency, and conceptual ERD diagrams;</li>
 *   <li>Graphviz ER, dependency, and conceptual ERD diagrams;</li>
 *   <li>batch Mermaid/Graphviz diagrams across the complete corpus;</li>
 *   <li>artifact index, DDL validation issues, failures, and a text summary.</li>
 * </ul>
 *
 * <p>Comparison Excel workbooks, P8 Actual-vs-Design comparison, and metadata-based CRUD are not
 * fabricated here because they require a live/real {@code MetadataRepository}. Their exclusion is
 * recorded in the summary so an offline audit cannot be mistaken for database comparison.</p>
 */
class CanonicalJsonDirectoryAllArtifactsIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.artifacts.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.artifacts.outputDir";
    private static final String PLATFORMS = "schemaforge.snapshot.artifacts.platforms";
    private static final String CLEAN_OUTPUT = "schemaforge.snapshot.artifacts.cleanOutput";
    private static final String FAIL_ON_ERRORS = "schemaforge.snapshot.artifacts.failOnErrors";
    private static final String BATCH_DIAGRAMS = "schemaforge.snapshot.artifacts.batchDiagrams";

    private static final List<DatabasePlatform> DEFAULT_PLATFORMS =
            List.copyOf(Arrays.asList(DatabasePlatform.values()));

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final JsonExporter jsonExporter = new JsonExporter();
    private final MermaidDiagramExporter mermaidExporter = new MermaidDiagramExporter();
    private final GraphvizDiagramExporter graphvizExporter = new GraphvizDiagramExporter();
    private final MermaidBatchDiagramExporter mermaidBatchExporter = new MermaidBatchDiagramExporter();
    private final GraphvizBatchDiagramExporter graphvizBatchExporter = new GraphvizBatchDiagramExporter();
    private final DatatypeCompatibilityAnalyzer datatypeCompatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();
    private final OracleDdlSanityChecker oracleSanityChecker = new OracleDdlSanityChecker();
    private final PostgreSqlDdlSanityChecker postgreSqlSanityChecker = new PostgreSqlDdlSanityChecker();
    private final SqlServerOfflineDdlValidator sqlServerValidator = new SqlServerOfflineDdlValidator();
    private final Db2ZosOfflineDdlValidator db2ZosValidator = new Db2ZosOfflineDdlValidator();

    @Test
    void generatesAllOfflineArtifactsFromCanonicalJsonCorpus() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        List<DatabasePlatform> platforms = configuredPlatforms();
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "false"));
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
        boolean batchDiagrams = Boolean.parseBoolean(System.getProperty(BATCH_DIAGRAMS, "true"));

        validateNonOverlapping(inputRoot, outputRoot);
        if (cleanOutput) {
            cleanOutputDirectory(inputRoot, outputRoot);
        }
        Files.createDirectories(outputRoot);

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonDirectoryAllArtifactsIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        assertTrue(!snapshots.isEmpty(), "No *.schema.json snapshots found under " + inputRoot);

        String timestamp = outputFileNamer.timestamp();
        Path reports = Files.createDirectories(outputRoot.resolve("reports"));

        List<String> artifactIndex = new ArrayList<>();
        artifactIndex.add("snapshot,source,platform,artifact,status,path,issue_count,error");
        List<String> validationIssues = new ArrayList<>();
        validationIssues.add("snapshot,source,platform,stage,location,code,message,fragment");
        List<String> failures = new ArrayList<>();
        failures.add("snapshot,source,stage,platform,error");

        Map<DatabasePlatform, Integer> ddlGenerated = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> ddlWithIssues = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> ddlBlocked = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> ddlFailed = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Dialect> dialects = new EnumMap<>(DatabasePlatform.class);
        for (DatabasePlatform platform : platforms) {
            ddlGenerated.put(platform, 0);
            ddlWithIssues.put(platform, 0);
            ddlBlocked.put(platform, 0);
            ddlFailed.put(platform, 0);
            Dialect dialect = DialectFactory.create(platform);
            verifyDialectInvariants(platform, dialect);
            dialects.put(platform, dialect);
        }

        Map<String, Integer> artifactCounts = new LinkedHashMap<>();
        List<Table> batchTables = new ArrayList<>();
        int snapshotFailures = 0;
        int processedSnapshots = 0;
        int staleParserSnapshots = 0;
        int totalTables = 0;

        for (Path snapshotPath : snapshots) {
            String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
            CanonicalSchemaSnapshot snapshot;
            PreparedSchema prepared;
            String source = "";
            try {
                snapshot = store.readSnapshot(snapshotPath);
                source = snapshot.source() == null ? "" : nullToEmpty(snapshot.source().relativePath());
                if (!CanonicalSnapshotVersions.parserCurrent(snapshot)) {
                    staleParserSnapshots++;
                }
                DatabaseSchema mapped = mapper.toDomainPersistedSource(snapshot);
                prepared = preparationService.prepare(mapped);
            } catch (Exception exception) {
                snapshotFailures++;
                String error = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                failures.add(csvLine(relativeSnapshot, source, "SNAPSHOT", "", error));
                artifactIndex.add(csvLine(relativeSnapshot, source, "", "SNAPSHOT", "FAILED", "", "0", error));
                continue;
            }

            processedSnapshots++;
            totalTables += prepared.schema().tables().size();
            batchTables.addAll(prepared.schema().tables());
            Path documentRoot = documentOutputDirectory(outputRoot, inputRoot, snapshotPath);
            Files.createDirectories(documentRoot);

            generateJsonArtifact(relativeSnapshot, source, prepared, documentRoot, outputRoot,
                    artifactIndex, failures, artifactCounts);
            generateDiagramArtifacts(relativeSnapshot, source, prepared.schema(), documentRoot, outputRoot,
                    artifactIndex, failures, artifactCounts);

            for (DatabasePlatform platform : platforms) {
                generateDdlArtifact(relativeSnapshot, source, prepared, documentRoot, outputRoot, platform,
                        dialects.get(platform), timestamp, artifactIndex, validationIssues, failures,
                        ddlGenerated, ddlWithIssues, ddlBlocked, ddlFailed, artifactCounts);
            }
        }

        if (batchDiagrams && !batchTables.isEmpty()) {
            generateBatchDiagrams(outputRoot, batchTables, artifactIndex, failures, artifactCounts);
        }

        Files.writeString(
                reports.resolve("canonical-json-artifact-index_" + timestamp + ".csv"),
                String.join(System.lineSeparator(), artifactIndex) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(
                reports.resolve("canonical-json-ddl-validation-issues_" + timestamp + ".csv"),
                String.join(System.lineSeparator(), validationIssues) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(
                reports.resolve("canonical-json-artifact-failures_" + timestamp + ".csv"),
                String.join(System.lineSeparator(), failures) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(
                reports.resolve("database-dependent-artifacts_" + timestamp + ".csv"),
                databaseDependentArtifactsReport(),
                StandardCharsets.UTF_8);

        String summary = textSummary(
                inputRoot, outputRoot, snapshots.size(), processedSnapshots, snapshotFailures,
                staleParserSnapshots, totalTables, platforms, ddlGenerated, ddlWithIssues,
                ddlBlocked, ddlFailed, artifactCounts, validationIssues.size() - 1,
                failures.size() - 1, batchDiagrams);
        Path summaryFile = reports.resolve("canonical-json-all-artifacts-summary_" + timestamp + ".txt");
        Files.writeString(summaryFile, summary, StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("Artifact index     : " + reports.resolve(
                "canonical-json-artifact-index_" + timestamp + ".csv"));
        System.out.println("Validation issues  : " + reports.resolve(
                "canonical-json-ddl-validation-issues_" + timestamp + ".csv"));
        System.out.println("Failures           : " + reports.resolve(
                "canonical-json-artifact-failures_" + timestamp + ".csv"));
        System.out.println("Summary            : " + summaryFile);

        assertTrue(processedSnapshots > 0, "No canonical snapshot was processed successfully");
        long generatedDdl = ddlGenerated.values().stream().mapToLong(Integer::longValue).sum()
                + ddlWithIssues.values().stream().mapToLong(Integer::longValue).sum();
        assertTrue(generatedDdl > 0, "No DDL artifact was generated");
        if (failOnErrors) {
            long blockingOrFailed = snapshotFailures
                    + ddlBlocked.values().stream().mapToLong(Integer::longValue).sum()
                    + ddlFailed.values().stream().mapToLong(Integer::longValue).sum()
                    + validationIssues.size() - 1L
                    + failures.size() - 1L;
            assertTrue(blockingOrFailed == 0,
                    "Artifact audit found failures/issues; see reports under " + reports);
        }
    }

    private void generateJsonArtifact(
            String snapshot,
            String source,
            PreparedSchema prepared,
            Path documentRoot,
            Path outputRoot,
            List<String> artifactIndex,
            List<String> failures,
            Map<String, Integer> artifactCounts) {
        Path target = documentRoot.resolve("canonical-export.json");
        try {
            jsonExporter.write(target, prepared.schema(), prepared.validationReport());
            addArtifact(artifactIndex, artifactCounts, snapshot, source, "", "CANONICAL_EXPORT_JSON",
                    "GENERATED", target, 0, "", outputRoot);
        } catch (Exception exception) {
            recordArtifactFailure(snapshot, source, "CANONICAL_EXPORT_JSON", "", target, exception,
                    artifactIndex, failures, outputRoot);
        }
    }

    private void generateDiagramArtifacts(
            String snapshot,
            String source,
            DatabaseSchema schema,
            Path documentRoot,
            Path outputRoot,
            List<String> artifactIndex,
            List<String> failures,
            Map<String, Integer> artifactCounts) {

        Path mermaidDirectory = documentRoot.resolve("diagrams").resolve("mermaid");
        Path graphvizDirectory = documentRoot.resolve("diagrams").resolve("graphviz");
        try {
            Files.createDirectories(mermaidDirectory);
            Files.createDirectories(graphvizDirectory);
        } catch (Exception exception) {
            failures.add(csvLine(snapshot, source, "DIAGRAM_DIRECTORY", "",
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            return;
        }

        generateDiagram(snapshot, source, "MERMAID_ER", mermaidDirectory.resolve("schema-er.mmd"),
                () -> mermaidExporter.export(schema.tables(), DiagramExportOptions.erAll()),
                "erDiagram", artifactIndex, failures, artifactCounts, outputRoot);
        generateDiagram(snapshot, source, "MERMAID_DEPENDENCY",
                mermaidDirectory.resolve("schema-dependency.mmd"),
                () -> mermaidExporter.export(schema.tables(), DiagramExportOptions.builder()
                        .type(DiagramType.DEPENDENCY).build()),
                "flowchart", artifactIndex, failures, artifactCounts, outputRoot);
        generateDiagram(snapshot, source, "MERMAID_CONCEPTUAL_ERD",
                mermaidDirectory.resolve("schema-conceptual-erd.mmd"),
                () -> mermaidExporter.export(schema.tables(), DiagramExportOptions.builder()
                        .type(DiagramType.CONCEPTUAL_ERD).build()),
                "erDiagram", artifactIndex, failures, artifactCounts, outputRoot);

        generateDiagram(snapshot, source, "GRAPHVIZ_ER", graphvizDirectory.resolve("schema-er.dot"),
                () -> graphvizExporter.export(schema.tables(), DiagramExportOptions.erAll()),
                "digraph", artifactIndex, failures, artifactCounts, outputRoot);
        generateDiagram(snapshot, source, "GRAPHVIZ_DEPENDENCY",
                graphvizDirectory.resolve("schema-dependency.dot"),
                () -> graphvizExporter.export(schema.tables(), DiagramExportOptions.builder()
                        .type(DiagramType.DEPENDENCY).build()),
                "digraph", artifactIndex, failures, artifactCounts, outputRoot);
        generateDiagram(snapshot, source, "GRAPHVIZ_CONCEPTUAL_ERD",
                graphvizDirectory.resolve("schema-conceptual-erd.dot"),
                () -> graphvizExporter.export(schema.tables(), DiagramExportOptions.builder()
                        .type(DiagramType.CONCEPTUAL_ERD).build()),
                "digraph", artifactIndex, failures, artifactCounts, outputRoot);
    }

    private void generateDiagram(
            String snapshot,
            String source,
            String artifact,
            Path target,
            TextSupplier supplier,
            String requiredMarker,
            List<String> artifactIndex,
            List<String> failures,
            Map<String, Integer> artifactCounts,
            Path relativeRoot) {
        try {
            String text = supplier.get();
            if (text == null || text.isBlank() || !text.contains(requiredMarker)) {
                throw new IllegalStateException("Generated diagram is blank or misses marker: " + requiredMarker);
            }
            Files.writeString(target, text, StandardCharsets.UTF_8);
            addArtifact(artifactIndex, artifactCounts, snapshot, source, "", artifact,
                    "GENERATED", target, 0, "", relativeRoot);
        } catch (Exception exception) {
            recordArtifactFailure(snapshot, source, artifact, "", target, exception,
                    artifactIndex, failures, relativeRoot);
        }
    }

    private void generateDdlArtifact(
            String snapshot,
            String source,
            PreparedSchema prepared,
            Path documentRoot,
            Path outputRoot,
            DatabasePlatform platform,
            Dialect dialect,
            String timestamp,
            List<String> artifactIndex,
            List<String> validationIssues,
            List<String> failures,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> withIssues,
            Map<DatabasePlatform, Integer> blocked,
            Map<DatabasePlatform, Integer> failed,
            Map<String, Integer> artifactCounts) {

        String baseName = safeArtifactBaseName(prepared.schema(), documentRoot.getFileName().toString());
        Path platformDirectory = documentRoot.resolve("ddl").resolve(platform.commandLineName());
        Path target = platformDirectory.resolve(outputFileNamer.scriptFileName(
                baseName, platform, OutputFileNamer.ScriptKind.DDL, timestamp));

        MappingAssessment mapping = mappingAssessment(dialect, prepared.schema());
        if (mapping.fatal()) {
            blocked.compute(platform, (key, value) -> value + 1);
            for (ValidationFinding finding : mapping.findings()) {
                validationIssues.add(csvLine(snapshot, source, platform.commandLineName(), finding.stage(),
                        finding.location(), finding.code(), finding.message(), finding.fragment()));
            }
            addArtifact(artifactIndex, artifactCounts, snapshot, source, platform.commandLineName(), "DDL",
                    "BLOCKED_BY_MAPPING", target, mapping.findings().size(),
                    "Fatal dialect mapping finding(s)", outputRoot);
            return;
        }

        try {
            Files.createDirectories(platformDirectory);
            String sql = new DdlGenerator(dialect).generate(prepared.schema(), prepared.validationReport());
            List<ValidationFinding> findings = new ArrayList<>(mapping.findings());
            findings.addAll(validate(platform, sql));
            Files.writeString(target, sql, StandardCharsets.UTF_8);

            if (findings.isEmpty()) {
                generated.compute(platform, (key, value) -> value + 1);
                addArtifact(artifactIndex, artifactCounts, snapshot, source, platform.commandLineName(), "DDL",
                        "GENERATED", target, 0, "", outputRoot);
            } else {
                withIssues.compute(platform, (key, value) -> value + 1);
                addArtifact(artifactIndex, artifactCounts, snapshot, source, platform.commandLineName(), "DDL",
                        "GENERATED_WITH_ISSUES", target, findings.size(), "", outputRoot);
                for (ValidationFinding finding : findings) {
                    validationIssues.add(csvLine(snapshot, source, platform.commandLineName(), finding.stage(),
                            finding.location(), finding.code(), finding.message(), finding.fragment()));
                }
            }
        } catch (Exception exception) {
            failed.compute(platform, (key, value) -> value + 1);
            recordArtifactFailure(snapshot, source, "DDL", platform.commandLineName(), target, exception,
                    artifactIndex, failures, outputRoot);
        }
    }

    private void generateBatchDiagrams(
            Path outputRoot,
            List<Table> tables,
            List<String> artifactIndex,
            List<String> failures,
            Map<String, Integer> artifactCounts) {
        Path batchRoot = outputRoot.resolve("batch-diagrams");
        try {
            Path mermaidRoot = Files.createDirectories(batchRoot.resolve("mermaid"));
            MermaidBatchDiagramExporter.Result result = mermaidBatchExporter.export(tables);
            writeBatchArtifact(mermaidRoot.resolve("schema-er.mmd"), result.er(), "BATCH_MERMAID_ER",
                    artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(mermaidRoot.resolve("schema-conceptual-erd.mmd"), result.conceptualErd(),
                    "BATCH_MERMAID_CONCEPTUAL_ERD", artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(mermaidRoot.resolve("schema-dependency.mmd"), result.dependency(),
                    "BATCH_MERMAID_DEPENDENCY", artifactIndex, artifactCounts, outputRoot);
            writeMermaidBatchReports(mermaidRoot, result);
        } catch (Exception exception) {
            failures.add(csvLine("", "", "BATCH_MERMAID", "",
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            artifactIndex.add(csvLine("", "", "", "BATCH_MERMAID", "FAILED", "", "0",
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
        }

        try {
            Path graphvizRoot = Files.createDirectories(batchRoot.resolve("graphviz"));
            GraphvizBatchDiagramExporter.Result result = graphvizBatchExporter.export(tables);
            writeBatchArtifact(graphvizRoot.resolve("schema-conceptual-erd.dot"), result.conceptualErd(),
                    "BATCH_GRAPHVIZ_CONCEPTUAL_ERD", artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(graphvizRoot.resolve("schema-dependency.dot"), result.dependency(),
                    "BATCH_GRAPHVIZ_DEPENDENCY", artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(graphvizRoot.resolve("schema-clustered.dot"), result.clusteredDependency(),
                    "BATCH_GRAPHVIZ_CLUSTERED", artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(graphvizRoot.resolve("schema-compact.dot"), result.compactDependency(),
                    "BATCH_GRAPHVIZ_COMPACT", artifactIndex, artifactCounts, outputRoot);
            writeBatchArtifact(graphvizRoot.resolve("schema-overview.dot"), result.overviewDependency(),
                    "BATCH_GRAPHVIZ_OVERVIEW", artifactIndex, artifactCounts, outputRoot);
            writeGraphvizBatchReports(graphvizRoot, result);
        } catch (Exception exception) {
            failures.add(csvLine("", "", "BATCH_GRAPHVIZ", "",
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            artifactIndex.add(csvLine("", "", "", "BATCH_GRAPHVIZ", "FAILED", "", "0",
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
        }
    }

    private void writeBatchArtifact(
            Path target,
            String content,
            String artifact,
            List<String> artifactIndex,
            Map<String, Integer> artifactCounts,
            Path outputRoot) throws Exception {
        Files.writeString(target, content, StandardCharsets.UTF_8);
        increment(artifactCounts, artifact + ":GENERATED");
        artifactIndex.add(csvLine("", "", "", artifact, "GENERATED",
                normalize(outputRoot.relativize(target)), "0", ""));
    }

    private void writeMermaidBatchReports(Path root, MermaidBatchDiagramExporter.Result result) throws Exception {
        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (MermaidBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(issue.code(), issue.sourceTable(), issue.targetTable(),
                    Integer.toString(issue.occurrences()), issue.detail()));
        }
        Files.writeString(root.resolve("issues.csv"), String.join(System.lineSeparator(), issues)
                + System.lineSeparator(), StandardCharsets.UTF_8);
        String summary = "SchemaForge batch Mermaid summary" + System.lineSeparator()
                + "=================================" + System.lineSeparator()
                + "Table definitions       : " + result.tableDefinitions() + System.lineSeparator()
                + "Distinct table names    : " + result.distinctTableNames() + System.lineSeparator()
                + "Duplicate table names   : " + result.duplicateTableNames() + System.lineSeparator()
                + "Exported unique tables  : " + result.exportedTables() + System.lineSeparator()
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + System.lineSeparator()
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + System.lineSeparator()
                + "Issues                   : " + result.issues().size() + System.lineSeparator();
        Files.writeString(root.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private void writeGraphvizBatchReports(Path root, GraphvizBatchDiagramExporter.Result result) throws Exception {
        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (GraphvizBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(issue.code(), issue.sourceTable(), issue.targetTable(),
                    Integer.toString(issue.occurrences()), issue.detail()));
        }
        Files.writeString(root.resolve("issues.csv"), String.join(System.lineSeparator(), issues)
                + System.lineSeparator(), StandardCharsets.UTF_8);
        String summary = "SchemaForge batch Graphviz summary" + System.lineSeparator()
                + "=================================" + System.lineSeparator()
                + "Table definitions       : " + result.tableDefinitions() + System.lineSeparator()
                + "Distinct table names    : " + result.distinctTableNames() + System.lineSeparator()
                + "Duplicate table names   : " + result.duplicateTableNames() + System.lineSeparator()
                + "Exported unique tables  : " + result.exportedTables() + System.lineSeparator()
                + "Connected tables        : " + result.connectedTables() + System.lineSeparator()
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + System.lineSeparator()
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + System.lineSeparator()
                + "Issues                   : " + result.issues().size() + System.lineSeparator();
        Files.writeString(root.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private MappingAssessment mappingAssessment(Dialect dialect, DatabaseSchema schema) {
        var assessment = datatypeCompatibilityAnalyzer.analyze(schema, dialect);
        List<ValidationFinding> findings = assessment.issues().stream()
                .map(issue -> new ValidationFinding(
                        "DIALECT_MAPPING", issue.path(), issue.code(), issue.message(), ""))
                .toList();
        return new MappingAssessment(findings, assessment.blocking());
    }

    private List<ValidationFinding> validate(DatabasePlatform platform, String sql) {
        return switch (platform) {
            case ORACLE -> oracleSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION", "line " + issue.lineNumber(),
                            issue.code(), issue.message(), issue.fragment())).toList();
            case POSTGRESQL -> postgreSqlSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(),
                            issue.message(), issue.fragment())).toList();
            case SQLSERVER -> sqlServerValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
            case DB2_ZOS -> db2ZosValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
        };
    }

    private void verifyDialectInvariants(DatabasePlatform platform, Dialect dialect) {
        if (platform != DatabasePlatform.POSTGRESQL) return;
        String probe = dialect.qualifyIndexName(
                com.behsazan.schemaforge.domain.valueobject.QualifiedName.of("TSTSHMA", "SCHEMAFORGE_PROBE"),
                "ix_schemaforge_probe");
        if (probe.contains(".")) {
            throw new IllegalStateException(
                    "PostgreSQL dialect regression: CREATE INDEX name is schema-qualified: " + probe);
        }
    }

    private static Path documentOutputDirectory(Path outputRoot, Path inputRoot, Path snapshotPath) {
        Path relative = inputRoot.relativize(snapshotPath);
        String fileName = relative.getFileName().toString();
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(".schema.json")
                ? fileName.substring(0, fileName.length() - ".schema.json".length())
                : stripExtension(fileName);
        Path parent = relative.getParent();
        Path documentsRoot = outputRoot.resolve("documents");
        return parent == null ? documentsRoot.resolve(base) : documentsRoot.resolve(parent).resolve(base);
    }

    private static String safeArtifactBaseName(DatabaseSchema schema, String fallback) {
        if (schema.tables().size() == 1) {
            Table table = schema.tables().getFirst();
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value()).orElse(schema.name().value());
            return sanitizeLogicalName(schemaName + "." + table.qualifiedName().name().value());
        }
        return sanitizeLogicalName(fallback);
    }

    private static String sanitizeLogicalName(String value) {
        String result = value == null ? "schema" : value.trim();
        result = result.replace('/', '_').replace('\\', '_');
        return result.isBlank() ? "schema" : result;
    }

    private static void addArtifact(
            List<String> artifactIndex,
            Map<String, Integer> artifactCounts,
            String snapshot,
            String source,
            String platform,
            String artifact,
            String status,
            Path target,
            int issueCount,
            String error,
            Path relativeRoot) {
        increment(artifactCounts, artifact + ":" + status);
        Path path = relativeRoot == null ? target : relativeRoot.relativize(target);
        artifactIndex.add(csvLine(snapshot, source, platform, artifact, status,
                normalize(path), Integer.toString(issueCount), error));
    }

    private static void recordArtifactFailure(
            String snapshot,
            String source,
            String artifact,
            String platform,
            Path target,
            Exception exception,
            List<String> artifactIndex,
            List<String> failures,
            Path relativeRoot) {
        String error = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
        Path path = relativeRoot == null ? target : relativeRoot.relativize(target);
        artifactIndex.add(csvLine(snapshot, source, platform, artifact, "FAILED",
                normalize(path), "0", error));
        failures.add(csvLine(snapshot, source, artifact, platform, error));
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
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
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-artifact-audit").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static List<DatabasePlatform> configuredPlatforms() {
        String value = trimToNull(System.getProperty(PLATFORMS));
        if (value == null) return DEFAULT_PLATFORMS;
        Set<DatabasePlatform> result = new LinkedHashSet<>();
        for (String token : value.split("[,;\\s]+")) {
            if (!token.isBlank()) result.add(DatabasePlatform.parse(token));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No platform selected by " + PLATFORMS);
        return List.copyOf(result);
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static void validateNonOverlapping(Path inputRoot, Path outputRoot) {
        Path normalizedInput = inputRoot.toAbsolutePath().normalize();
        Path normalizedOutput = outputRoot.toAbsolutePath().normalize();
        if (normalizedInput.equals(normalizedOutput)
                || normalizedInput.startsWith(normalizedOutput)
                || normalizedOutput.startsWith(normalizedInput)) {
            throw new IllegalArgumentException(
                    "Input and output directories must not overlap: " + normalizedOutput);
        }
    }

    private static void cleanOutputDirectory(Path inputRoot, Path outputRoot) throws Exception {
        validateNonOverlapping(inputRoot, outputRoot);
        Path normalizedOutput = outputRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedOutput)) return;
        try (var paths = Files.walk(normalizedOutput)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String textSummary(
            Path inputRoot,
            Path outputRoot,
            int snapshotsDiscovered,
            int processedSnapshots,
            int snapshotFailures,
            int staleParserSnapshots,
            int totalTables,
            List<DatabasePlatform> platforms,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> withIssues,
            Map<DatabasePlatform, Integer> blocked,
            Map<DatabasePlatform, Integer> failed,
            Map<String, Integer> artifactCounts,
            int validationIssueCount,
            int artifactFailureCount,
            boolean batchDiagrams) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append("SchemaForge canonical JSON full artifact audit").append(nl)
                .append("============================================").append(nl)
                .append("Input snapshots      : ").append(inputRoot).append(nl)
                .append("Output root          : ").append(outputRoot).append(nl)
                .append("Snapshots discovered : ").append(snapshotsDiscovered).append(nl)
                .append("Snapshots processed  : ").append(processedSnapshots).append(nl)
                .append("Snapshot failures    : ").append(snapshotFailures).append(nl)
                .append("Stale parser sources : ").append(staleParserSnapshots).append(nl)
                .append("Table definitions    : ").append(totalTables).append(nl)
                .append("Batch diagrams       : ").append(batchDiagrams).append(nl)
                .append("DDL validation issues: ").append(validationIssueCount).append(nl)
                .append("Artifact failures    : ").append(artifactFailureCount).append(nl);

        for (DatabasePlatform platform : platforms) {
            out.append(nl).append(platform.commandLineName()).append(nl)
                    .append("  DDL generated       : ").append(generated.get(platform)).append(nl)
                    .append("  DDL with issues     : ").append(withIssues.get(platform)).append(nl)
                    .append("  DDL blocked mapping : ").append(blocked.get(platform)).append(nl)
                    .append("  DDL failed          : ").append(failed.get(platform)).append(nl);
        }

        out.append(nl).append("Artifact counters").append(nl)
                .append("-----------------").append(nl);
        artifactCounts.forEach((key, value) -> out.append("  ").append(key).append(" = ").append(value).append(nl));

        out.append(nl)
                .append("Database-dependent artifacts intentionally NOT generated by this offline runner:").append(nl)
                .append("  - Document-vs-Actual comparison Excel workbooks").append(nl)
                .append("  - P8 table/index/column physical comparison against Actual DB metadata").append(nl)
                .append("  - Oracle/SQL Server metadata-based CRUD artifacts").append(nl)
                .append("Reason: these require a live/real MetadataRepository; canonical JSON alone is Desired Design.").append(nl);
        return out.toString();
    }

    private static String databaseDependentArtifactsReport() {
        String nl = System.lineSeparator();
        return "artifact,platform,status,reason" + nl
                + csvLine("COMPARE_EXCEL", "ALL", "NOT_GENERATED_REQUIRES_ACTUAL_DB",
                "Document-vs-Actual comparison requires a live MetadataRepository") + nl
                + csvLine("P8_PHYSICAL_COMPARE", "ALL", "NOT_GENERATED_REQUIRES_ACTUAL_DB",
                "Table/index/column physical comparison requires Actual database metadata") + nl
                + csvLine("METADATA_CRUD", "ORACLE", "NOT_GENERATED_REQUIRES_ACTUAL_DB",
                "Oracle CRUD generation is metadata-based") + nl
                + csvLine("METADATA_CRUD", "SQLSERVER", "NOT_GENERATED_REQUIRES_ACTUAL_DB",
                "SQL Server CRUD generation is metadata-based") + nl;
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String text = value == null ? "" : value;
            escaped.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface TextSupplier {
        String get() throws Exception;
    }

    private record MappingAssessment(List<ValidationFinding> findings, boolean fatal) {
    }

    private record ValidationFinding(String stage, String location, String code, String message, String fragment) {
    }
}
