package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the same packaged Excel and repository path used by startup seeding. */
class GlossaryAtomicBootstrapTest {
    @TempDir Path temporary;

    @Test
    void createsPersistentH2AndImportsOnlyAtomicBaselineIdempotently() throws Exception {
        Path database = temporary.resolve("data/schemaforge-glossary").toAbsolutePath();
        java.nio.file.Files.createDirectories(database.getParent());
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUsername("sa");
        source.setPassword("");
        var repository = new GlossaryRepository(source);
        var importer = new GlossaryExcelImporter(repository);
        try (InputStream input = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            var result = importer.importWorkbook(input, GlossaryTermStatus.CANDIDATE);
            assertEquals(946, result.sourceRows());
            assertEquals(804, result.uniqueEntries());
            assertEquals(804, result.inserted());
            assertEquals(GlossaryTermStatus.CANDIDATE, result.status());
        }
        assertEquals(804, repository.search(null, null, null, 0, 1000).total());
        assertEquals(800, repository.search(null, GlossaryTermKind.ATOMIC, null, 0, 1000).total());
        assertEquals(4, repository.search(null, GlossaryTermKind.PHRASE, null, 0, 1000).total());
        assertEquals(804, repository.search(null, null, null, 0, 1000).items().size(),
                "Search total and items must agree for the complete 804-term Atomic baseline");
        var phrases = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 1000).items();
        assertEquals(java.util.Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER",
                        "ADVANCE_PAYMENT", "BALANCE_SHEET"),
                phrases.stream().map(GlossaryTerm::key).collect(java.util.stream.Collectors.toSet()),
                "The four multiword entries must come from 01_Atomic_Terms");
        assertTrue(phrases.stream().allMatch(
                term -> "ECOBANK_ATOMIC_XLSX".equals(term.source())));
        // Pagination must return rows, not just a correct COUNT. This also exercises
        // filtered results and both ends of a short PHRASE result set.
        var firstPage = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 2);
        var secondPage = repository.search(null, GlossaryTermKind.PHRASE, null, 1, 2);
        assertEquals(4, firstPage.total());
        assertEquals(2, firstPage.items().size());
        assertEquals(2, secondPage.items().size());
        assertEquals(java.util.Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER",
                        "ADVANCE_PAYMENT", "BALANCE_SHEET"),
                java.util.stream.Stream.concat(firstPage.items().stream(), secondPage.items().stream())
                        .map(GlossaryTerm::key).collect(java.util.stream.Collectors.toSet()));
        assertTrue(repository.search(null, GlossaryTermKind.PHRASE, null, 2, 2)
                .items().isEmpty());
        var expectedLedgerKeys = phrases.stream()
                .map(GlossaryTerm::key).filter(key -> key.contains("LEDGER"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER"), expectedLedgerKeys,
                "The unfiltered mapped terms themselves must match LEDGER");
        var filtered = repository.search("LEDGER", GlossaryTermKind.PHRASE, null, 0, 10);
        assertEquals(expectedLedgerKeys.size(), filtered.total(),
                "Filtered query inconsistent with the same domain keys returned by unfiltered search. "
                        + "expected=" + expectedLedgerKeys + ", actual=" + filtered.items()
                        + ", allPhrases=" + phrases);
        assertEquals(filtered.total(), repository.search("ledger", GlossaryTermKind.PHRASE, null, 0, 10).total(),
                "Query case must not change search matches");
        assertEquals(java.util.Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER"),
                filtered.items().stream().map(GlossaryTerm::key)
                        .collect(java.util.stream.Collectors.toSet()));
        // Underscores in search terms must be treated literally, not as SQL LIKE wildcards.
        var exact = repository.search("GENERAL_LEDGER", GlossaryTermKind.PHRASE, null, 0, 10);
        assertEquals(1, exact.total());
        assertEquals("GENERAL_LEDGER", exact.items().getFirst().key());
        assertEquals(800, repository.search(null, GlossaryTermKind.ATOMIC, null, 0, 1000).items().size());
        assertEquals("ECOBANK_ATOMIC_XLSX", repository.getByKey(
                GlossaryTermKind.PHRASE, "GENERAL_LEDGER").orElseThrow().source());
        assertEquals(0, repository.approvedLexicon().atomic().size());

        var account = repository.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow();
        repository.update(account.id(), new GlossaryTermRequest("ACCOUNT", "حساب", "Banking",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        try (InputStream input = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            var result = importer.importWorkbook(input, GlossaryTermStatus.CANDIDATE);
            assertEquals(0, result.inserted());
            assertEquals(804, result.alreadyPresent());
        }
        assertEquals(GlossaryTermStatus.APPROVED, repository.getByKey(
                GlossaryTermKind.ATOMIC, "ACCOUNT").orElseThrow().status());
        assertEquals("Banking", repository.getByKey(GlossaryTermKind.ATOMIC, "ACCOUNT")
                .orElseThrow().module());
        assertTrue(java.nio.file.Files.exists(Path.of(database + ".mv.db")));
    }
}
