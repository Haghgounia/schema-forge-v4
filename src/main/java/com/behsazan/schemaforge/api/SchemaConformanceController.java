package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only REST entry point for auditing existing database structures. */
@RestController
@RequestMapping("/api/v1/conformance")
@Tag(name = "Schema Conformance", description = "Read-only validation of existing database tables and schemas")
public class SchemaConformanceController {
    private final SchemaConformanceAuditService service;

    public SchemaConformanceController(SchemaConformanceAuditService service) {
        this.service = service;
    }

    @GetMapping(value = "/table", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit one existing database table against SchemaForge validation rules")
    public SchemaConformanceReport table(
            @RequestParam("platform") String platform,
            @RequestParam("schema") String schema,
            @RequestParam("table") String table) {
        return service.auditTable(DatabasePlatform.parse(platform), schema, table);
    }

    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit one existing database schema against SchemaForge validation rules")
    public SchemaConformanceReport schema(
            @RequestParam("platform") String platform,
            @RequestParam("schema") String schema) {
        return service.auditSchema(DatabasePlatform.parse(platform), schema);
    }
}
