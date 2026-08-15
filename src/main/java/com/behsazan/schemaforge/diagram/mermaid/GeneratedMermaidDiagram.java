package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One generated Mermaid artifact and its deterministic output identity. */
public record GeneratedMermaidDiagram(
        String fileName,
        String content,
        DiagramType type,
        DiagramScope scope,
        int inputTableCount) {

    public GeneratedMermaidDiagram {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        if (inputTableCount < 1) {
            throw new IllegalArgumentException("inputTableCount must be positive");
        }
    }

    public byte[] utf8() {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
