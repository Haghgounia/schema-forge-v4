package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramExporter;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Read-only Mermaid diagrams of actual DBA-created schemas/tables from the explicit connection. */
@RestController
@RequestMapping("/api/v1/diagram/mermaid")
public class SelectedMetadataDiagramController {
    private static final int MAX_TABLES = 150;
    private final RuntimeMetadataConnectionRegistry selected;
    private final MermaidDiagramExporter exporter = new MermaidDiagramExporter();

    public SelectedMetadataDiagramController(RuntimeMetadataConnectionRegistry selected) {
        this.selected = selected;
    }

    @GetMapping(value = "/selected", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> generate(
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
            @RequestParam("schema") String schema,
            @RequestParam(value = "table", required = false) String table,
            @RequestParam(value = "type", defaultValue = "er") String type,
            @RequestParam(value = "includeColumns", defaultValue = "true") boolean includeColumns,
            @RequestParam(value = "includeDataTypes", defaultValue = "true") boolean includeDataTypes,
            @RequestParam(value = "includePrimaryKeys", defaultValue = "true") boolean includePrimaryKeys,
            @RequestParam(value = "includeForeignKeys", defaultValue = "true") boolean includeForeignKeys) {
        var identity = selected.describe(token);
        var platform = DatabasePlatform.parse(identity.platform());
        MetadataRepository repository = selected.resolve(token, platform);
        if (schema == null || !schema.trim().matches("[A-Za-z_][A-Za-z0-9_$#-]{0,127}")) {
            throw new IllegalArgumentException("Valid live schema is required");
        }
        String chosenSchema = schema.trim();
        if (!repository.schemaExists(chosenSchema)) {
            throw new IllegalArgumentException("Schema is missing or not visible: " + chosenSchema);
        }
        boolean singleTable = table != null && !table.isBlank();
        List<Table> tables;
        if (singleTable) {
            String tableName = table.trim();
            if (!tableName.matches("[A-Za-z_][A-Za-z0-9_$#-]{0,127}")) {
                throw new IllegalArgumentException("Invalid live table name");
            }
            tables = List.of(repository.findTable(chosenSchema, tableName)
                    .orElseThrow(() -> new IllegalArgumentException("Live table not found or not visible: "
                            + chosenSchema + "." + tableName)));
        } else {
            List<String> names = repository.findTableNames(chosenSchema);
            if (names.isEmpty()) throw new IllegalArgumentException("No visible tables in the selected schema");
            if (names.size() > MAX_TABLES) {
                throw new IllegalArgumentException("Live diagram limit is " + MAX_TABLES
                        + " tables; select one table to continue");
            }
            // Strictly read only the names returned for this schema. Missing privileges do not
            // silently create synthetic tables or relationships.
            java.util.ArrayList<Table> found = new java.util.ArrayList<>();
            for (String name : names) {
                repository.findTable(chosenSchema, name).ifPresent(found::add);
            }
            if (found.isEmpty()) throw new IllegalArgumentException("No readable live tables in schema");
            tables = List.copyOf(found);
        }
        var options = MermaidDiagramController.options(type, singleTable ? "table" : "schema",
                chosenSchema, singleTable ? chosenSchema + "." + table.trim() : null,
                null, 1, includeColumns, includeDataTypes, includePrimaryKeys, includeForeignKeys, false);
        String mermaid = exporter.export(tables, options);
        byte[] data = mermaid.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("schemaforge-live-" + platform.commandLineName() + ".mmd").build());
        headers.setContentLength(data.length);
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
