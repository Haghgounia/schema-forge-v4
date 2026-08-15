package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.domain.model.Table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/** Writes Mermaid text as a UTF-8 .mmd artifact. */
public final class MermaidDiagramFileWriter {
    private final MermaidDiagramExporter exporter;

    public MermaidDiagramFileWriter() {
        this(new MermaidDiagramExporter());
    }

    public MermaidDiagramFileWriter(MermaidDiagramExporter exporter) {
        this.exporter = Objects.requireNonNull(exporter);
    }

    public Path write(Path outputFile, Collection<Table> tables, DiagramExportOptions options) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputFile, exporter.export(tables, options), StandardCharsets.UTF_8);
        return outputFile;
    }
}
