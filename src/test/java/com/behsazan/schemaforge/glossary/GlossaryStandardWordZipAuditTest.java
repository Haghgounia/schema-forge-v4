package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int MAX_EXCEL_DATA_ROWS_PER_SHEET = 1_048_575;
    private static final String EXCEL_REPORT = "schemaforge-glossary-word-audit-review.xlsx";
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
        System.out.println("[GLOSSARY_WORD_ZIP] Excel (Persian field names): "
                + output.resolve("reports/glossary/" + EXCEL_REPORT));

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
        Path excelPath = output.resolve("reports/glossary/" + EXCEL_REPORT);
        assertTrue(Files.isRegularFile(excelPath), "Excel audit workbook must be generated");
        try (InputStream input = Files.newInputStream(excelPath);
             XSSFWorkbook excel = new XSSFWorkbook(input)) {
            Sheet identifiers = excel.getSheet("Identifiers");
            assertNotNull(identifiers, "Identifiers sheet must exist");
            assertEquals("نام فارسی فیلد", identifiers.getRow(0).getCell(4).getStringCellValue());
            assertEquals(results.identifiers.size() + 1, identifiers.getLastRowNum() + 1);
            boolean countryIdFound = false;
            for (int index = 1; index <= identifiers.getLastRowNum(); index++) {
                Row row = identifiers.getRow(index);
                if (row != null && "COUNTRY_ID".equals(row.getCell(3).getStringCellValue())) {
                    assertEquals("شناسه کشور", row.getCell(4).getStringCellValue(),
                            "Persian field name must come from Word, not from a translation");
                    countryIdFound = true;
                }
            }
            assertTrue(countryIdFound, "Expected a COUNTRY_ID field in the real Word fixture");
            assertEquals(6, excel.getNumberOfSheets(),
                    "Must reproduce the six sheets of the user's review workbook");
            assertEquals("Term Review Queue", excel.getSheetAt(1).getSheetName());
            assertEquals("Plural Review Hints", excel.getSheetAt(2).getSheetName());
            assertEquals("Source Documents", excel.getSheetAt(3).getSheetName());
            assertEquals("Identifiers", excel.getSheetAt(4).getSheetName());
            assertEquals("Short and Acronym Hints", excel.getSheetAt(5).getSheetName());
            assertEquals("Example Field Persian Name", excel.getSheet("Term Review Queue")
                    .getRow(0).getCell(6).getStringCellValue());
            assertEquals("Reviewer Decision", excel.getSheet("Term Review Queue")
                    .getRow(0).getCell(7).getStringCellValue());
            boolean persianExampleFound = false;
            Sheet queue = excel.getSheet("Term Review Queue");
            for (int index = 1; index <= queue.getLastRowNum(); index++) {
                Row row = queue.getRow(index);
                if (row != null && "COUNTRY_ID".equals(row.getCell(4).getStringCellValue())
                        && "\u0634\u0646\u0627\u0633\u0647 \u06a9\u0634\u0648\u0631".equals(
                                row.getCell(6).getStringCellValue())) {
                    persianExampleFound = true;
                    break;
                }
            }
            assertTrue(persianExampleFound,
                    "Queue should show the Persian description for a matching example field");
        }
        assertTrue(Files.readString(output.resolve("audit-evidence-manifest.json"))
                .contains("reports/glossary/" + EXCEL_REPORT),
                "Excel must be included in the evidence manifest with a SHA-256");
    }

    @Test
    void extractsOnlyActualPersianLabelsWithoutInventingTranslations() {
        assertEquals("نام خانوادگی", persianFieldName(" نام خانوادگی "));
        assertEquals("شناسه‌مشتری", persianFieldName("شناسه‌مشتری"));
        assertEquals("", persianFieldName("Account number"));
        assertEquals("", persianFieldName(""));
        assertEquals("", persianFieldName(null));
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
                                "", findings, results.identifiers);
                        identifiers++;
                        if (tableFindings == 0) approvedIdentifiers++;
                        for (var column : table.columns()) {
                            String identifier = column.name().value();
                            int columnFindings = addIdentifier(name, "COLUMN", tableName, identifier,
                                    identifier, column.description().value(), findings, results.identifiers);
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
                                     String identifier, String wordDescription,
                                     List<UnknownGlossaryTerm> findings,
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
                current.size(), (int) newlyFound, (int) pending, (int) rejected,
                type.equals("COLUMN") ? persianFieldName(wordDescription) : "",
                type.equals("COLUMN") ? cleanWordDescription(wordDescription) : ""));
        return current.size();
    }

    /** The standard DOCX column-description cell is the source of Persian field names.
     * Keep Latin-only descriptions as source evidence, but never mislabel them as Persian.
     * No Glossary translation or guessed expansion is used here.
     */
    private static String persianFieldName(String description) {
        String cleaned = cleanWordDescription(description);
        for (int index = 0; index < cleaned.length(); index++) {
            char letter = cleaned.charAt(index);
            if (letter >= '\u0621' && letter <= '\u064A'
                    || letter >= '\u0671' && letter <= '\u06D3') {
                return cleaned;
            }
        }
        return "";
    }

    private static String cleanWordDescription(String description) {
        return description == null ? "" : description.replace('\r', ' ')
                .replace('\n', ' ').replace('\t', ' ')
                .replace('\u00A0', ' ').replaceAll(" {2,}", " ").strip();
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
        Map<String, IdentifierAudit> identifiersByKey = new LinkedHashMap<>();
        for (IdentifierAudit audit : results.identifiers) {
            identifiersByKey.putIfAbsent(identifierKey(audit.source(), audit.type(), audit.table(),
                    audit.column(), audit.identifier()), audit);
        }
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
            IdentifierAudit identifier = identifiersByKey.get(identifierKey(term.sourceName(),
                    term.objectType(), term.tableName(), term.columnName(), term.identifier()));
            String persian = identifier == null ? "" : identifier.persianName();
            frequencies.computeIfAbsent(key, ignored -> new TermOccurrence(term.reviewStatus(), term.term()))
                    .add(term.sourceName(), term.identifier(), persian);
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
        summary.put("fieldsWithPersianNames", results.identifiers.stream()
                .filter(entry -> entry.type().equals("COLUMN") && !entry.persianName().isBlank()).count());
        summary.put("xlsxReport", "reports/glossary/" + EXCEL_REPORT);
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

        Path xlsx = dir.resolve(EXCEL_REPORT);
        writeExcelReport(xlsx, summary, snapshot, results, frequencies, problems);
        reports.add(xlsx);

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

    private static String identifierKey(String source, String type, String table, String column,
                                        String identifier) {
        return String.join(String.valueOf((char) 0), source, type, table, column, identifier);
    }

    /**
     * Persist the SAME six-sheet manual review workbook as the previously shared
     * schemaforge-glossary-word-audit-review-*.xlsx. The only source for Persian field
     * labels is each DOCX column-description cell (captured in IdentifierAudit).
     */
    private static void writeExcelReport(Path target, Map<String, Object> summary,
                                         Snapshot snapshot, AuditResults results,
                                         Map<String, TermOccurrence> frequencies,
                                         List<String> problems) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFont(headerFont);
            headerStyle.setWrapText(true);

            CellStyle persianStyle = workbook.createCellStyle();
            persianStyle.setAlignment(HorizontalAlignment.RIGHT);
            persianStyle.setWrapText(true);
            Font persianFont = workbook.createFont();
            persianFont.setFontName("Tahoma");
            persianStyle.setFont(persianFont);

            CellStyle reviewStyle = workbook.createCellStyle();
            Font reviewFont = workbook.createFont();
            reviewFont.setColor(IndexedColors.BLUE.getIndex());
            reviewStyle.setFont(reviewFont);
            reviewStyle.setWrapText(true);

            // Sheet 1: original audit summary, with explicit Persian extraction evidence.
            Sheet overview = workbook.createSheet("Overview");
            overview.setColumnWidth(0, 45 * 256);
            overview.setColumnWidth(1, 100 * 256);
            for (int i = 2; i < 5; i++) overview.setColumnWidth(i, 20 * 256);
            Row title = overview.createRow(0);
            title.createCell(0).setCellValue("SchemaForge | Standard Word Glossary Audit");
            title.getCell(0).setCellStyle(headerStyle);
            overview.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
            excelRow(overview, 2, persianStyle, -1,
                    "Read-only evidence review - manual decisions only; no changes to H2.");
            Object[][] metrics = {
                    {"Source ZIP", summary.get("zipPath")},
                    {"ZIP SHA-256", summary.get("zipSha256")},
                    {"Glossary H2 Engine", summary.get("glossaryEngine")},
                    {"Glossary entries", summary.get("glossaryTerms")},
                    {"Approved terms", snapshot.lexicon().atomic().size()
                            + snapshot.lexicon().phrases().size()},
                    {"Candidate atomic terms", summary.get("candidateAtomic")},
                    {"Candidate phrase terms", summary.get("candidatePhrases")},
                    {"Parsed standard DOCX", summary.get("documentsParsed")},
                    {"Tables", summary.get("tablesChecked")},
                    {"Identifiers", summary.get("identifiersChecked")},
                    {"Fields with Persian names from Word", summary.get("fieldsWithPersianNames")},
                    {"Fully approved identifiers", summary.get("fullyApprovedIdentifiers")},
                    {"Review occurrences", summary.get("reviewOccurrences")},
                    {"NEW occurrences", summary.get("newOccurrences")},
                    {"PENDING_APPROVAL occurrences", summary.get("pendingOccurrences")},
                    {"Unique review terms", summary.get("uniqueReviewTerms")},
                    {"Unique NEW terms", frequencies.values().stream()
                            .filter(f -> f.status.equals("NEW")).count()},
                    {"Unique PENDING_APPROVAL terms", frequencies.values().stream()
                            .filter(f -> f.status.equals("PENDING_APPROVAL")).count()},
                    {"Parser errors", summary.get("errors")},
                    {"Reference sheet", summary.get("referenceWorksheet")},
                    {"Audit status", summary.get("status")}
            };
            int metricRow = 4;
            for (Object[] metric : metrics) {
                excelRow(overview, metricRow++, persianStyle, -1, metric);
            }
            metricRow += 2;
            excelRow(overview, metricRow++, persianStyle, -1, "Review and interpretation notes");
            String[] notes = {
                    "Persian field names come ONLY from the corresponding standard DOCX column label; never from automatic translation.",
                    "If a Word column lacks Persian text, the Persian field name is blank; the field is not auto-translated.",
                    "The original CSV report contracts remain unchanged, including unknown-terms.csv for import.",
                    "Successful standard DOCX parsing does NOT establish that all identifiers are meaningful or unabbreviated.",
                    "Plural Review Hints and Short and Acronym Hints are heuristic candidates, not automatic approval.",
                    "Reviewer Decision and Reviewer Notes are manual worksheet cells; this file does not alter H2.",
                    "This workbook is included in audit-evidence-manifest.json with SHA-256 evidence."
            };
            for (String note : notes) excelRow(overview, metricRow++, persianStyle, -1, note);

            List<TermOccurrence> byFrequency = frequencies.values().stream()
                    .sorted(Comparator.comparingInt((TermOccurrence f) -> f.count).reversed()
                            .thenComparing(f -> f.term).thenComparing(f -> f.status))
                    .toList();

            // Sheet 2: same review queue as the user's example, plus a Persian
            // label from the EXAMPLE column (where one is present in the Word).
            String[] termHeaders = {"Current Status", "Term", "Occurrences", "Affected DOCX",
                    "Example Identifier", "Example Source Document",
                    "Example Field Persian Name", "Reviewer Decision", "Reviewer Notes"};
            int[] termWidths = {26, 28, 18, 20, 45, 65, 55, 25, 55};
            int termPart = 1;
            Sheet terms = excelSheet(workbook, "Term Review Queue", headerStyle,
                    termHeaders, termWidths);
            int termRow = 1;
            for (TermOccurrence f : byFrequency) {
                if (termRow > MAX_EXCEL_DATA_ROWS_PER_SHEET) {
                    applyFilter(terms, termRow, termHeaders.length);
                    terms = excelSheet(workbook, "Term Review " + (++termPart), headerStyle,
                            termHeaders, termWidths);
                    termRow = 1;
                }
                excelRow(terms, termRow, persianStyle, 6,
                        f.status, f.term, f.count, f.sources.size(), f.exampleIdentifier,
                        f.exampleSource, f.examplePersian, "", "");
                Row row = terms.getRow(termRow);
                row.getCell(7).setCellStyle(reviewStyle);
                row.getCell(8).setCellStyle(reviewStyle);
                termRow++;
            }
            applyFilter(terms, termRow, termHeaders.length);

            // Sheet 3: inflection hints only, based on already-existing candidate
            // ATOMIC terms. These hints never add/approve Glossary entries.
            Sheet plurals = excelSheet(workbook, "Plural Review Hints", headerStyle,
                    new String[]{"New Term", "Existing CANDIDATE Base", "Occurrences",
                            "Affected DOCX", "Interpretation"},
                    new int[]{35, 38, 19, 21, 50});
            int pluralRow = 1;
            for (TermOccurrence f : byFrequency) {
                if (!f.status.equals("NEW")) continue;
                String base = pluralCandidateBase(f.term, snapshot.lexicon().candidateAtomic());
                if (base == null) continue;
                excelRow(plurals, pluralRow++, persianStyle, -1,
                        f.term, base, f.count, f.sources.size(),
                        "HEURISTIC_ONLY_DO_NOT_AUTO_APPROVE");
            }
            applyFilter(plurals, pluralRow, 5);

            // Sheet 4: original 6-column source-document summary.
            Sheet documents = excelSheet(workbook, "Source Documents", headerStyle,
                    new String[]{"Source DOCX", "Parser Status", "Tables", "Identifiers",
                            "Approved Identifiers", "Diagnostic"},
                    new int[]{70, 30, 16, 20, 28, 65});
            int documentRow = 1;
            for (FileAudit file : results.files) {
                excelRow(documents, documentRow++, persianStyle, -1,
                        file.source(), file.status(), file.tables(), file.identifiers(),
                        file.approved(), file.message());
            }
            applyFilter(documents, documentRow, 6);

            // Sheet 5: IDENTICAL identifiers.csv row order, with precisely one
            // extra column E containing the Persian name from each source DOCX.
            String[] identifierHeaders = {"Source DOCX", "Object Type", "Table", "Column",
                    "\u0646\u0627\u0645 \u0641\u0627\u0631\u0633\u06cc \u0641\u06cc\u0644\u062f",
                    "Identifier", "Audit Status", "Occurrences", "NEW", "PENDING", "REJECTED"};
            int[] identifierWidths = {68, 18, 42, 45, 58, 48, 25, 20, 15, 17, 18};
            Sheet identifiers = excelSheet(workbook, "Identifiers", headerStyle,
                    identifierHeaders, identifierWidths);
            int identifierPart = 1;
            int identifierRow = 1;
            for (IdentifierAudit item : results.identifiers) {
                if (identifierRow > MAX_EXCEL_DATA_ROWS_PER_SHEET) {
                    applyFilter(identifiers, identifierRow, identifierHeaders.length);
                    identifiers = excelSheet(workbook, "Identifiers " + (++identifierPart),
                            headerStyle, identifierHeaders, identifierWidths);
                    identifierRow = 1;
                }
                excelRow(identifiers, identifierRow++, persianStyle, 4,
                        item.source(), item.type(), item.table(), item.column(),
                        item.persianName(), item.identifier(), item.status(),
                        item.totalFindings(), item.newTerms(), item.pending(), item.rejected());
            }
            applyFilter(identifiers, identifierRow, identifierHeaders.length);

            // Sheet 6: conservative screening of genuinely short tokens or
            // high-frequency short pending tokens; not proof of abbreviation.
            Sheet abbreviations = excelSheet(workbook, "Short and Acronym Hints", headerStyle,
                    new String[]{"Review Status", "Term", "Occurrences", "Affected DOCX",
                            "Screening Hint (Not a Judgment)"},
                    new int[]{27, 32, 18, 23, 85});
            int hintRow = 1;
            Set<String> flaggedLongForms = Set.of("DESC", "SUMOF", "SUBGROUP");
            for (TermOccurrence f : byFrequency) {
                boolean shortNew = f.status.equals("NEW") && f.term.length() <= 3;
                boolean highFrequencyShort = f.status.equals("PENDING_APPROVAL")
                        && f.term.length() <= 3 && f.count >= 500;
                boolean flagged = f.status.equals("NEW") && flaggedLongForms.contains(f.term);
                if (!shortNew && !highFrequencyShort && !flagged) continue;
                excelRow(abbreviations, hintRow++, persianStyle, -1,
                        f.status, f.term, f.count, f.sources.size(),
                        (shortNew || highFrequencyShort)
                                ? "Short token (<=3 chars); could be a normal word or shorthand"
                                : "Worth manual abbreviation/semantic review");
            }
            applyFilter(abbreviations, hintRow, 5);

            try (OutputStream output = Files.newOutputStream(target)) {
                workbook.write(output);
            }
        } finally {
            try {
                workbook.close();
            } finally {
                workbook.dispose();
            }
        }
    }

    private static String pluralCandidateBase(String token, Set<String> candidateAtomic) {
        List<String> possibilities = new ArrayList<>();
        if (token.endsWith("IES") && token.length() > 3)
            possibilities.add(token.substring(0, token.length() - 3) + "Y");
        if (token.endsWith("ES") && token.length() > 2)
            possibilities.add(token.substring(0, token.length() - 2));
        if (token.endsWith("S") && token.length() > 1)
            possibilities.add(token.substring(0, token.length() - 1));
        for (String base : possibilities) {
            if (candidateAtomic.contains(base)) return base;
        }
        return null;
    }

    private static Sheet excelSheet(SXSSFWorkbook workbook, String name, CellStyle headerStyle,
                                    String[] headers, int[] widths) {
        Sheet sheet = workbook.createSheet(name);
        sheet.createFreezePane(0, 1);
        sheet.setDefaultRowHeightInPoints(18);
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < headers.length; index++) {
            sheet.setColumnWidth(index, Math.min(255, widths[index]) * 256);
            Cell cell = header.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(headerStyle);
        }
        return sheet;
    }

    private static void excelRow(Sheet sheet, int index, CellStyle persianStyle,
                                 int persianColumn, Object... values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            Cell cell = row.createCell(column);
            Object value = values[column];
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(excelText(value));
            }
            if (column == persianColumn) cell.setCellStyle(persianStyle);
        }
    }

    private static String excelText(Object value) {
        String text = value == null ? "" : value.toString();
        // Excel cells support at most 32767 UTF-16 code units.
        return text.length() <= 32767 ? text : text.substring(0, 32764) + "...";
    }

    private static void applyFilter(Sheet sheet, int nextRow, int columns) {
        if (nextRow > 1) sheet.setAutoFilter(new CellRangeAddress(0, nextRow - 1, 0, columns - 1));
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
                                   String status, int totalFindings, int newTerms, int pending, int rejected,
                                   String persianName, String wordDescription) { }
    private static final class TermOccurrence {
        private final String status;
        private final String term;
        private int count;
        private final Set<String> sources = new TreeSet<>();
        private String exampleSource = "";
        private String exampleIdentifier = "";
        private String examplePersian = "";
        private TermOccurrence(String status, String term) {
            this.status = status;
            this.term = term;
        }
        private void add(String source, String identifier, String persian) {
            count++;
            sources.add(source);
            if (exampleSource.isEmpty() || (examplePersian.isEmpty() && !persian.isEmpty())) {
                exampleSource = source;
                exampleIdentifier = identifier;
                examplePersian = persian;
            }
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
