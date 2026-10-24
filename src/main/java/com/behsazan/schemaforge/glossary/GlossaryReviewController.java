package com.behsazan.schemaforge.glossary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Steward-only workflow; never approves terms as a side effect of report ingestion. */
@RestController
@RequestMapping("/api/v1/glossary/review")
@ConditionalOnProperty(prefix = "schemaforge.glossary", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Glossary Governance", description = "Review unknown words from generated artifact reports")
public final class GlossaryReviewController {
    private final GlossaryReviewService reviews;
    private final GlossaryWriteGuard guard;

    public GlossaryReviewController(GlossaryReviewService reviews, GlossaryWriteGuard guard) {
        this.reviews = reviews;
        this.guard = guard;
    }

    @PostMapping(value = "/import-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import generated unknown-terms.csv evidence as unapproved candidates")
    public GlossaryReviewService.ImportReportResult importReport(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestPart("file") MultipartFile file) throws IOException {
        guard.requireAuthorized(adminKey);
        if (file == null || file.isEmpty() || file.getSize() > GlossaryReportCsvParser.MAX_BYTES) {
            throw new IllegalArgumentException("A glossary CSV of up to 5 MB is required");
        }
        return reviews.importReport(file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/queue")
    @Operation(summary = "List pending review terms, or include decisions already resolved")
    public GlossaryReviewService.QueuePage queue(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        guard.requireAuthorized(adminKey);
        return reviews.queue(includeResolved, page, size);
    }

    @PostMapping("/decision")
    @Operation(summary = "Approve/reject one baseline, manual or discovered candidate with an auditable review reason")
    public GlossaryReviewService.DecisionResult decide(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestBody GlossaryReviewService.DecisionRequest request) {
        guard.requireAuthorized(adminKey);
        return reviews.decide(request);
    }

    @GetMapping("/history")
    @Operation(summary = "Read immutable workflow history, optionally restricted to one term")
    public GlossaryReviewService.HistoryPage history(
            @RequestHeader(value = "X-Glossary-Admin-Key", required = false) String adminKey,
            @RequestParam(required = false) String term,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        guard.requireAuthorized(adminKey);
        return reviews.history(term, page, size);
    }
}
