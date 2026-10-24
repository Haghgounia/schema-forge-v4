package com.behsazan.schemaforge.glossary;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Imports the official Atomic Terms worksheet only; other worksheets are ignored. */
public final class GlossaryExcelImporter {
    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private final GlossaryRepository repository;

    public GlossaryExcelImporter(GlossaryRepository repository) {
        this.repository = repository;
    }

    public ImportResult importWorkbook(MultipartFile file, GlossaryTermStatus status)
            throws IOException, SQLException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("An XLSX workbook up to 15 MB is required");
        }
        if (file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Glossary import requires an .xlsx file");
        }
        return importWorkbook(file.getInputStream(), status);
    }

    /** Shared, byte-identical import rules for REST uploads and packaged Atomic baseline. */
    ImportResult importWorkbook(InputStream input, GlossaryTermStatus status)
            throws IOException, SQLException {
        if (input == null) throw new IllegalArgumentException("Workbook stream is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        Map<String, ImportAccumulator> entries = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int rows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet atomic = workbook.getSheet("01_Atomic_Terms");
            if (atomic == null) {
                throw new IllegalArgumentException("Required worksheet is missing: 01_Atomic_Terms");
            }
            requireHeaders(atomic, formatter, "واژه فارسی", "English Canonical");
            for (int index = 1; index <= atomic.getLastRowNum(); index++) {
                Row row = atomic.getRow(index);
                if (row == null) continue;
                String english = cell(row, 2, formatter);
                String persian = cell(row, 1, formatter);
                if (english.isBlank() && persian.isBlank()) continue;
                if (english.isBlank()) {
                    throw new IllegalArgumentException("Missing English at 01_Atomic_Terms row " + (index + 1));
                }
                String key = GlossaryNormalizer.key(english);
                // The Atomic worksheet itself contains four multiword concepts.
                // Preserve these as PHRASE entries, but never import Canonical Phrases.
                GlossaryTermKind kind = key.contains("_")
                        ? GlossaryTermKind.PHRASE : GlossaryTermKind.ATOMIC;
                String identity = kind + ":" + key;
                entries.computeIfAbsent(identity, ignored -> new ImportAccumulator(kind, key, english))
                        .add(persian, "");
                if (++rows > MAX_ROWS) {
                    throw new IllegalArgumentException("Glossary worksheet exceeds " + MAX_ROWS + " data rows");
                }
            }
        }
        List<GlossaryImportTerm> canonical = new ArrayList<>(entries.size());
        for (ImportAccumulator term : entries.values()) canonical.add(term.build());
        int inserted = repository.importNew(canonical, status);
        return new ImportResult(rows, canonical.size(), inserted, canonical.size() - inserted, status);
    }

    /** Startup-only authoritative import plus one-time migration of existing H2 rows. */
    public ApprovedBaselineResult importApprovedBaseline(InputStream input) throws IOException, SQLException {
        ParsedAtomicSheet sheet = parseAtomic(input);
        if (sheet.rows() != 946 || sheet.terms().size() != 804) {
            throw new IllegalStateException("Unexpected authoritative Atomic Terms baseline: "
                    + sheet.rows() + " rows and " + sheet.terms().size() + " entries");
        }
        int inserted = repository.importNew(sheet.terms(), GlossaryTermStatus.APPROVED);
        GlossaryRepository.BaselineApprovalResult migration = repository.approveAtomicBaselineOnce(sheet.terms());
        return new ApprovedBaselineResult(sheet.rows(), sheet.terms().size(), inserted, migration);
    }

    private ParsedAtomicSheet parseAtomic(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("Workbook stream is required");
        Map<String, ImportAccumulator> entries = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int rows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet atomic = workbook.getSheet("01_Atomic_Terms");
            if (atomic == null) throw new IllegalArgumentException("Required worksheet is missing: 01_Atomic_Terms");
            requireHeaders(atomic, formatter, "واژه فارسی", "English Canonical");
            for (int index = 1; index <= atomic.getLastRowNum(); index++) {
                Row row = atomic.getRow(index);
                if (row == null) continue;
                String english = cell(row, 2, formatter);
                String persian = cell(row, 1, formatter);
                if (english.isBlank() && persian.isBlank()) continue;
                if (english.isBlank()) throw new IllegalArgumentException("Missing English in Atomic Terms row " + (index + 1));
                String key = GlossaryNormalizer.key(english);
                GlossaryTermKind kind = key.contains("_") ? GlossaryTermKind.PHRASE : GlossaryTermKind.ATOMIC;
                String identity = kind + ":" + key;
                entries.computeIfAbsent(identity, ignored -> new ImportAccumulator(kind, key, english)).add(persian, "");
                if (++rows > MAX_ROWS) throw new IllegalArgumentException("Atomic worksheet exceeds limit");
            }
        }
        return new ParsedAtomicSheet(rows, entries.values().stream().map(ImportAccumulator::build).toList());
    }

    private record ParsedAtomicSheet(int rows, List<GlossaryImportTerm> terms) { }
    public record ApprovedBaselineResult(int sourceRows, int uniqueEntries, int inserted,
                                         GlossaryRepository.BaselineApprovalResult migration) { }

    private static void requireHeaders(Sheet sheet, DataFormatter formatter, String persian, String english) {
        Row first = sheet.getRow(0);
        if (first == null) throw new IllegalArgumentException("Missing headers in " + sheet.getSheetName());
        if (!cell(first, 1, formatter).equals(persian)
                || !cell(first, 2, formatter).equals(english)) {
            throw new IllegalArgumentException("Unexpected column headers in " + sheet.getSheetName());
        }
    }

    private static String cell(Row row, int index, DataFormatter formatter) {
        var value = row.getCell(index);
        return value == null ? "" : formatter.formatCellValue(value).strip();
    }

    private static final class ImportAccumulator {
        final GlossaryTermKind kind;
        final String key;
        final String english;
        final Set<String> persian = new LinkedHashSet<>();
        final Set<String> modules = new LinkedHashSet<>();

        ImportAccumulator(GlossaryTermKind kind, String key, String english) {
            this.kind = kind;
            this.key = key;
            this.english = english;
        }

        void add(String translation, String module) {
            if (!translation.isBlank()) persian.add(translation);
            if (!module.isBlank()) modules.add(module);
        }

        GlossaryImportTerm build() {
            return new GlossaryImportTerm(kind, key, english,
                    String.join(" | ", persian), String.join(" | ", modules));
        }
    }

    public record ImportResult(int sourceRows, int uniqueEntries, int inserted, int alreadyPresent,
                               GlossaryTermStatus status) { }
}
