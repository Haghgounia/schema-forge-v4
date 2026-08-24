package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizBatchDiagramExporter;
import com.behsazan.schemaforge.diagram.graphviz.GraphvizDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidBatchDiagramExporter;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramExporter;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces Mermaid and Graphviz artifacts from an already prepared canonical schema.
 *
 * <p>This class owns diagram-file rendering and ledger registration only. It does not parse
 * source documents, modify the canonical model, or decide package orchestration.</p>
 */
public final class DiagramArtifactProducer {
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final MermaidDiagramExporter mermaidDiagramExporter = new MermaidDiagramExporter();
    private final MermaidBatchDiagramExporter mermaidBatchDiagramExporter = new MermaidBatchDiagramExporter();
    private final GraphvizDiagramExporter graphvizDiagramExporter = new GraphvizDiagramExporter();
    private final GraphvizBatchDiagramExporter graphvizBatchDiagramExporter = new GraphvizBatchDiagramExporter();

    public DiagramArtifactProducer(ArtifactNamingPolicy artifactNamingPolicy) {
        if (artifactNamingPolicy == null) {
            throw new IllegalArgumentException("artifactNamingPolicy is required");
        }
        this.artifactNamingPolicy = artifactNamingPolicy;
    }

    /** Writes the per-document Mermaid ER artifact. */
    public void writeMermaidArtifact(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        String mermaid = mermaidDiagramExporter.export(schema.tables(), DiagramExportOptions.erAll());
        Path path = output.resolve(artifactNamingPolicy.mermaidErRelativePath(baseName, timestamp));
        Files.createDirectories(path.getParent());
        Files.writeString(path, mermaid, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                baseName + ":er", ArtifactPaths.relative(output, path),
                "text/plain", "MermaidDiagramExporter");
    }

    /** Writes the per-document Graphviz ER artifact. */
    public void writeGraphvizArtifact(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        String dot = graphvizDiagramExporter.export(schema.tables(), DiagramExportOptions.erAll());
        Path path = output.resolve(artifactNamingPolicy.graphvizErRelativePath(baseName, timestamp));
        Files.createDirectories(path.getParent());
        Files.writeString(path, dot, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                baseName + ":er", ArtifactPaths.relative(output, path),
                "text/vnd.graphviz", "GraphvizDiagramExporter");
    }

    /** Writes field-free conceptual Mermaid and Graphviz ERD artifacts. */
    public void writeConceptualErdArtifacts(
            DatabaseSchema schema, Path output, String baseName, String timestamp,
            ArtifactGenerationContext context) throws IOException {
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.CONCEPTUAL_ERD)
                .build();
        String mermaid = mermaidDiagramExporter.export(schema.tables(), options);
        String dot = graphvizDiagramExporter.export(schema.tables(), options);
        Path mermaidPath = output.resolve(
                artifactNamingPolicy.mermaidConceptualRelativePath(baseName, timestamp));
        Path graphvizPath = output.resolve(
                artifactNamingPolicy.graphvizConceptualRelativePath(baseName, timestamp));
        Files.createDirectories(mermaidPath.getParent());
        Files.createDirectories(graphvizPath.getParent());
        Files.writeString(mermaidPath, mermaid, StandardCharsets.UTF_8);
        Files.writeString(graphvizPath, dot, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                baseName + ":conceptual-erd", ArtifactPaths.relative(output, mermaidPath),
                "text/plain", "MermaidDiagramExporter");
        context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                baseName + ":conceptual-erd", ArtifactPaths.relative(output, graphvizPath),
                "text/vnd.graphviz", "GraphvizDiagramExporter");
    }

    /** Writes batch-level Mermaid ER, conceptual, dependency, issue, and summary artifacts. */
    public void writeBatchMermaidArtifacts(
            List<Table> tableDefinitions, Path output, ArtifactGenerationContext context) throws IOException {
        MermaidBatchDiagramExporter.Result result = mermaidBatchDiagramExporter.export(tableDefinitions);
        Files.createDirectories(output.resolve(artifactNamingPolicy.batchMermaidDirectory()));

        Path erPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ER));
        Path conceptualPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.CONCEPTUAL_ERD));
        Path dependencyPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.DEPENDENCY));
        Files.writeString(erPath, result.er(), StandardCharsets.UTF_8);
        Files.writeString(conceptualPath, result.conceptualErd(), StandardCharsets.UTF_8);
        Files.writeString(dependencyPath, result.dependency(), StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:schema-er", ArtifactPaths.relative(output, erPath),
                "text/plain", "MermaidBatchDiagramExporter");
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:conceptual-erd", ArtifactPaths.relative(output, conceptualPath),
                "text/plain", "MermaidBatchDiagramExporter");
        context.ledger().generated(context, ArtifactType.MERMAID_DIAGRAM, null,
                "batch:dependency", ArtifactPaths.relative(output, dependencyPath),
                "text/plain", "MermaidBatchDiagramExporter");

        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (MermaidBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(
                    issue.code(),
                    issue.sourceTable(),
                    issue.targetTable(),
                    Integer.toString(issue.occurrences()),
                    issue.detail()));
        }
        Path issuesPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ISSUES));
        Files.writeString(issuesPath, String.join("\n", issues) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.ISSUE_REPORT, null,
                "batch:mermaid", ArtifactPaths.relative(output, issuesPath),
                "text/csv", "MermaidBatchDiagramExporter");

        String summary = "SchemaForge batch Mermaid summary\n"
                + "=================================\n"
                + "Table definitions       : " + result.tableDefinitions() + "\n"
                + "Distinct table names    : " + result.distinctTableNames() + "\n"
                + "Duplicate table names   : " + result.duplicateTableNames() + "\n"
                + "Exported unique tables  : " + result.exportedTables() + "\n"
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + "\n"
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + "\n"
                + "Issues                   : " + result.issues().size() + "\n"
                + "Duplicate policy         : EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION\n";
        Path summaryPath = output.resolve(artifactNamingPolicy.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.SUMMARY));
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "batch:mermaid", ArtifactPaths.relative(output, summaryPath),
                "text/plain", "MermaidBatchDiagramExporter");
    }

    /** Writes batch-level Graphviz diagram, issue, and summary artifacts. */
    public void writeBatchGraphvizArtifacts(
            List<Table> tableDefinitions, Path output, ArtifactGenerationContext context) throws IOException {
        GraphvizBatchDiagramExporter.Result result = graphvizBatchDiagramExporter.export(tableDefinitions);
        Files.createDirectories(output.resolve(artifactNamingPolicy.batchGraphvizDirectory()));

        Path conceptualPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.CONCEPTUAL_ERD));
        Path dependencyPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.DEPENDENCY));
        Path clusteredPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.CLUSTERED));
        Path compactPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.COMPACT));
        Path overviewPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.OVERVIEW));
        Files.writeString(conceptualPath, result.conceptualErd(), StandardCharsets.UTF_8);
        Files.writeString(dependencyPath, result.dependency(), StandardCharsets.UTF_8);
        Files.writeString(clusteredPath, result.clusteredDependency(), StandardCharsets.UTF_8);
        Files.writeString(compactPath, result.compactDependency(), StandardCharsets.UTF_8);
        Files.writeString(overviewPath, result.overviewDependency(), StandardCharsets.UTF_8);

        for (Path diagram : List.of(conceptualPath, dependencyPath, clusteredPath, compactPath, overviewPath)) {
            context.ledger().generated(context, ArtifactType.GRAPHVIZ_DIAGRAM, null,
                    "batch:" + diagram.getFileName().toString().replace("schema-", "").replace(".dot", ""),
                    ArtifactPaths.relative(output, diagram), "text/vnd.graphviz",
                    "GraphvizBatchDiagramExporter");
        }

        List<String> issues = new ArrayList<>();
        issues.add("code,source_table,target_table,occurrences,detail");
        for (GraphvizBatchDiagramExporter.Issue issue : result.issues()) {
            issues.add(csvLine(
                    issue.code(),
                    issue.sourceTable(),
                    issue.targetTable(),
                    Integer.toString(issue.occurrences()),
                    issue.detail()));
        }
        Path issuesPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.ISSUES));
        Files.writeString(issuesPath, String.join("\n", issues) + "\n", StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.ISSUE_REPORT, null,
                "batch:graphviz", ArtifactPaths.relative(output, issuesPath),
                "text/csv", "GraphvizBatchDiagramExporter");

        String summary = "SchemaForge batch Graphviz summary\n"
                + "=================================\n"
                + "Table definitions       : " + result.tableDefinitions() + "\n"
                + "Distinct table names    : " + result.distinctTableNames() + "\n"
                + "Duplicate table names   : " + result.duplicateTableNames() + "\n"
                + "Exported unique tables  : " + result.exportedTables() + "\n"
                + "Connected tables        : " + result.connectedTables() + "\n"
                + "Physical FKs (exported) : " + result.physicalForeignKeys() + "\n"
                + "Resolved physical FKs   : " + result.resolvedPhysicalForeignKeys() + "\n"
                + "Issues                   : " + result.issues().size() + "\n"
                + "Duplicate policy         : EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION\n"
                + "Full profile             : disconnected=true, labels=true, clusterBySchema=true\n"
                + "Compact profile          : disconnected=false, labels=true, clusterBySchema=true\n"
                + "Overview profile         : disconnected=false, labels=false, clusterBySchema=true\n"
                + "Renderer                 : DOT_ONLY_NO_GRAPHVIZ_EXECUTION\n";
        Path summaryPath = output.resolve(artifactNamingPolicy.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.SUMMARY));
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        context.ledger().generated(context, ArtifactType.SUMMARY_REPORT, null,
                "batch:graphviz", ArtifactPaths.relative(output, summaryPath),
                "text/plain", "GraphvizBatchDiagramExporter");
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }
}
