package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.domain.model.Table;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Production application service for generating Mermaid from canonical JSON snapshots.
 *
 * <p>The service never performs historical version selection and never invokes any SQL dialect.
 * It uses the same canonical model as DDL generation but remains independent from DDL rendering.</p>
 */
public final class MermaidDiagramGenerationService {
    private final CanonicalJsonDiagramInputLoader loader;
    private final MermaidDiagramExporter exporter;
    private final ArtifactNamingPolicy artifactNamingPolicy;

    public MermaidDiagramGenerationService() {
        this(new CanonicalJsonDiagramInputLoader(), new MermaidDiagramExporter(), new ArtifactNamingPolicy());
    }

    MermaidDiagramGenerationService(
            CanonicalJsonDiagramInputLoader loader,
            MermaidDiagramExporter exporter) {
        this(loader, exporter, new ArtifactNamingPolicy());
    }

    MermaidDiagramGenerationService(
            CanonicalJsonDiagramInputLoader loader,
            MermaidDiagramExporter exporter,
            ArtifactNamingPolicy artifactNamingPolicy) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.artifactNamingPolicy = Objects.requireNonNull(artifactNamingPolicy, "artifactNamingPolicy must not be null");
    }

    /** Generates one deterministic Mermaid artifact from unique production canonical input. */
    public GeneratedMermaidDiagram generate(Path input, DiagramExportOptions options) throws IOException {
        Objects.requireNonNull(options, "options must not be null");
        List<Table> tables = loader.loadTables(input);
        String content = exporter.export(tables, options);
        return new GeneratedMermaidDiagram(
                artifactNamingPolicy.standaloneMermaidFileName(options), content, options.type(), options.scope(), tables.size());
    }

}
