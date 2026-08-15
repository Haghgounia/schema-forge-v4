package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.domain.model.Table;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

    public MermaidDiagramGenerationService() {
        this(new CanonicalJsonDiagramInputLoader(), new MermaidDiagramExporter());
    }

    MermaidDiagramGenerationService(
            CanonicalJsonDiagramInputLoader loader,
            MermaidDiagramExporter exporter) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
    }

    /** Generates one deterministic Mermaid artifact from unique production canonical input. */
    public GeneratedMermaidDiagram generate(Path input, DiagramExportOptions options) throws IOException {
        Objects.requireNonNull(options, "options must not be null");
        List<Table> tables = loader.loadTables(input);
        String content = exporter.export(tables, options);
        return new GeneratedMermaidDiagram(
                outputFileName(options), content, options.type(), options.scope(), tables.size());
    }

    static String outputFileName(DiagramExportOptions options) {
        String selector = switch (options.scope()) {
            case TABLE, TABLE_WITH_DEPENDENCIES -> token(options.rootTable().toString());
            case SCHEMA -> token(options.schema().value());
            case SELECTED_TABLES -> "selected_" + options.selectedTables().size() + "_tables";
            case ALL -> "schema";
        };
        StringBuilder name = new StringBuilder(selector)
                .append("__")
                .append(options.type().name().toLowerCase(Locale.ROOT))
                .append('-')
                .append(options.scope().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (options.scope() == DiagramScope.TABLE_WITH_DEPENDENCIES) {
            name.append("-depth-").append(options.dependencyDepth());
        }
        return name.append(".mmd").toString();
    }

    private static String token(String value) {
        String normalized = value == null ? "schema" : value.trim();
        if (normalized.isEmpty()) {
            return "schema";
        }
        return normalized.replaceAll("[^A-Za-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}
