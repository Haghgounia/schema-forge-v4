package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.error.SchemaForgeRestExceptionHandler;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlossaryXlsxExportAndSpellingTest {
    @TempDir Path temp;

    private GlossaryRepository repository() {
        return new GlossaryRepository(repoDataSource());
    }

    private DriverManagerDataSource repoDataSource() {
        var db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:file:" + temp.resolve("new-glossary").toAbsolutePath()
                .toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        db.setUsername("sa");
        db.setPassword("");
        return db;
    }

    @Test
    void xlsxExportsEveryVisibleStatusAndPersianWithoutWritingToDatabase() throws Exception {
        var repo = repository();
        var customer = repo.create(new GlossaryTermRequest("Customer", "مشتری", "CRM",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        repo.create(new GlossaryTermRequest("Profile", "پروفایل", "CRM",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.CANDIDATE));
        repo.create(new GlossaryTermRequest("Deprecated", "", "CRM",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.REJECTED));
        repo.create(new GlossaryTermRequest("Daily Balance", "مانده روزانه", "Accounting",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.APPROVED));
        var deleted = repo.create(new GlossaryTermRequest("Should Not Export", "موقت", "",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        assertTrue(repo.delete(deleted.id()));
        var mvc = MockMvcBuilders.standaloneSetup(new GlossaryController(
                repo, new GlossaryExcelImporter(repo), new GlossaryWriteGuard("test-secret")))
                .setControllerAdvice(new SchemaForgeRestExceptionHandler()).build();
        byte[] bytes = mvc.perform(get("/api/v1/glossary/export.xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("schemaforge-glossary.xlsx")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 1000);
        try (var excel = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(2, excel.getNumberOfSheets());
            var entries = excel.getSheet("Glossary");
            assertEquals(5, entries.getPhysicalNumberOfRows()); // header + 4 active entries
            assertEquals("ID", entries.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Status", entries.getRow(0).getCell(6).getStringCellValue());
            boolean hasPersian = false;
            boolean hasApproved = false;
            boolean hasCandidate = false;
            boolean hasRejected = false;
            for (int row = 1; row <= 4; row++) {
                var current = entries.getRow(row);
                if ("مشتری".equals(current.getCell(4).getStringCellValue())) hasPersian = true;
                switch (current.getCell(6).getStringCellValue()) {
                    case "APPROVED" -> hasApproved = true;
                    case "CANDIDATE" -> hasCandidate = true;
                    case "REJECTED" -> hasRejected = true;
                    default -> fail("Unknown exported status");
                }
                assertNotEquals("SHOULD_NOT_EXPORT", current.getCell(2).getStringCellValue());
            }
            assertTrue(hasPersian && hasApproved && hasCandidate && hasRejected);
            assertEquals("4", excel.getSheet("Overview").getRow(6).getCell(1).getStringCellValue());
        }
        assertEquals(4, repo.search(null, null, null, 0, 100).total());
        assertEquals(GlossaryTermStatus.APPROVED, repo.get(customer.id()).orElseThrow().status());
    }

    @Test
    void exportDoesNotStopAtTheFirstThousandEntries() throws Exception {
        var repo = repository();
        // This is an XLSX pagination test, not a CRUD throughput test. Preparing 1003
        // rows via repository.create() would open several separate H2 file connections
        // per row and made Windows regression spend over 20 minutes in this fixture.
        // Import the same CANDIDATE rows in one repository-managed transaction.
        var terms = new java.util.ArrayList<GlossaryImportTerm>(1003);
        for (int i = 0; i < 1003; i++) {
            String key = "TERM" + String.format("%04d", i);
            terms.add(new GlossaryImportTerm(GlossaryTermKind.ATOMIC, key, key, "", ""));
        }
        assertEquals(1003, repo.importNew(terms, GlossaryTermStatus.CANDIDATE));
        byte[] bytes = new GlossaryXlsxExporter(repo).export();
        try (var excel = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = excel.getSheet("Glossary");
            assertEquals(1004, sheet.getPhysicalNumberOfRows());
            assertEquals("TERM0000", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("TERM1000", sheet.getRow(1001).getCell(2).getStringCellValue());
            assertEquals("TERM1002", sheet.getRow(1003).getCell(2).getStringCellValue());
            assertEquals("1003", excel.getSheet("Overview").getRow(6).getCell(1).getStringCellValue());
        }
    }

    @Test
    void legacyNewCsvPluralsAreNotInsertedWhenSingularExists() {
        var repo = repository();
        repo.create(new GlossaryTermRequest("PROFILE", "پروفایل", "",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.CANDIDATE));
        var review = new GlossaryReviewService(repoDataSource());
        String csv = GlossaryCsv.row("source_name", "object_type", "table_name", "column_name",
                "identifier", "unknown_term", "token_position", "review_status")
                + GlossaryCsv.row("a.docx", "TABLE", "PROFILES", "", "PROFILES",
                        "PROFILES", "1", "NEW")
                + GlossaryCsv.row("a.docx", "COLUMN", "PROFILES", "PROFILES_ID",
                        "PROFILES_ID", "PROFILES", "1", "NEW");
        var result = review.importReport("unknown-terms.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, result.skippedKnownPluralOccurrences());
        assertEquals(0, result.createdCandidates());
        assertEquals(0, result.recordedOccurrences());
        assertEquals(0, result.previouslyRecorded());
        assertTrue(repo.getByKey(GlossaryTermKind.ATOMIC, "PROFILES").isEmpty());
    }

    @Test
    void spellingSuggestionsAreReadOnlyAndDoNotApproveOrChangeWords() throws Exception {
        var repo = repository();
        repo.create(new GlossaryTermRequest("CUSTOMER", "مشتری", "", GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.APPROVED));
        repo.create(new GlossaryTermRequest("PROFILE", "پروفایل", "", GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.CANDIDATE));
        repo.create(new GlossaryTermRequest("BLOCKED", "", "", GlossaryTermKind.ATOMIC,
                GlossaryTermStatus.REJECTED));
        var mvc = MockMvcBuilders.standaloneSetup(new GlossaryController(
                repo, new GlossaryExcelImporter(repo), new GlossaryWriteGuard("test-secret")))
                .setControllerAdvice(new SchemaForgeRestExceptionHandler()).build();
        mvc.perform(get("/api/v1/glossary/spelling-suggestions")
                        .param("term", "CUTOMER").param("objectType", "COLUMN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("POSSIBLE_TYPO"))
                .andExpect(jsonPath("$.suggestions[0].key").value("CUSTOMER"))
                .andExpect(jsonPath("$.automaticChanges").value(false));
        mvc.perform(get("/api/v1/glossary/spelling-suggestions")
                        .param("term", "PROFILES").param("objectType", "TABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.canonicalSingular").value("PROFILE"))
                .andExpect(jsonPath("$.canonicalStatus").value("CANDIDATE"));
        mvc.perform(get("/api/v1/glossary/spelling-suggestions")
                        .param("term", "PROFILES").param("objectType", "COLUMN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("PLURAL_COLUMN_VIOLATION"));
        mvc.perform(get("/api/v1/glossary/spelling-suggestions")
                        .param("term", "BLOCKED"))
                .andExpect(jsonPath("$.classification").value("REJECTED"));
        mvc.perform(get("/api/v1/glossary/spelling-suggestions")
                        .param("term", "A_B"))
                .andExpect(status().isBadRequest());
        assertEquals(3, repo.search(null, null, null, 0, 100).total());
        assertTrue(repo.getByKey(GlossaryTermKind.ATOMIC, "CUTOMER").isEmpty());
        assertEquals(GlossaryTermStatus.CANDIDATE,
                repo.getByKey(GlossaryTermKind.ATOMIC, "PROFILE").orElseThrow().status());
    }
}
