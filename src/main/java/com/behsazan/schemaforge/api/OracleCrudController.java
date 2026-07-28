package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.OracleCrudGenerationService;
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

/** Exposes Oracle metadata-based CRUD package generation. */
@RestController
@RequestMapping("/api/v1/generate/oracle")
@Tag(name = "Oracle CRUD Generation",
        description = "Generate Oracle CRUD packages directly from Oracle data dictionary metadata")
public class OracleCrudController {
    private final OracleCrudGenerationService service;

    public OracleCrudController(OracleCrudGenerationService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/crud",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/sql")
    @Operation(summary = "Generate one Oracle CRUD package from live table metadata")
    public ResponseEntity<byte[]> generate(@RequestBody OracleCrudRequest request) {
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
