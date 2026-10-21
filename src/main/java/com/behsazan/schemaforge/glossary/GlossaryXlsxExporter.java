package com.behsazan.schemaforge.glossary;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** A read-only full export of the same active/effective scope as /glossary/export.csv. */
public final class GlossaryXlsxExporter {
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_EXCEL_DATA_ROWS = 1_048_575;
    private static final String[] HEADERS = {
            "ID", "Kind", "Key", "English", "Persian", "Module", "Status",
            "Source", "Created At", "Updated At"
    };
    private final GlossaryRepository repository;

    public GlossaryXlsxExporter(GlossaryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public byte[] export() throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            try {
                Sheet summary = workbook.createSheet("Overview");
                Sheet entries = workbook.createSheet("Glossary");
                entries.createFreezePane(0, 1);
                entries.setRightToLeft(false);
                CellStyle header = headerStyle(workbook);
                Row headerRow = entries.createRow(0);
                for (int c = 0; c < HEADERS.length; c++) {
                    var cell = headerRow.createCell(c);
                    cell.setCellValue(HEADERS[c]);
                    cell.setCellStyle(header);
                }
                int rowIndex = 1;
                int page = 0;
                Map<GlossaryTermStatus, Integer> statuses = new EnumMap<>(GlossaryTermStatus.class);
                Map<GlossaryTermKind, Integer> kinds = new EnumMap<>(GlossaryTermKind.class);
                GlossaryPage batch;
                do {
                    batch = repository.search(null, null, null, page++, PAGE_SIZE);
                    for (GlossaryTerm term : batch.items()) {
                        if (rowIndex > MAX_EXCEL_DATA_ROWS) {
                            throw new IllegalStateException("Glossary exceeds the Excel worksheet row limit");
                        }
                        Row row = entries.createRow(rowIndex++);
                        row.createCell(0).setCellValue((double) term.id());
                        stringCell(row, 1, term.kind().name());
                        stringCell(row, 2, term.key());
                        stringCell(row, 3, term.english());
                        stringCell(row, 4, term.persian());
                        stringCell(row, 5, term.module());
                        stringCell(row, 6, term.status().name());
                        stringCell(row, 7, term.source());
                        stringCell(row, 8, term.createdAt());
                        stringCell(row, 9, term.updatedAt());
                        statuses.merge(term.status(), 1, Integer::sum);
                        kinds.merge(term.kind(), 1, Integer::sum);
                    }
                } while (!batch.items().isEmpty());
                entries.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, HEADERS.length - 1));
                int[] widths = {13, 15, 35, 38, 38, 32, 23, 28, 35, 35};
                for (int c = 0; c < widths.length; c++) entries.setColumnWidth(c, widths[c] * 256);

                addSummary(summary, header, rowIndex - 1, statuses, kinds);
                workbook.write(output);
                return output.toByteArray();
            } finally {
                workbook.dispose();
            }
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static void addSummary(Sheet sheet, CellStyle header, int total,
                                   Map<GlossaryTermStatus, Integer> statuses,
                                   Map<GlossaryTermKind, Integer> kinds) {
        var labels = new String[][] {
                {"Contract", "schemaforge-glossary-full-export-v1"},
                {"Exported at (UTC)", Instant.now().toString()},
                {"Scope", "Active, effective glossary terms; all statuses; no pagination"},
                {"Excluded", "Soft-deleted terms and inactive legacy ECOBANK_XLSX phrases"},
                {"Read only", "Yes (does not insert, update, approve, or reject terms)"},
                {"Importable", "No. This workbook is a complete review/export, not 01_Atomic_Terms input"},
                {"Total terms", Integer.toString(total)},
                {"Atomic", Integer.toString(kinds.getOrDefault(GlossaryTermKind.ATOMIC, 0))},
                {"Phrase", Integer.toString(kinds.getOrDefault(GlossaryTermKind.PHRASE, 0))},
                {"Candidate", Integer.toString(statuses.getOrDefault(GlossaryTermStatus.CANDIDATE, 0))},
                {"Approved", Integer.toString(statuses.getOrDefault(GlossaryTermStatus.APPROVED, 0))},
                {"Rejected", Integer.toString(statuses.getOrDefault(GlossaryTermStatus.REJECTED, 0))}
        };
        for (int i = 0; i < labels.length; i++) {
            Row row = sheet.createRow(i);
            var name = row.createCell(0);
            name.setCellValue(labels[i][0]);
            name.setCellStyle(header);
            row.createCell(1).setCellValue(labels[i][1]);
        }
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 89 * 256);
        sheet.createFreezePane(0, 1);
    }

    private static void stringCell(Row row, int column, String value) {
        // Explicit string cells ensure user-supplied '=' values are never exported as formulas.
        row.createCell(column).setCellValue(value == null ? "" : value);
    }
}
