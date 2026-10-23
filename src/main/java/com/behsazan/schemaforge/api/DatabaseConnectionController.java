package com.behsazan.schemaforge.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrative, opt-in JDBC configuration inspection and read-only connection test. */
@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Database Connections", description = "Admin-only effective settings and JDBC connection tests")
public final class DatabaseConnectionController {
    private final DatabaseConnectionAdministrationService service;

    public DatabaseConnectionController(DatabaseConnectionAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Read effective configured JDBC defaults for seven DBMSs, never return passwords")
    public ResponseEntity<List<DatabaseConnectionAdministrationService.ConnectionSummary>> list(
            @RequestHeader(value = DatabaseConnectionAdministrationService.ADMIN_HEADER, required = false)
                    String adminKey) {
        service.authorize(adminKey);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summaries());
    }

    @PostMapping("/test")
    @Operation(summary = "Test one JDBC connection without changing runtime metadata configuration or running SQL")
    public ResponseEntity<DatabaseConnectionAdministrationService.ProbeResult> test(
            @RequestHeader(value = DatabaseConnectionAdministrationService.ADMIN_HEADER, required = false)
                    String adminKey,
            @RequestBody DatabaseConnectionAdministrationService.ProbeRequest request) {
        service.authorize(adminKey);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.probe(request));
    }
}
