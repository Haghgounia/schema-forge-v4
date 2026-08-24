package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * REST adapter for generating SQL Server CRUD procedures from live database metadata.
 *
 * <p>The controller validates the request envelope, delegates metadata resolution and SQL
 * generation to {@link SqlServerCrudGenerationService}, and returns the generated script as
 * a UTF-8 attachment. HTTP failures are mapped centrally by the C7 REST error contract.
 * No database access or procedure rendering is performed in the web layer.</p>
 */
@RestController
@RequestMapping("/api/v1/generate/sqlserver")
@Tag(name = "SQL Server CRUD Generation",
        description = "Generate SQL Server CRUD stored procedures directly from sys.* metadata")
public class SqlServerCrudController {
    private final SqlServerCrudGenerationService service;

    public SqlServerCrudController(SqlServerCrudGenerationService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/crud",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/sql")
    @Operation(summary = "Generate SQL Server CRUD procedures from live table metadata")
    public ResponseEntity<byte[]> generate(@RequestBody SqlServerCrudRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        var result = service.generate(request.schema(), request.table());
        byte[] content = result.sql().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/sql;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

}
