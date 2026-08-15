package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.mermaid.GeneratedMermaidDiagram;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Production REST endpoint for Mermaid export from canonical JSON snapshots. */
@RestController
@RequestMapping("/api/v1/diagram/mermaid")
@Tag(name = "Diagram Export", description = "Generate Mermaid diagrams from unique canonical JSON snapshots")
public class MermaidDiagramController {
    private static final MediaType MERMAID_MEDIA_TYPE = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final MermaidDiagramApiService service;

    public MermaidDiagramController(MermaidDiagramApiService service) {
        this.service = service;
    }

    @PostMapping(value = "/canonical-json", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Generate Mermaid from one canonical snapshot or a ZIP of unique canonical snapshots")
    public ResponseEntity<byte[]> canonicalJson(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "er") String type,
            @RequestParam(value = "scope", defaultValue = "all") String scope,
            @RequestParam(value = "schema", required = false) String schema,
            @RequestParam(value = "root", required = false) String root,
            @RequestParam(value = "selected", required = false) String selected,
            @RequestParam(value = "depth", defaultValue = "1") int depth,
            @RequestParam(value = "includeColumns", defaultValue = "true") boolean includeColumns,
            @RequestParam(value = "includeDataTypes", defaultValue = "true") boolean includeDataTypes,
            @RequestParam(value = "includePrimaryKeys", defaultValue = "true") boolean includePrimaryKeys,
            @RequestParam(value = "includeForeignKeys", defaultValue = "true") boolean includeForeignKeys,
            @RequestParam(value = "includeLogicalForeignKeys", defaultValue = "false") boolean includeLogicalForeignKeys)
            throws IOException {

        DiagramExportOptions options = options(
                type, scope, schema, root, selected, depth, includeColumns, includeDataTypes,
                includePrimaryKeys, includeForeignKeys, includeLogicalForeignKeys);
        GeneratedMermaidDiagram artifact = service.generate(file, options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MERMAID_MEDIA_TYPE);
        headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.fileName()).build());
        byte[] content = artifact.utf8();
        headers.setContentLength(content.length);
        headers.add("X-SchemaForge-Diagram-Type", artifact.type().name());
        headers.add("X-SchemaForge-Diagram-Scope", artifact.scope().name());
        headers.add("X-SchemaForge-Input-Tables", Integer.toString(artifact.inputTableCount()));
        return ResponseEntity.ok().headers(headers).body(content);
    }

    static DiagramExportOptions options(
            String type,
            String scope,
            String schema,
            String root,
            String selected,
            int depth,
            boolean includeColumns,
            boolean includeDataTypes,
            boolean includePrimaryKeys,
            boolean includeForeignKeys,
            boolean includeLogicalForeignKeys) {

        DiagramType diagramType = parseEnum(DiagramType.class, type, "type");
        DiagramScope diagramScope = parseEnum(DiagramScope.class, scope, "scope");
        DiagramExportOptions.Builder builder = DiagramExportOptions.builder()
                .type(diagramType)
                .scope(diagramScope)
                .dependencyDepth(depth)
                .includeColumns(includeColumns)
                .includeDataTypes(includeDataTypes)
                .includePrimaryKeys(includePrimaryKeys)
                .includeForeignKeys(includeForeignKeys)
                .includeLogicalForeignKeys(includeLogicalForeignKeys);

        switch (diagramScope) {
            case SCHEMA -> builder.schema(requireText(schema, "schema is required for SCHEMA scope"));
            case TABLE, TABLE_WITH_DEPENDENCIES -> builder.rootTable(parseQualifiedName(
                    requireText(root, "root is required for " + diagramScope + " scope"), "root"));
            case SELECTED_TABLES -> builder.selectedTables(parseSelected(selected));
            case ALL -> {
                // No selector is required.
            }
        }
        return builder.build();
    }

    private static List<QualifiedName> parseSelected(String value) {
        String text = requireText(value, "selected is required for SELECTED_TABLES scope");
        List<QualifiedName> result = new ArrayList<>();
        for (String token : text.split(",")) {
            if (!token.isBlank()) {
                result.add(parseQualifiedName(token.trim(), "selected"));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("selected must contain at least one SCHEMA.TABLE name");
        }
        return List.copyOf(result);
    }

    private static QualifiedName parseQualifiedName(String value, String parameter) {
        int separator = value.indexOf('.');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('.', separator + 1) >= 0) {
            throw new IllegalArgumentException(parameter + " must use SCHEMA.TABLE format: " + value);
        }
        return QualifiedName.of(value.substring(0, separator), value.substring(separator + 1));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String parameter) {
        String normalized = requireText(value, parameter + " must not be blank")
                .trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported " + parameter + " '" + value + "'. Supported values: "
                            + String.join(", ", java.util.Arrays.stream(type.getEnumConstants())
                                    .map(item -> item.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                                    .toList()));
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", exception.getMessage()));
    }
}
