package com.behsazan.schemaforge.glossary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic gate for the Windows-only discrepancy: an 804-entry import is visible
 * on one search invocation but missing on a subsequent invocation.
 *
 * It deliberately does not change production behavior or the normal bootstrap test.
 * It uses its own isolated temporary database; it never opens the project's data DB.
 */
class GlossaryAtomicSnapshotDiagnosticTest {
    @TempDir Path temp;

    private static final Set<String> EXPECTED_PHRASES = Set.of(
            "GENERAL_LEDGER", "SUBSIDIARY_LEDGER", "ADVANCE_PAYMENT", "BALANCE_SHEET");

    @Test
    void compareRawH2CountsAndRepeatedSearchesWithAndWithoutOpenConnection() throws Exception {
        Path base = temp.resolve("data/glossary-diagnostic").toAbsolutePath();
        Files.createDirectories(base.getParent());
        var ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        String url = "jdbc:h2:file:" + base.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        ds.setUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        // Version is pinned because H2 2.3.232 may lose committed rows during
        // on-close MVStore compaction (upstream h2database/h2database#4247).
        try (Connection check = ds.getConnection()) {
            String engineVersion = check.getMetaData().getDatabaseProductVersion();
            System.out.println("GLOSSARY_DIAG engineVersion=" + engineVersion);
            assertTrue(engineVersion.startsWith("2.4.240"),
                    "Unexpected H2 runtime " + engineVersion + "; Maven must resolve 2.4.240");
        }
        var repository = new GlossaryRepository(ds);
        var importer = new GlossaryExcelImporter(repository);

        System.out.println("GLOSSARY_DIAG classSHA256=" + fingerprint(GlossaryRepository.class));
        System.out.println("GLOSSARY_DIAG url=" + url);
        try (InputStream data = new ClassPathResource("glossary/01_Atomic_Terms.xlsx").getInputStream()) {
            var imported = importer.importWorkbook(data, GlossaryTermStatus.CANDIDATE);
            System.out.println("GLOSSARY_DIAG imported sourceRows=" + imported.sourceRows()
                    + " uniqueEntries=" + imported.uniqueEntries() + " inserted=" + imported.inserted());
            assertEquals(946, imported.sourceRows());
            assertEquals(804, imported.uniqueEntries());
        }

        List<Observation> observations = new ArrayList<>();
        observations.add(observe("no-held-connection-1", ds, repository));
        observations.add(observe("no-held-connection-2", ds, repository));
        try (Connection keepAlive = ds.getConnection()) {
            System.out.println("GLOSSARY_DIAG pinnedUrl=" + keepAlive.getMetaData().getURL());
            observations.add(observe("with-held-connection-1", ds, repository));
            observations.add(observe("with-held-connection-2", ds, repository));
        }
        observations.add(observe("no-held-connection-3", ds, repository));

        // Report every snapshot before asserting to avoid a failure on the first call
        // hiding the changes between successive data-source connections.
        for (Observation o : observations) System.out.println("GLOSSARY_DIAG SNAPSHOT " + o);
        List<String> anomalies = new ArrayList<>();
        for (Observation o : observations) {
            if (o.rawTotal() != 804) anomalies.add(o.phase() + ": rawTotal=" + o.rawTotal());
            if (o.rawPhrases() != 4) anomalies.add(o.phase() + ": rawPhrases=" + o.rawPhrases());
            if (o.allTotal() != 4) anomalies.add(o.phase() + ": allTotal=" + o.allTotal());
            if (!EXPECTED_PHRASES.equals(o.allKeys())) anomalies.add(o.phase() + ": allKeys=" + o.allKeys());
            if (o.firstTotal() != 4) anomalies.add(o.phase() + ": firstTotal=" + o.firstTotal());
            if (o.secondTotal() != 4) anomalies.add(o.phase() + ": secondTotal=" + o.secondTotal());
            if (o.firstItems() != 2) anomalies.add(o.phase() + ": firstItems=" + o.firstItems());
            if (o.secondItems() != 2) anomalies.add(o.phase() + ": secondItems=" + o.secondItems());
            if (o.ledgerTotal() != 2) anomalies.add(o.phase() + ": ledgerTotal=" + o.ledgerTotal());
        }
        assertTrue(anomalies.isEmpty(), "Inconsistent H2 glossary snapshots: " + anomalies);

    }

    private static Observation observe(String phase, DriverManagerDataSource ds,
                                       GlossaryRepository repository) throws Exception {
        long rawTotal;
        long rawPhrases;
        try (Connection c = ds.getConnection();
             var query = c.createStatement();
             ResultSet rows = query.executeQuery("SELECT "
                     + "COUNT(*) AS all_count, "
                     + "COUNT(CASE WHEN kind='PHRASE' THEN 1 END) AS phrase_count "
                     + "FROM glossary_term WHERE is_deleted=FALSE "
                     + "AND (kind='ATOMIC' OR source<>'ECOBANK_XLSX')")) {
            assertTrue(rows.next());
            rawTotal = rows.getLong("all_count");
            rawPhrases = rows.getLong("phrase_count");
        }
        var all = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 1000);
        var first = repository.search(null, GlossaryTermKind.PHRASE, null, 0, 2);
        var second = repository.search(null, GlossaryTermKind.PHRASE, null, 1, 2);
        var ledger = repository.search("LEDGER", GlossaryTermKind.PHRASE, null, 0, 10);
        return new Observation(phase, rawTotal, rawPhrases, all.total(),
                all.items().stream().map(GlossaryTerm::key).collect(Collectors.toSet()),
                first.total(), first.items().size(), second.total(), second.items().size(),
                ledger.total());
    }

    private static String fingerprint(Class<?> cls) throws Exception {
        try (InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class")) {
            if (in == null) throw new IllegalStateException("Class resource not found: " + cls.getName());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return java.util.HexFormat.of().formatHex(hash);
        }
    }

    private record Observation(String phase, long rawTotal, long rawPhrases,
                               long allTotal, Set<String> allKeys,
                               long firstTotal, int firstItems, long secondTotal, int secondItems,
                               long ledgerTotal) { }
}
