package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** File-backed smoke gate against the real packaged Atomic Terms workbook, isolated in @TempDir. */
class GlossaryOperationalAcceptanceTest {
    @TempDir Path temp;

    private DataSource dataSource() throws Exception {
        Files.createDirectories(temp.resolve("data"));
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + temp.resolve("data/operational-acceptance")
                .toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");
        source.setPassword("");
        return source;
    }

    @Test
    void packagedAtomicTermsPersistAndRetainStewardChangesAfterRestartAndReseeding() throws Exception {
        var db = dataSource();
        var repo = new GlossaryRepository(db);
        GlossaryExcelImporter.ImportResult first;
        try (InputStream stream = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            first = new GlossaryExcelImporter(repo).importWorkbook(stream, GlossaryTermStatus.CANDIDATE);
        }
        assertEquals(946, first.sourceRows());
        assertEquals(804, first.uniqueEntries());
        assertEquals(804, first.inserted());
        assertEquals(800, repo.search(null, GlossaryTermKind.ATOMIC, null, 0, 1000).total());
        assertEquals(4, repo.search(null, GlossaryTermKind.PHRASE, null, 0, 1000).total());
        assertEquals(0, repo.approvedLexicon().atomic().size());
        assertEquals(Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER", "ADVANCE_PAYMENT", "BALANCE_SHEET"),
                repo.search(null, GlossaryTermKind.PHRASE, null, 0, 10).items().stream()
                        .map(GlossaryTerm::key).collect(Collectors.toSet()));
        var stewardship = new GlossaryReviewService(db);
        assertEquals(804, stewardship.queue(false, 0, 100).total());
        stewardship.decide(new GlossaryReviewService.DecisionRequest(
                "ACCOUNT", GlossaryTermStatus.APPROVED, "پذیرفته شده پس از بررسی",
                "Acceptance", "Approved by the terminology steward"));
        assertEquals(803, stewardship.queue(false, 0, 100).total());
        assertEquals(1, stewardship.history("ACCOUNT", 0, 100).total());
        stewardship.decide(new GlossaryReviewService.DecisionRequest(
                "GENERAL_LEDGER", GlossaryTermStatus.APPROVED, "دفتر کل",
                "Accounting", "Reviewed Atomic-worksheet phrase", GlossaryTermKind.PHRASE));
        assertEquals(802, stewardship.queue(false, 0, 100).total());
        assertTrue(repo.approvedLexicon().phrases().contains("GENERAL_LEDGER"));
        assertEquals(1, stewardship.history("GENERAL_LEDGER", 0, 100).total());

        // A fresh DataSource & Repository cause the persistent file to be re-opened.
        var reopened = new GlossaryRepository(dataSource());
        assertEquals(804, reopened.search(null, null, null, 0, 1000).total());
        assertTrue(reopened.approvedLexicon().atomic().contains("ACCOUNT"));
        assertTrue(reopened.approvedLexicon().phrases().contains("GENERAL_LEDGER"));
        try (InputStream stream = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            assertEquals(0, new GlossaryExcelImporter(reopened)
                    .importWorkbook(stream, GlossaryTermStatus.CANDIDATE).inserted());
        }
        var preserved = reopened.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow();
        assertEquals(GlossaryTermStatus.APPROVED, preserved.status());
        assertEquals("پذیرفته شده پس از بررسی", preserved.persian());
        assertEquals("Acceptance", preserved.module());
        assertTrue(Files.isRegularFile(temp.resolve("data/operational-acceptance.mv.db")));
    }
}
