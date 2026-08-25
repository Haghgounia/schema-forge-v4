package com.behsazan.schemaforge.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Exposes REST endpoints for schema forge operations.
 *
 * @since 4.1
 */
@RestController
@RequestMapping("/api/v1/generate")
@Tag(name = "Schema Generation", description = "Generate Oracle, PostgreSQL, Db2 for z/OS, SQL Server, and MySQL DDL from Word, legacy Word, ZIP, or Enterprise Architect XML/XMI")
public class SchemaForgeController {
    private static final DateTimeFormatter ARCHIVE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);
    private final SchemaForgeApiService service;

    public SchemaForgeController(SchemaForgeApiService service) {
        this.service = service;
    }

    @PostMapping(value = "/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from one Word specification")
    public ResponseEntity<byte[]> word(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @RequestParam(value = "auditProfile", required = false, defaultValue = "AUTO") String auditProfile)
            throws IOException {
        return zip(service.generateFromWord(file, includeAuditFields, auditProfile),
                archiveName("schemaforge-word-output"));
    }

    @PostMapping(value = "/legacy-word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from one legacy Word table specification")
    public ResponseEntity<byte[]> legacyWord(
            @RequestPart("file") MultipartFile file,
            @RequestParam("schema") String schema,
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @RequestParam(value = "auditProfile", required = false, defaultValue = "AUTO") String auditProfile)
            throws IOException {
        return zip(service.generateFromLegacyWord(file, schema, includeAuditFields, auditProfile),
                archiveName("schemaforge-legacy-word-output"));
    }

    @PostMapping(value = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from a ZIP containing Word specifications")
    public ResponseEntity<byte[]> zip(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @RequestParam(value = "auditProfile", required = false, defaultValue = "AUTO") String auditProfile)
            throws IOException {
        return zip(service.generateFromZip(file, includeAuditFields, auditProfile),
                archiveName("schemaforge-batch-output"));
    }

    @PostMapping(value = "/ea-xml", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from Enterprise Architect XML/XMI")
    public ResponseEntity<byte[]> eaXml(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "schema", required = false) String schema,
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @RequestParam(value = "auditProfile", required = false, defaultValue = "AUTO") String auditProfile)
            throws IOException {
        return zip(service.generateFromEaXml(file, schema, includeAuditFields, auditProfile),
                archiveName("schemaforge-ea-output"));
    }

    private static String archiveName(String baseName) {
        return baseName + "_" + LocalDateTime.now().format(ARCHIVE_TIME) + ".zip";
    }

    private static ResponseEntity<byte[]> zip(byte[] content, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

}
