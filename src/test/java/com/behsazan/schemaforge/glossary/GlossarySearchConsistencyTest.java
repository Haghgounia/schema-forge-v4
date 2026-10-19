package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Independent search regression: result list and count must describe the same rows. */
class GlossarySearchConsistencyTest {
    @TempDir Path temp;

    @Test
    void stablePagesLiteralQueriesStatusesAndLegacyExclusions() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("glossary-search").toAbsolutePath()
                .toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        var ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        var repository = new GlossaryRepository(ds);
        repository.create(new GlossaryTermRequest("ACCOUNT", "حساب", "Core",
                GlossaryTermKind.ATOMIC, GlossaryTermStatus.APPROVED));
        repository.create(new GlossaryTermRequest("GENERAL LEDGER", "دفتر کل", "Finance",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        repository.create(new GlossaryTermRequest("SUBSIDIARY LEDGER", "دفتر معین", "Finance",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        repository.create(new GlossaryTermRequest("ADVANCE PAYMENT", "پیش پرداخت", "Finance",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        repository.create(new GlossaryTermRequest("BALANCE SHEET", "ترازنامه", "Finance",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.CANDIDATE));
        repository.create(new GlossaryTermRequest("LEGACY ONLY", "قدیمی", "Legacy",
                GlossaryTermKind.PHRASE, GlossaryTermStatus.APPROVED));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE glossary_term SET source='ECOBANK_XLSX' "
                    + "WHERE term_key='LEGACY_ONLY'");
        }

        var all = repository.search(null, null, null, 0, 1000);
        assertEquals(5L, all.total());
        assertEquals(5, all.items().size());
        var phrase = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 1000);
        assertEquals(4L, phrase.total());
        assertEquals(Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER", "ADVANCE_PAYMENT", "BALANCE_SHEET"),
                phrase.items().stream().map(GlossaryTerm::key).collect(Collectors.toSet()));
        var first = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 2);
        var second = repository.search(null, GlossaryTermKind.PHRASE, null, 1, 2);
        assertEquals(4L, first.total());
        assertEquals(4L, second.total());
        assertEquals(2, first.items().size());
        assertEquals(2, second.items().size());
        assertEquals(Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER", "ADVANCE_PAYMENT", "BALANCE_SHEET"),
                java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                        .map(GlossaryTerm::key).collect(Collectors.toSet()));
        assertTrue(repository.search(null, GlossaryTermKind.PHRASE, null, 2, 2).items().isEmpty());
        var literal = repository.search("GENERAL_LEDGER", GlossaryTermKind.PHRASE, null, 0, 100);
        assertEquals(1L, literal.total());
        assertEquals("GENERAL_LEDGER", literal.items().getFirst().key());
        assertEquals(2L, repository.search("ledger", GlossaryTermKind.PHRASE, null, 0, 10).total());
        assertEquals(2L, repository.search("LEDGER", GlossaryTermKind.PHRASE, null, 0, 10).total());
        assertEquals(Set.of("GENERAL_LEDGER", "SUBSIDIARY_LEDGER"),
                repository.search("LEDGER", GlossaryTermKind.PHRASE, null, 0, 10).items().stream()
                        .map(GlossaryTerm::key).collect(Collectors.toSet()));
        assertEquals(1L, repository.search("دفتر کل", null, null, 0, 10).total());
        assertEquals(1L, repository.search(null, null, GlossaryTermStatus.APPROVED, 0, 10).total());
        assertThrows(IllegalArgumentException.class, () -> repository.search(null, null, null, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> repository.search(null, null, null, 0, 1001));
        assertThrows(IllegalArgumentException.class,
                () -> repository.search(null, null, null, Integer.MAX_VALUE, 2));
    }
}
