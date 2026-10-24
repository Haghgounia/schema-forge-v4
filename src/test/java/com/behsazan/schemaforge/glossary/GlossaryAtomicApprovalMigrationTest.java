package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Existing CANDIDATE -> APPROVED, only once; new H2 starts approved. */
class GlossaryAtomicApprovalMigrationTest {
    @TempDir Path temp;

    private GlossaryRepository repository(Path name) {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + name.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");source.setPassword("");
        return new GlossaryRepository(source);
    }

    private GlossaryExcelImporter.ImportResult seedCandidates(GlossaryRepository repository) throws Exception {
        try (InputStream in = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            return new GlossaryExcelImporter(repository).importWorkbook(in, GlossaryTermStatus.CANDIDATE);
        }
    }

    private GlossaryExcelImporter.ApprovedBaselineResult migrate(GlossaryRepository repository) throws Exception {
        try (InputStream in = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            return new GlossaryExcelImporter(repository).importApprovedBaseline(in);
        }
    }

    @Test
    void existingAtomicCandidatesBecomeApprovedAndSubsequentCurationIsPreserved() throws Exception {
        Path file = temp.resolve("existing-h2");
        var db = repository(file);
        assertEquals(804, seedCandidates(db).inserted());
        var old = db.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow();
        assertEquals(GlossaryTermStatus.CANDIDATE, old.status());
        db.update(old.id(), new GlossaryTermRequest("ACCOUNT", "حساب", "Curated details",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.REJECTED));
        var removed = db.getByKey(GlossaryTermKind.ATOMIC, "ABILITY").orElseThrow();
        db.delete(removed.id());
        db.create(new GlossaryTermRequest("OUTSIDEBASELINEABC", "خارج از فایل", "",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.CANDIDATE));
        var result = migrate(db);
        assertEquals(0, result.inserted());
        assertTrue(result.migration().applied());
        assertEquals(804, result.migration().sourceEntries());
        assertEquals(803, result.migration().promoted());
        assertEquals(1, result.migration().skipped());
        assertEquals(799, db.approvedLexicon().atomic().size());
        assertEquals(4, db.approvedLexicon().phrases().size());
        assertEquals(GlossaryTermStatus.APPROVED, db.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT")
                .orElseThrow().status());
        assertEquals("Curated details", db.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT")
                .orElseThrow().module());
        assertTrue(db.getByKey(GlossaryTermKind.ATOMIC, "ABILITY").isEmpty());
        assertEquals(GlossaryTermStatus.CANDIDATE, db.getByKey(
                GlossaryTermKind.ATOMIC, "OUTSIDEBASELINEABC").orElseThrow().status());

        var account = db.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow();
        db.update(account.id(), new GlossaryTermRequest("ACCOUNT", "حساب", "Later steward decision",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.REJECTED));
        var reopened = repository(file);
        var again = migrate(reopened);
        assertFalse(again.migration().applied());
        assertEquals(0, again.migration().promoted());
        assertEquals(GlossaryTermStatus.REJECTED, reopened.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT")
                .orElseThrow().status());
    }

    @Test
    void freshH2StartsWithExactly804ApprovedTerms() throws Exception {
        var db = repository(temp.resolve("fresh-h2"));
        var result = migrate(db);
        assertEquals(946, result.sourceRows());
        assertEquals(804, result.uniqueEntries());
        assertEquals(804, result.inserted());
        assertTrue(result.migration().applied());
        assertEquals(0, result.migration().promoted());
        assertEquals(800, db.approvedLexicon().atomic().size());
        assertEquals(4, db.approvedLexicon().phrases().size());
    }
}
