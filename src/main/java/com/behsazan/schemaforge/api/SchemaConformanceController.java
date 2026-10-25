package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditService;
import com.behsazan.schemaforge.conformance.SchemaConformanceMultiPlatformReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import com.behsazan.schemaforge.conformance.SchemaConformanceScope;
import com.behsazan.schemaforge.conformance.SchemaConformanceAuditPlan;
import com.behsazan.schemaforge.conformance.SchemaConformanceRuleCatalog;
import com.behsazan.schemaforge.conformance.SchemaConformanceSelectedResult;
import com.behsazan.schemaforge.conformance.SchemaConformanceSelectedCohortResult;
import com.behsazan.schemaforge.conformance.SchemaConformanceSelectedTarget;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final RuntimeMetadataConnectionRegistry selectedConnections;

    @Autowired
    public SchemaConformanceController(SchemaConformanceAuditService service,
                                       RuntimeMetadataConnectionRegistry selectedConnections) {
        this.service = service;
        this.selectedConnections = selectedConnections;
    }

    /** Preserves compatibility for legacy unit tests and programmatic callers. */
    public SchemaConformanceController(SchemaConformanceAuditService service) {
        this(service, null);
    }

    @GetMapping(value = "/table", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "[Legacy] Audit table using selected platforms or configured metadata repositories",
            description = "Backward-compatible endpoint: when no browser metadata token is present, platform selection uses server JDBC configuration. Prefer /selected/table for explicit, traceable auditing.",
            deprecated = true,
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
            summary = "[Legacy] Audit schema using selected platforms or configured metadata repositories",
            description = "Backward-compatible endpoint: when no browser metadata token is present, platform selection uses server JDBC configuration. Prefer /selected/schema for explicit, traceable auditing.",
            deprecated = true,
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

    /**
     * Explicit browser audit workflow. The plan only reads the selected session metadata;
     * it cannot connect to configured fallback DBMSs or execute DDL.
     */
    @GetMapping(value = "/selected/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Identify the exact JDBC target and eight live-metadata rule families before auditing")
    public ResponseEntity<SchemaConformanceAuditPlan> selectedPlan(
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token) {
        var selected = selectedConnections().describe(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SchemaConformanceAuditPlan(
                SchemaConformanceAuditPlan.CONTRACT,
                SchemaConformanceSelectedTarget.of(selected),
                SchemaConformanceRuleCatalog.RULES,
                true,
                List.of("No table rows or application data are scanned",
                        "No DDL/DML, database writes, or object changes are executed",
                        "No performance, access-control or vulnerability assessment is performed")));
    }

    @GetMapping(value = "/selected/table", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit an existing table using ONLY the explicitly selected JDBC session")
    public ResponseEntity<SchemaConformanceSelectedResult> selectedTable(
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
            @RequestParam("schema") String schema,
            @RequestParam("table") String table) {
        var selected = selectedConnections().describe(token);
        DatabasePlatform platform = DatabasePlatform.parse(selected.platform());
        var repository = selectedConnections().resolve(token, platform);
        var report = service.auditSelectedTable(platform, schema, table, repository);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new SchemaConformanceSelectedResult(SchemaConformanceSelectedTarget.of(selected), report));
    }

    @GetMapping(value = "/selected/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit a live schema using ONLY the explicitly selected JDBC session")
    public ResponseEntity<SchemaConformanceSelectedResult> selectedSchema(
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
            @RequestParam("schema") String schema) {
        var selected = selectedConnections().describe(token);
        DatabasePlatform platform = DatabasePlatform.parse(selected.platform());
        var repository = selectedConnections().resolve(token, platform);
        var report = service.auditSelectedSchema(platform, schema, repository);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new SchemaConformanceSelectedResult(SchemaConformanceSelectedTarget.of(selected), report));
    }

    /**
     * POST carries a list but is strictly read-only. No SQL mutations are executed.
     * The requested list is returned so omissions cannot be mistaken for success.
     */
    @PostMapping(value = "/selected/tables", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read-only audit of an explicitly selected table cohort (up to 100 tables)")
    public ResponseEntity<SchemaConformanceSelectedCohortResult> selectedTables(
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
            @RequestBody SelectedTablesRequest request) {
        if (request == null) throw new IllegalArgumentException("Audit cohort request is required");
        String schema = request.schema();
        List<String> names = SchemaConformanceAuditService.normalizeCohortNames(request.tables());
        var selected = selectedConnections().describe(token);
        DatabasePlatform platform = DatabasePlatform.parse(selected.platform());
        var repository = selectedConnections().resolve(token, platform);
        var report = service.auditSelectedTables(platform, schema, names, repository);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new SchemaConformanceSelectedCohortResult(
                        SchemaConformanceSelectedTarget.of(selected), names, report));
    }

    public record SelectedTablesRequest(String schema, List<String> tables) { }

    private RuntimeMetadataConnectionRegistry selectedConnections() {
        if (selectedConnections == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Explicit metadata connection selection is unavailable");
        }
        return selectedConnections;
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
