package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Exposes SQL Server metadata-based CRUD stored-procedure generation. */
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", exception.getMessage()));
    }
}
