package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OracleCrudGenerationService;
import com.behsazan.schemaforge.application.SqlServerCrudGenerationService;
import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/** CRUD exclusively from an explicitly chosen runtime metadata connection. */
@RestController
@RequestMapping("/api/v1/generate")
public class SelectedCrudController {
    private final RuntimeMetadataConnectionRegistry profiles;
    private final OracleCrudGenerationService oracle;
    private final SqlServerCrudGenerationService sqlServer;

    public SelectedCrudController(RuntimeMetadataConnectionRegistry profiles,
                                  OracleCrudGenerationService oracle,
                                  SqlServerCrudGenerationService sqlServer) {
        this.profiles = profiles;
        this.oracle = oracle;
        this.sqlServer = sqlServer;
    }

    @PostMapping(value = "/oracle/crud/selected", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = "application/sql")
    public ResponseEntity<byte[]> oracle(@RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
                                          @RequestBody OracleCrudRequest request) {
        if (request == null) throw new IllegalArgumentException("CRUD request body is required");
        var repository = profiles.resolve(token, DatabasePlatform.ORACLE);
        var result = oracle.generateFromRepository(request.schema(), request.table(), repository);
        return sqlFile(result.fileName(), result.sql());
    }

    @PostMapping(value = "/sqlserver/crud/selected", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = "application/sql")
    public ResponseEntity<byte[]> sqlServer(@RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String token,
                                             @RequestBody SqlServerCrudRequest request) {
        if (request == null) throw new IllegalArgumentException("CRUD request body is required");
        var repository = profiles.resolve(token, DatabasePlatform.SQLSERVER);
        var result = sqlServer.generateFromRepository(request.schema(), request.table(), repository);
        return sqlFile(result.fileName(), result.sql());
    }

    private static ResponseEntity<byte[]> sqlFile(String filename, String sql) {
        byte[] data = sql.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/sql;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(data.length);
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
