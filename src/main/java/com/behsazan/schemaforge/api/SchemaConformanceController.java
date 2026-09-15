package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceMultiPlatformReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    @Operation(
            summary = "Audit one existing database table against SchemaForge validation rules",
            description = "Select one or more database platforms. A single selection preserves the existing single-report response; multiple selections return a multi-platform aggregate report.",
            responses = @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(
                    oneOf = {SchemaConformanceReport.class, SchemaConformanceMultiPlatformReport.class}))))
    public Object table(
            @Parameter(
                    required = true,
                    description = "Database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @RequestParam("schema") String schema,
            @RequestParam("table") String table) {
        List<DatabasePlatform> selected = RestPlatformSelection.require(platforms);
        List<SchemaConformanceReport> reports = selected.stream()
                .map(platform -> service.auditTable(platform, schema, table))
                .toList();
        return collapse(SchemaConformanceScope.TABLE, schema, table, selected, reports);
    }

    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Audit one existing database schema against SchemaForge validation rules",
            description = "Select one or more database platforms. A single selection preserves the existing single-report response; multiple selections return a multi-platform aggregate report.",
            responses = @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(
                    oneOf = {SchemaConformanceReport.class, SchemaConformanceMultiPlatformReport.class}))))
    public Object schema(
            @Parameter(
                    required = true,
                    description = "Database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @RequestParam("schema") String schema) {
        List<DatabasePlatform> selected = RestPlatformSelection.require(platforms);
        List<SchemaConformanceReport> reports = selected.stream()
                .map(platform -> service.auditSchema(platform, schema))
                .toList();
        return collapse(SchemaConformanceScope.SCHEMA, schema, null, selected, reports);
    }

    private static Object collapse(
            SchemaConformanceScope scope,
            String schema,
            String table,
            List<DatabasePlatform> selected,
            List<SchemaConformanceReport> reports) {
        if (reports.size() == 1) {
            return reports.getFirst();
        }
        return new SchemaConformanceMultiPlatformReport(
                SchemaConformanceMultiPlatformReport.CONTRACT,
                scope,
                schema,
                table,
                selected,
                reports);
    }

}
