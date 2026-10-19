package com.behsazan.schemaforge.glossary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

@RestController
@RequestMapping("/api/v1/glossary")
@ConditionalOnProperty(prefix = "schemaforge.glossary", name = "enabled", havingValue = "true")
@Tag(name = "Glossary Governance", description = "Search, govern, and import canonical database naming vocabulary")
public final class GlossaryController {
    private final GlossaryRepository repository;
    private final GlossaryExcelImporter importer;
    private final GlossaryWriteGuard writeGuard;

    public GlossaryController(GlossaryRepository repository, GlossaryExcelImporter importer,
                              GlossaryWriteGuard writeGuard) {
        this.repository = repository;
        this.importer = importer;
        this.writeGuard = writeGuard;
    }

    @GetMapping("/terms")
    @Operation(summary = "Search and paginate glossary entries (including candidates)")
    public GlossaryPage search(@RequestParam(required = false) String q,
                               @RequestParam(required = false) GlossaryTermKind kind,
                               @RequestParam(required = false) GlossaryTermStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "100") int size) {
        return repository.search(q, kind, status, page, size);
    }

    @GetMapping("/terms/{id}")
    @Operation(summary = "Get one glossary term")
    public ResponseEntity<GlossaryTerm> get(@PathVariable long id) {
        return repository.get(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export all glossary entries, without page limits")
    public ResponseEntity<byte[]> export() {
        StringBuilder output = new StringBuilder("\uFEFF");
        output.append(GlossaryCsv.row("id", "kind", "key", "english",
                "persian", "module", "status", "source"));
        int page = 0;
        GlossaryPage result;
        do {
            result = repository.search(null, null, null, page++, 1000);
            for (GlossaryTerm term : result.items()) {
                output.append(GlossaryCsv.row(Long.toString(term.id()), term.kind().name(), term.key(),
                        term.english(), term.persian(), term.module(), term.status().name(), term.source()));
            }
        } while (!result.items().isEmpty());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("schemaforge-glossary.csv").build());
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        return new ResponseEntity<>(output.toString().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    @PostMapping("/terms")
    @Operation(summary = "Create a glossary term; default status is CANDIDATE")
    public ResponseEntity<GlossaryTerm> create(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestBody GlossaryTermRequest request) {
        writeGuard.requireAuthorized(adminKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.create(request));
    }

    @PutMapping("/terms/{id}")
    @Operation(summary = "Edit or approve/reject a glossary term")
    public ResponseEntity<GlossaryTerm> update(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @PathVariable long id, @RequestBody GlossaryTermRequest request) {
        writeGuard.requireAuthorized(adminKey);
        return repository.update(id, request).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/terms/{id}")
    @Operation(summary = "Soft delete a glossary term, excluding it from validation and export")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @PathVariable long id) {
        writeGuard.requireAuthorized(adminKey);
        return repository.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/bulk-status")
    @Operation(summary = "Explicit administrator approval/rejection for a reviewed cohort")
    public BulkStatusResult bulkStatus(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) GlossaryTermKind kind,
            @RequestParam GlossaryTermStatus from,
            @RequestParam GlossaryTermStatus to) {
        writeGuard.requireAuthorized(adminKey);
        return new BulkStatusResult(repository.changeStatus(kind, from, to), kind, from, to);
    }

    public record BulkStatusResult(int updated, GlossaryTermKind kind,
                                   GlossaryTermStatus from, GlossaryTermStatus to) { }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import only 01_Atomic_Terms; other Excel worksheets are ignored")
    public GlossaryExcelImporter.ImportResult importWorkbook(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "CANDIDATE") GlossaryTermStatus status)
            throws IOException, SQLException {
        writeGuard.requireAuthorized(adminKey);
        return importer.importWorkbook(file, status);
    }
}
