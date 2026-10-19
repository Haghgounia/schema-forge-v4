package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only, external-corpus acceptance test for STANDARD SchemaForge DOCX specifications.
 *
 * <p>Example (Windows CMD):
 * <pre>
 * mvnw.cmd -Dschemaforge.glossary.wordZip.file="D:\\Docs\\standard-tables.zip" ^
 *   -Dschemaforge.glossary.wordZip.outputDir="D:\\Reports\\glossary" ^
 *   -Dschemaforge.glossary.wordZip.h2Base="D:\\Projects\\schema-forge-v4\\data\\schemaforge-glossary" ^
 *   -Dschemaforge.glossary.wordZip.required=true ^
 *   -Dtest=GlossaryStandardWordZipAuditTest test
 * </pre>
 *
 * <p>Only .docx is parsed, strictly through WordSpecificationParser. The project H2 database is
 * queried read-only, with no import, seeding or approvals. Semantic meaningfulness and whether a
 * token is an abbreviation are NOT guessed: only explicit APPROVED glossary terms pass.
 */
class GlossaryStandardWordZipAuditTest {
    private static final String PREFIX = "schemaforge.glossary.wordZip.";
    private static final long MAX_ZIP_BYTES = 1_024L * 1_024 * 1_024;
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_DOC_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_DOC_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_SOURCE_NAME = 1024; // GlossaryReviewService contract
    private static final int MAX_IMPORT_ROWS = 20_000;
    private static final String UNKNOWN_HEADER = GlossaryCsv.row(
            "source_name", "object_type", "table_name", "column_name", "identifier",
            "unknown_term", "token_position", "review_status");
    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    @Test
    void auditsExternalZipOfStandardWordDocumentsAgainstExistingGlossary() throws Exception {
        String configuredZip = System.getProperty(PREFIX + "file", "").strip();
        if (configuredZip.isEmpty() && Boolean.getBoolean(PREFIX + "required")) {
            fail("Missing -D" + PREFIX + "file=... (ZIP containing standard .docx files)");
        }
        Assumptions.assumeTrue(!configuredZip.isEmpty(),
                "External standard Word ZIP not supplied; see distribution/bin/audit-standard-word-zip.cmd");

        Path zip = Path.of(configuredZip).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(zip), "Input Word ZIP does not exist: " + zip);
        assertTrue(Files.size(zip) <= MAX_ZIP_BYTES, "Input ZIP exceeds 1 GiB: " + zip);

        Path h2Base = Path.of(System.getProperty(PREFIX + "h2Base", "./data/schemaforge-glossary"))
                .toAbsolutePath().normalize();
        if (h2Base.toString().endsWith(".mv.db")) {
            h2Base = Path.of(h2Base.toString().substring(0, h2Base.toString().length() - 6));
        }
        assertTrue(Files.isRegularFile(Path.of(h2Base + ".mv.db")),
                "Existing Glossary H2 file not found: " + h2Base + ".mv.db. "
                        + "Enable the Glossary and initialize its database first; this audit never seeds it.");
        Snapshot snapshot = loadReadOnlyGlossary(h2Base);

        Path outputBase = Path.of(System.getProperty(PREFIX + "outputDir", "./target/glossary-standard-word-zip"))
                .toAbsolutePath().normalize();
        Path output = outputBase.resolve("audit-" + RUN_STAMP.format(Instant.now()) + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(output);
        System.out.println("[GLOSSARY_WORD_ZIP] Input ZIP: " + zip);
        System.out.println("[GLOSSARY_WORD_ZIP] H2 (read-only): " + h2Base + ".mv.db");
        System.out.println("[GLOSSARY_WORD_ZIP] Reports: " + output);

        var results = new AuditResults();
        List<String> problems = new ArrayList<>();
        try {
            auditZip(zip, snapshot.lexicon(), results, problems);
        } catch (IOException | IllegalArgumentException exception) {
            problems.add("ZIP_READ_FAILED: " + concise(exception));
        }
        writeReports(output, zip, h2Base, snapshot, results, problems);

        System.out.printf(Locale.ROOT,
                "[GLOSSARY_WORD_ZIP] scanned=%d, identifiers=%d, new=%d, pending=%d, rejected=%d, errors=%d%n",
                results.scannedFiles(), results.identifiers.size(), results.count("NEW"),
                results.count("PENDING_APPROVAL"), results.count("REJECTED"), problems.size());
        assertTrue(results.scannedFiles() > 0, "No standard .docx was parsed; reports: " + output);
        assertTrue(problems.isEmpty(), "Invalid/nonstandard Word documents found. "
                + "See reports/glossary/files.csv and errors.csv in " + output + "; " + problems.size() + " problem(s)");
        if (Boolean.getBoolean(PREFIX + "failOnFindings")) {
            assertTrue(results.findings.isEmpty(), "Glossary has " + results.findings.size()
                    + " unapproved/unknown token occurrences; see " + output);
        }
    }

    @Test
    void bundledRealStandardDocxZipProducesImportCompatibleReports(@TempDir Path temp) throws Exception {
        Path fixture = Path.of("src/test/resources/physical-real-corpus/MCB.BIM.TBL.COUNTRIES.V1.1.docx");
        assertTrue(Files.isRegularFile(fixture), "Real standard Word fixture is missing");
        Path zip = temp.resolve("standard-word-fixture.zip");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(zip))) {
            archive.putNextEntry(new ZipEntry("standard/Countries.docx"));
            archive.write(Files.readAllBytes(fixture));
            archive.closeEntry();
        }
        GlossaryLexicon lexicon = new GlossaryLexicon(Set.of("COUNTRY", "COUNTRIES"), Set.of());
        AuditResults results = new AuditResults();
        List<String> problems = new ArrayList<>();
        auditZip(zip, lexicon, results, problems);
        assertTrue(problems.isEmpty(), "Real standard Word input must parse: " + problems);
        assertEquals(1, results.scannedFiles());
        assertTrue(results.identifiers.size() > 1);
        assertTrue(results.findings.size() > 0, "The deliberately minimal lexicon must detect new terms");
        Path output = Files.createDirectories(temp.resolve("audit-evidence"));
        writeReports(output, zip, temp.resolve("unused-h2"), new Snapshot(lexicon, "fixture", 2),
                results, problems);
        Path report = output.resolve("reports/glossary/unknown-terms.csv");
        assertTrue(GlossaryReportCsvParser.parse(Files.readAllBytes(report)).sourceRows() > 0);
        assertTrue(Files.readString(output.resolve("reports/glossary/files.csv"))
                .contains("PARSED_STANDARD_DOCX"));
        assertTrue(Files.readString(output.resolve("reports/glossary/identifiers.csv"))
                .contains("REVIEW_REQUIRED"));
        assertTrue(Files.readString(output.resolve("reports/glossary/summary.json"))
                .contains("01_Atomic_Terms"));
        assertTrue(Files.isRegularFile(output.resolve("audit-evidence-manifest.json")));
    }

    @Test
    void rejectsUnsafeZipEntryNamesAndNeverSilentlyAcceptsLegacyDoc() {
        for (String path : List.of("../escape.docx", "docs/../../escape.docx", "/root.docx",
                "C:/drive.docx", "../x.doc", "docs//file.docx", "docs/./file.docx")) {
            assertThrows(IllegalArgumentException.class, () -> validateEntryName(path));
        }
        assertDoesNotThrow(() -> validateEntryName("standard/TABLE_CUSTOMER_DETAILS.docx"));
        assertFalse(isStandardDocx("legacy/CTPrsn.doc"));
        assertTrue(isStandardDocx("standard/CUSTOMER_ACCOUNT_DETAILS.DOCX"));
    }

    private static Snapshot loadReadOnlyGlossary(Path base) throws Exception {
        // Do not construct GlossaryRepository here: its constructor creates tables/indexes.
        // ACCESS_MODE_DATA=r + IFEXISTS=TRUE prevents seeding and accidental writes.
        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:file:" + base.toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE";
        Set<String> approvedAtomic = new HashSet<>();
        Set<String> approvedPhrases = new HashSet<>();
        Set<String> candidateAtomic = new HashSet<>();
        Set<String> candidatePhrases = new HashSet<>();
        Set<String> rejectedAtomic = new HashSet<>();
        Set<String> rejectedPhrases = new HashSet<>();
        String version;
        try (Connection connection = DriverManager.getConnection(url,
                System.getProperty(PREFIX + "h2User", "sa"),
                System.getProperty(PREFIX + "h2Password", ""));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT kind, term_key, status FROM glossary_term "
                     + "WHERE is_deleted=FALSE AND (kind='ATOMIC' OR source<>'ECOBANK_XLSX')")) {
            version = connection.getMetaData().getDatabaseProductVersion();
            while (rows.next()) {
                String kind = rows.getString(1);
                String key = rows.getString(2);
                String status = rows.getString(3);
                Set<String> destination = switch (kind + ":" + status) {
                    case "ATOMIC:APPROVED" -> approvedAtomic;
                    case "PHRASE:APPROVED" -> approvedPhrases;
                    case "ATOMIC:CANDIDATE" -> candidateAtomic;
                    case "PHRASE:CANDIDATE" -> candidatePhrases;
                    case "ATOMIC:REJECTED" -> rejectedAtomic;
                    case "PHRASE:REJECTED" -> rejectedPhrases;
                    default -> throw new IllegalStateException(
                            "Invalid glossary kind/status: " + kind + "/" + status);
                };
                if (!key.equals(GlossaryNormalizer.key(key))) {
                    throw new IllegalStateException("Noncanonical glossary key: " + key);
                }
                destination.add(key);
            }
        }
        GlossaryLexicon lexicon = new GlossaryLexicon(approvedAtomic, approvedPhrases,
                candidateAtomic, candidatePhrases, rejectedAtomic, rejectedPhrases);
        int total = approvedAtomic.size() + approvedPhrases.size() + candidateAtomic.size()
                + candidatePhrases.size() + rejectedAtomic.size() + rejectedPhrases.size();
        if (total == 0) throw new IllegalStateException("Glossary H2 is empty; refusing an empty vocabulary audit");
        return new Snapshot(lexicon, version, total);
    }

    private static void auditZip(Path zip, GlossaryLexicon lexicon,
                                 AuditResults results, List<String> problems) throws IOException {
        WordSpecificationParser parser = new WordSpecificationParser();
        GlossaryScanner scanner = new GlossaryScanner();
        long cumulativeBytes = 0;
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            if (archive.size() > MAX_ENTRIES) throw new IllegalArgumentException("ZIP exceeds " + MAX_ENTRIES + " entries");
            List<? extends ZipEntry> entries = archive.stream()
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList();
            Set<String> names = new HashSet<>();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                String original = entry.getName();
                try {
                    validateEntryName(original);
                } catch (IllegalArgumentException problem) {
                    results.files.add(new FileAudit(original, "UNSAFE_PATH", "", 0, 0, 0, concise(problem)));
                    problems.add(original + ": " + concise(problem));
                    continue;
                }
                String name = original.replace('\\', '/');
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    results.files.add(new FileAudit(name, "DUPLICATE", "", 0, 0, 0, "Duplicate ZIP source name"));
                    problems.add(name + ": Duplicate ZIP source name");
                    continue;
                }
                if (name.startsWith("__MACOSX/") || name.contains("/~$") || name.startsWith("~$")) {
                    results.files.add(new FileAudit(name, "IGNORED_TEMPORARY", "", 0, 0, 0, ""));
                    continue;
                }
                if (name.toLowerCase(Locale.ROOT).endsWith(".doc")) {
                    results.files.add(new FileAudit(name, "UNSUPPORTED_LEGACY_DOC", "", 0, 0, 0,
                            "Only standard DOCX is accepted; legacy DOC is excluded"));
                    problems.add(name + ": legacy .doc is not accepted");
                    continue;
                }
                if (!isStandardDocx(name)) {
                    results.files.add(new FileAudit(name, "IGNORED_NON_WORD", "", 0, 0, 0, ""));
                    continue;
                }
                if (name.length() > MAX_SOURCE_NAME) {
                    results.files.add(new FileAudit(name, "INVALID_NAME", "", 0, 0, 0, "Source name exceeds 1024 characters"));
                    problems.add(name + ": source name exceeds 1024 characters");
                    continue;
                }
                try {
                    byte[] bytes;
                    try (InputStream in = archive.getInputStream(entry)) {
                        bytes = readBounded(in, MAX_DOC_BYTES);
                    }
                    cumulativeBytes += bytes.length;
                    if (cumulativeBytes > MAX_TOTAL_DOC_BYTES) {
                        throw new IllegalArgumentException("Total uncompressed Word data exceeds 2 GiB");
                    }
                    if (entry.getCrc() >= 0) {
                        CRC32 crc = new CRC32();
                        crc.update(bytes);
                        if (crc.getValue() != entry.getCrc()) throw new IOException("ZIP CRC mismatch");
                    }
                    DatabaseSchema schema = parser.parse(
                            new SpecificationSource(name, new ByteArrayInputStream(bytes)));
                    if (schema.tables().isEmpty()) throw new IllegalArgumentException("No canonical tables found");
                    List<UnknownGlossaryTerm> findings = scanner.scan(name, schema, lexicon);
                    int identifiers = 0;
                    int approvedIdentifiers = 0;
                    for (var table : schema.tables()) {
                        String tableName = table.qualifiedName().name().value();
                        int tableFindings = addIdentifier(name, "TABLE", tableName, "", tableName,
                                findings, results.identifiers);
                        identifiers++;
                        if (tableFindings == 0) approvedIdentifiers++;
                        for (var column : table.columns()) {
                            String identifier = column.name().value();
                            int columnFindings = addIdentifier(name, "COLUMN", tableName, identifier,
                                    identifier, findings, results.identifiers);
                            identifiers++;
                            if (columnFindings == 0) approvedIdentifiers++;
                        }
                    }
                    if ((long) results.findings.size() + findings.size() > 250_000) {
                        throw new IllegalArgumentException("More than 250000 glossary findings; split the input ZIP");
                    }
                    results.findings.addAll(findings);
                    results.files.add(new FileAudit(name, "PARSED_STANDARD_DOCX", sha256(bytes),
                            schema.tables().size(), identifiers, approvedIdentifiers, ""));
                } catch (Exception exception) {
                    results.files.add(new FileAudit(name, "PARSE_FAILED", "", 0, 0, 0, concise(exception)));
                    problems.add(name + ": " + concise(exception));
                }
            }
        }
    }

    private static int addIdentifier(String source, String type, String table, String column,
                                     String identifier, List<UnknownGlossaryTerm> findings,
                                     List<IdentifierAudit> result) {
        List<UnknownGlossaryTerm> current = findings.stream()
                .filter(finding -> finding.objectType().equals(type)
                        && finding.tableName().equals(table)
                        && finding.columnName().equals(column)
                        && finding.identifier().equals(identifier))
                .toList();
        long newlyFound = current.stream().filter(f -> f.reviewStatus().equals("NEW")).count();
        long pending = current.stream().filter(f -> f.reviewStatus().equals("PENDING_APPROVAL")).count();
        long rejected = current.stream().filter(f -> f.reviewStatus().equals("REJECTED")).count();
        result.add(new IdentifierAudit(source, type, table, column, identifier,
                current.isEmpty() ? "APPROVED" : "REVIEW_REQUIRED",
                current.size(), (int) newlyFound, (int) pending, (int) rejected));
        return current.size();
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.length() > 2048
                || name.indexOf('\0') >= 0 || name.startsWith("/") || name.startsWith("\\")
                || name.matches("(?i)^[a-z]:.*")) {
            throw new IllegalArgumentException("Invalid ZIP entry path");
        }
        String normalized = name.replace('\\', '/');
        for (String component : normalized.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("Unsafe ZIP entry path component");
            }
        }
    }

    private static boolean isStandardDocx(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    private static byte[] readBounded(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if ((long) output.size() + read > limit) throw new IOException("DOCX exceeds 32 MiB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void writeReports(Path output, Path zip, Path h2Base, Snapshot snapshot,
                                     AuditResults results, List<String> problems) throws Exception {
        Path dir = Files.createDirectories(output.resolve("reports/glossary"));
        List<Path> reports = new ArrayList<>();
        StringBuilder unknown = new StringBuilder(UNKNOWN_HEADER);
        Map<String, TermOccurrence> frequencies = new TreeMap<>();
        List<Path> importReadyParts = new ArrayList<>();
        StringBuilder importPart = new StringBuilder(UNKNOWN_HEADER);
        int partBytes = UNKNOWN_HEADER.getBytes(StandardCharsets.UTF_8).length;
        int partRows = 0;
        int partNumber = 0;
        for (UnknownGlossaryTerm term : results.findings) {
            String csvRow = GlossaryCsv.row(term.sourceName(), term.objectType(), term.tableName(),
                    term.columnName(), term.identifier(), term.term(),
                    Integer.toString(term.tokenPosition()), term.reviewStatus());
            unknown.append(csvRow);
            int rowBytes = csvRow.getBytes(StandardCharsets.UTF_8).length;
            if (rowBytes + UNKNOWN_HEADER.getBytes(StandardCharsets.UTF_8).length
                    > GlossaryReportCsvParser.MAX_BYTES) {
                throw new IOException("One finding exceeds the glossary import contract size");
            }
            if (partRows > 0 && (partBytes + rowBytes > GlossaryReportCsvParser.MAX_BYTES
                    || partRows == MAX_IMPORT_ROWS)) {
                Path chunk = dir.resolve("review-import-part-" + String.format(Locale.ROOT, "%04d", ++partNumber) + ".csv");
                writeImportPart(chunk, importPart.toString());
                importReadyParts.add(chunk);
                importPart = new StringBuilder(UNKNOWN_HEADER);
                partBytes = UNKNOWN_HEADER.getBytes(StandardCharsets.UTF_8).length;
                partRows = 0;
            }
            importPart.append(csvRow);
            partBytes += rowBytes;
            partRows++;
            String key = term.reviewStatus() + ":" + term.term();
            frequencies.computeIfAbsent(key, ignored -> new TermOccurrence(term.reviewStatus(), term.term()))
                    .add(term.sourceName());
        }
        Path unknownFile = dir.resolve("unknown-terms.csv");
        Files.writeString(unknownFile, unknown.toString(), StandardCharsets.UTF_8);
        reports.add(unknownFile);

        // The regular report is directly importable while <= 5 MiB / 20000 rows.
        // Larger reports are also split into independently validated import-ready chunks.
        if (Files.size(unknownFile) <= GlossaryReportCsvParser.MAX_BYTES
                && results.findings.size() <= MAX_IMPORT_ROWS) {
            GlossaryReportCsvParser.parse(Files.readAllBytes(unknownFile));
        } else {
            if (partRows > 0) {
                Path chunk = dir.resolve("review-import-part-" + String.format(Locale.ROOT, "%04d", ++partNumber) + ".csv");
                writeImportPart(chunk, importPart.toString());
                importReadyParts.add(chunk);
            }
            reports.addAll(importReadyParts);
        }

        StringBuilder files = new StringBuilder(GlossaryCsv.row("source_name", "status", "sha256",
                "table_count", "identifier_count", "approved_identifiers", "diagnostic"));
        for (FileAudit file : results.files) {
            files.append(GlossaryCsv.row(file.source(), file.status(), file.sha256(),
                    Integer.toString(file.tables()), Integer.toString(file.identifiers()),
                    Integer.toString(file.approved()), file.message()));
        }
        Path filesCsv = dir.resolve("files.csv");
        Files.writeString(filesCsv, files.toString(), StandardCharsets.UTF_8);
        reports.add(filesCsv);

        StringBuilder identifiers = new StringBuilder(GlossaryCsv.row("source_name", "object_type", "table_name",
                "column_name", "identifier", "status", "review_occurrences", "new_terms",
                "pending_terms", "rejected_terms"));
        for (IdentifierAudit audit : results.identifiers) {
            identifiers.append(GlossaryCsv.row(audit.source(), audit.type(), audit.table(), audit.column(),
                    audit.identifier(), audit.status(), Integer.toString(audit.totalFindings()),
                    Integer.toString(audit.newTerms()), Integer.toString(audit.pending()),
                    Integer.toString(audit.rejected())));
        }
        Path identifiersCsv = dir.resolve("identifiers.csv");
        Files.writeString(identifiersCsv, identifiers.toString(), StandardCharsets.UTF_8);
        reports.add(identifiersCsv);

        StringBuilder terms = new StringBuilder(GlossaryCsv.row("review_status", "term", "occurrences",
                "affected_documents"));
        for (TermOccurrence frequency : frequencies.values()) {
            terms.append(GlossaryCsv.row(frequency.status, frequency.term,
                    Integer.toString(frequency.count), Integer.toString(frequency.sources.size())));
        }
        Path termsCsv = dir.resolve("terms-summary.csv");
        Files.writeString(termsCsv, terms.toString(), StandardCharsets.UTF_8);
        reports.add(termsCsv);

        StringBuilder errors = new StringBuilder(GlossaryCsv.row("error"));
        for (String problem : problems) errors.append(GlossaryCsv.row(problem));
        Path errorsCsv = dir.resolve("errors.csv");
        Files.writeString(errorsCsv, errors.toString(), StandardCharsets.UTF_8);
        reports.add(errorsCsv);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contract", "schemaforge-standard-word-zip-glossary-audit-v1");
        summary.put("generatedAt", Instant.now().toString());
        summary.put("referenceWorksheet", "01_Atomic_Terms");
        summary.put("zipPath", zip.toString());
        summary.put("zipSha256", sha256(zip));
        summary.put("glossaryPath", h2Base + ".mv.db");
        summary.put("glossaryEngine", snapshot.h2Version());
        summary.put("glossaryTerms", snapshot.totalTerms());
        summary.put("glossaryLexiconSha256", sha256Glossary(snapshot.lexicon()));
        summary.put("approvedAtomic", snapshot.lexicon().atomic().size());
        summary.put("approvedPhrases", snapshot.lexicon().phrases().size());
        summary.put("candidateAtomic", snapshot.lexicon().candidateAtomic().size());
        summary.put("candidatePhrases", snapshot.lexicon().candidatePhrases().size());
        summary.put("documentsParsed", results.scannedFiles());
        summary.put("zipEntriesRecorded", results.files.size());
        summary.put("tablesChecked", results.files.stream().mapToInt(FileAudit::tables).sum());
        summary.put("identifiersChecked", results.identifiers.size());
        summary.put("fullyApprovedIdentifiers", results.identifiers.stream()
                .filter(entry -> entry.status().equals("APPROVED")).count());
        summary.put("reviewOccurrences", results.findings.size());
        summary.put("newOccurrences", results.count("NEW"));
        summary.put("pendingOccurrences", results.count("PENDING_APPROVAL"));
        summary.put("rejectedOccurrences", results.count("REJECTED"));
        summary.put("uniqueReviewTerms", frequencies.size());
        summary.put("reviewImportChunks", importReadyParts.stream()
                .map(path -> output.relativize(path).toString().replace('\\', '/')).toList());
        summary.put("mainReportDirectlyImportable", importReadyParts.isEmpty());
        summary.put("errors", problems.size());
        summary.put("status", !problems.isEmpty() ? "INPUT_ERRORS"
                : results.findings.isEmpty() ? "CLEAN" : "REVIEW_REQUIRED");
        summary.put("scope", "Standard DOCX only; lexical matching, no inferred abbreviation expansion");
        summary.put("h2Access", "READ_ONLY");
        Path summaryJson = dir.resolve("summary.json");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(summaryJson.toFile(), summary);
        reports.add(summaryJson);

        List<Map<String, Object>> reportMetadata = new ArrayList<>();
        for (Path report : reports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", output.relativize(report).toString().replace('\\', '/'));
            item.put("size", Files.size(report));
            item.put("sha256", sha256(report));
            reportMetadata.add(item);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contract", "schemaforge-glossary-audit-evidence-manifest-v1");
        manifest.put("sourceSha256", sha256(zip));
        manifest.put("referenceWorksheet", "01_Atomic_Terms");
        manifest.put("reports", reportMetadata);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                output.resolve("audit-evidence-manifest.json").toFile(), manifest);
    }

    private static void writeImportPart(Path target, String csv) throws IOException {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        GlossaryReportCsvParser.parse(bytes);
        Files.write(target, bytes);
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        String safe = (message == null ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message)
                .replace('\r', ' ').replace('\n', ' ');
        return safe.substring(0, Math.min(safe.length(), 300));
    }

    private static String sha256Glossary(GlossaryLexicon lexicon) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, Set<String>> groups = new TreeMap<>();
            groups.put("APPROVED:ATOMIC", lexicon.atomic());
            groups.put("APPROVED:PHRASE", lexicon.phrases());
            groups.put("CANDIDATE:ATOMIC", lexicon.candidateAtomic());
            groups.put("CANDIDATE:PHRASE", lexicon.candidatePhrases());
            groups.put("REJECTED:ATOMIC", lexicon.rejectedAtomic());
            groups.put("REJECTED:PHRASE", lexicon.rejectedPhrases());
            for (var group : groups.entrySet()) {
                for (String key : new TreeSet<>(group.getValue())) {
                    digest.update((group.getKey() + ":" + key + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[32768];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Snapshot(GlossaryLexicon lexicon, String h2Version, int totalTerms) { }
    private record FileAudit(String source, String status, String sha256,
                             int tables, int identifiers, int approved, String message) { }
    private record IdentifierAudit(String source, String type, String table, String column, String identifier,
                                   String status, int totalFindings, int newTerms, int pending, int rejected) { }
    private static final class TermOccurrence {
        private final String status;
        private final String term;
        private int count;
        private final Set<String> sources = new TreeSet<>();
        private TermOccurrence(String status, String term) {
            this.status = status;
            this.term = term;
        }
        private void add(String source) {
            count++;
            sources.add(source);
        }
    }
    private static final class AuditResults {
        private final List<FileAudit> files = new ArrayList<>();
        private final List<IdentifierAudit> identifiers = new ArrayList<>();
        private final List<UnknownGlossaryTerm> findings = new ArrayList<>();
        private int scannedFiles() {
            return (int) files.stream().filter(file -> file.status().equals("PARSED_STANDARD_DOCX")).count();
        }
        private long count(String status) {
            return findings.stream().filter(finding -> finding.reviewStatus().equals(status)).count();
        }
    }
}
