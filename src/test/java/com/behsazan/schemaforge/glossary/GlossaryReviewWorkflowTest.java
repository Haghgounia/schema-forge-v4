package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.error.SchemaForgeRestExceptionHandler;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual H2 + generated CSV + REST contract gate; never accesses the user's project data/*.mv.db. */
class GlossaryReviewWorkflowTest {
    @TempDir Path temp;

    private DataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + temp.resolve("review-db").toAbsolutePath()
                .toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    @Test
    void realArtifactReportIsIdempotentlyReviewedWithImmutableHistoryAndManifestIntact() throws Exception {
        var db = dataSource();
        var repo = new GlossaryRepository(db);
        var reviews = new GlossaryReviewService(db);
        repo.create(new GlossaryTermRequest("Account", "حساب", "", GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.APPROVED));
        var schema = DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "ACCOUNT_XOPNEW")
                        .addColumn(Column.required("ACCOUNT_XOPDENIED", DataType.simple("NUMBER")))
                        .build()).build();
        var models = List.of(new ArtifactManifestAssembler.ModelInput("sample.docx", schema,
                new ValidationReport(true, List.of())));
        var context = ArtifactGenerationContext.create(ArtifactOrigin.STANDARD_WORD, "sample.docx");
        var artifactRoot = Files.createDirectories(temp.resolve("artifacts"));
        var mapper = new ObjectMapper();
        new GlossaryReportWriter(repo, mapper).write(artifactRoot, context, models);
        new ArtifactManifestWriter(mapper).write(artifactRoot, context, "sample", models, Map.of());
        assertEquals(2, context.ledger().snapshot().stream()
                .filter(entry -> entry.type() == ArtifactType.GLOSSARY_REPORT).count());
        byte[] csv = Files.readAllBytes(artifactRoot.resolve("reports/glossary/unknown-terms.csv"));
        String rawCsv = new String(csv, StandardCharsets.UTF_8);
        assertTrue(rawCsv.contains("XOPNEW"));
        assertTrue(rawCsv.contains("XOPDENIED"));

        var first = reviews.importReport("unknown-terms.csv", csv);
        assertEquals(2, first.newOccurrences());
        assertEquals(2, first.createdCandidates());
        assertEquals(2, first.recordedOccurrences());
        assertEquals(0, first.skippedNonNew());
        var again = reviews.importReport("unknown-terms.csv", csv);
        assertEquals(2, again.previouslyRecorded());
        assertEquals(0, again.createdCandidates());
        assertEquals(0, again.recordedOccurrences());
        assertEquals(2, reviews.queue(false, 0, 100).total());
        assertEquals("CANDIDATE", reviews.queue(false, 0, 100).items().getFirst().status());
        assertFalse(repo.approvedLexicon().atomic().contains("XOPNEW"));

        var approval = reviews.decide(new GlossaryReviewService.DecisionRequest(
                "XOPNEW", GlossaryTermStatus.APPROVED, "نو", "Retail", "Reviewed terminology evidence"));
        assertEquals(GlossaryTermStatus.APPROVED, approval.status());
        reviews.decide(new GlossaryReviewService.DecisionRequest(
                "XOPDENIED", GlossaryTermStatus.REJECTED, "", "", "Invalid naming term"));
        assertTrue(repo.approvedLexicon().atomic().contains("XOPNEW"));
        assertTrue(repo.approvedLexicon().rejectedAtomic().contains("XOPDENIED"));
        assertEquals("نو", repo.getByKey(GlossaryTermKind.ATOMIC, "XOPNEW").orElseThrow().persian());
        assertEquals(0, reviews.queue(false, 0, 100).total());
        assertEquals(2, reviews.queue(true, 0, 100).total());
        assertEquals(4, reviews.history(null, 0, 100).total());
        assertEquals(2, reviews.history("XOPNEW", 0, 100).total());
        var findings = new GlossaryScanner().scan("sample.docx", schema, repo.approvedLexicon());
        assertEquals(List.of("XOPDENIED"), findings.stream().map(UnknownGlossaryTerm::term).toList());
        assertEquals("REJECTED", findings.getFirst().reviewStatus());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> reviews.decide(new GlossaryReviewService.DecisionRequest(
                        "XOPNEW", GlossaryTermStatus.REJECTED, "", "", "Conflicting second review")));
        assertEquals(4, reviews.history(null, 0, 100).total(),
                "Rejected second decisions must not leave misleading history events");

        // Review artifacts and decisions persist when every connection has been closed.
        var reopened = new GlossaryReviewService(dataSource());
        assertEquals(4, reopened.history(null, 0, 100).total());
        assertEquals(2, reopened.queue(true, 0, 100).total());
    }

    @Test
    void reportParserRejectsAlteredHeaderForgedPositionsAndMalformedUtf8WithoutAnyWrites() {
        var db = dataSource();
        new GlossaryRepository(db);
        var reviews = new GlossaryReviewService(db);
        String goodHeader = GlossaryCsv.row("source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status");
        String valid = GlossaryCsv.row("file.docx", "TABLE", "XNEW", "", "XNEW", "XNEW", "1", "NEW");
        String invalid = GlossaryCsv.row("file.docx", "TABLE", "XNEW", "", "XNEW", "XFAKE", "1", "NEW");
        assertThrows(IllegalArgumentException.class, () -> reviews.importReport("bad.csv",
                (goodHeader + valid + invalid).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> reviews.importReport("bad.csv",
                (GlossaryCsv.row("bad_header") + valid).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> reviews.importReport("bad.csv",
                new byte[] {(byte) 0xFF, (byte) 0xFE}));
        assertThrows(IllegalArgumentException.class, () -> reviews.importReport("bad.csv",
                (goodHeader + "\"file.docx\",\"TABLE\",\"XNEW\"\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> reviews.importReport("bad.csv",
                (goodHeader + GlossaryCsv.row("file.docx", "TABLE", "X_NEW", "", "X_NEW",
                        "X_NEW", "1", "NEW")).getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, reviews.queue(true, 0, 100).total());
        assertEquals(0, reviews.history(null, 0, 100).total());
    }

    @Test
    void ignoresResolvedReportRowsAndDoesNotOverwriteExistingApprovedRejectedOrDeletedTerms() {
        var db = dataSource();
        var repo = new GlossaryRepository(db);
        var reviews = new GlossaryReviewService(db);
        repo.create(new GlossaryTermRequest("XEXIST", "Reviewed", "", GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.APPROVED));
        var deleted = repo.create(new GlossaryTermRequest("XDELETED", "Historical", "",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.REJECTED));
        repo.delete(deleted.id());
        String csv = GlossaryCsv.row("source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status")
                + GlossaryCsv.row("one.docx", "TABLE", "XEXIST", "", "XEXIST", "XEXIST", "1", "NEW")
                + GlossaryCsv.row("one.docx", "TABLE", "XDELETED", "", "XDELETED", "XDELETED", "1", "NEW")
                + GlossaryCsv.row("one.docx", "TABLE", "XPENDING", "", "XPENDING", "XPENDING", "1", "PENDING_APPROVAL")
                + GlossaryCsv.row("one.docx", "TABLE", "XREJECTED", "", "XREJECTED", "XREJECTED", "1", "REJECTED");
        var result = reviews.importReport("unknown-terms.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(4, result.sourceRows());
        assertEquals(2, result.skippedNonNew());
        assertEquals(0, result.createdCandidates());
        assertEquals(2, result.alreadyKnownOccurrences());
        assertEquals(GlossaryTermStatus.APPROVED,
                repo.getByKey(GlossaryTermKind.ATOMIC, "XEXIST").orElseThrow().status());
        assertEquals("Reviewed", repo.getByKey(GlossaryTermKind.ATOMIC, "XEXIST").orElseThrow().persian());
        assertTrue(repo.get(deleted.id()).isEmpty());
        assertEquals(0, reviews.queue(false, 0, 100).total());
    }

    @Test
    void restReviewEndpointsAreAdminGuardedAndExposeConsistentErrors() throws Exception {
        var db = dataSource();
        new GlossaryRepository(db);
        var reviews = new GlossaryReviewService(db);
        var mvc = MockMvcBuilders.standaloneSetup(new GlossaryReviewController(
                reviews, new GlossaryWriteGuard("admin-for-tests")))
                .setControllerAdvice(new SchemaForgeRestExceptionHandler()).build();
        String contents = GlossaryCsv.row("source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status")
                + GlossaryCsv.row("one.docx", "TABLE", "XAPITEST", "", "XAPITEST", "XAPITEST", "1", "NEW");
        var uploaded = new MockMultipartFile("file", "unknown-terms.csv", "text/csv",
                contents.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/glossary/review/import-report").file(uploaded))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(multipart("/api/v1/glossary/review/import-report").file(uploaded)
                        .header("X-Glossary-Admin-Key", "admin-for-tests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCandidates").value(1));
        mvc.perform(get("/api/v1/glossary/review/queue"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/glossary/review/queue")
                        .header("X-Glossary-Admin-Key", "admin-for-tests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("CANDIDATE"));
        String decision = "{\"term\":\"XAPITEST\",\"to\":\"APPROVED\","
                + "\"persian\":\"تست\",\"reason\":\"Reviewed in governance queue\"}";
        mvc.perform(post("/api/v1/glossary/review/decision")
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/glossary/review/decision")
                        .header("X-Glossary-Admin-Key", "admin-for-tests")
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/v1/glossary/review/decision")
                        .header("X-Glossary-Admin-Key", "admin-for-tests")
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/api/v1/glossary/review/history").param("term", "XAPITEST")
                        .header("X-Glossary-Admin-Key", "admin-for-tests"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }
}
