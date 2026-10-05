package com.behsazan.schemaforge.specification.parser.legacy;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus probe for the legacy RTL9 dependency grid.
 *
 * <p>This test is intentionally read-only. It does not create foreign keys and does not
 * interpret the syntax of the {@code فیلد خارجی} cell. Instead it writes every populated
 * dependency row verbatim so that the private Facility corpus can be qualified before
 * canonical FK recovery is enabled.</p>
 */
class FacilityLegacyRtl9DependencyProbeIT {
    private static final String INPUT_DIR = "schemaforge.facility.wordDir";
    private static final String OUTPUT_FILE = "schemaforge.facility.dependencyProbeOutput";

    @Test
    void inventoriesPopulatedDependencyRowsWithoutInference() throws Exception {
        Path root = requiredDirectory(INPUT_DIR);
        Path output = outputFile(root);

        List<Path> documents;
        try (var stream = Files.walk(root)) {
            documents = stream
                    .filter(Files::isRegularFile)
                    .filter(FacilityLegacyRtl9DependencyProbeIT::isWordDocument)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }

        int sections = 0;
        int populatedRows = 0;
        int completeRows = 0;
        int localOnlyRows = 0;
        int foreignOnlyRows = 0;
        int readErrors = 0;
        Set<String> documentsWithPopulatedRows = new LinkedHashSet<>();
        List<ProbeRecord> records = new ArrayList<>();

        for (Path document : documents) {
            List<List<List<String>>> tables;
            try {
                tables = readTables(document);
            } catch (Exception exception) {
                readErrors++;
                records.add(ProbeRecord.error(
                        normalize(root.relativize(document)),
                        document.getFileName().toString(),
                        exception.getClass().getSimpleName() + ": " + safe(exception.getMessage())
                ));
                continue;
            }

            sections += LegacyRtl9DependencySectionProbe.countSections(tables);
            List<LegacyRtl9DependencySectionProbe.DependencyRow> rows =
                    LegacyRtl9DependencySectionProbe.extractRows(tables);
            if (!rows.isEmpty()) {
                documentsWithPopulatedRows.add(normalize(root.relativize(document)));
            }
            for (LegacyRtl9DependencySectionProbe.DependencyRow row : rows) {
                populatedRows++;
                switch (row.shape()) {
                    case "COMPLETE_PAIR" -> completeRows++;
                    case "LOCAL_ONLY" -> localOnlyRows++;
                    case "FOREIGN_ONLY" -> foreignOnlyRows++;
                    default -> throw new IllegalStateException("Unexpected dependency row shape: " + row.shape());
                }
                records.add(new ProbeRecord(
                        normalize(root.relativize(document)),
                        document.getFileName().toString(),
                        row.tableIndex() + 1,
                        row.rowIndex() + 1,
                        row.shape(),
                        row.localFieldRaw(),
                        row.foreignFieldRaw(),
                        String.join(" | ", row.rawCells()),
                        ""
                ));
            }
        }

        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        writeCsv(output, records);

        System.out.println("Facility Word documents          : " + documents.size());
        System.out.println("Dependency sections              : " + sections);
        System.out.println("Documents with populated rows    : " + documentsWithPopulatedRows.size());
        System.out.println("Populated dependency rows        : " + populatedRows);
        System.out.println("Complete local/foreign pairs     : " + completeRows);
        System.out.println("Local-only rows                  : " + localOnlyRows);
        System.out.println("Foreign-only rows                : " + foreignOnlyRows);
        System.out.println("Read errors                      : " + readErrors);
        System.out.println("Dependency probe CSV             : " + output);

        assertTrue(!documents.isEmpty(), "No Word documents were found under " + root);
        assertTrue(sections > 0, "No RTL9 dependency sections were detected under " + root);
    }

    private static List<List<List<String>>> readTables(Path document) throws IOException {
        String lower = document.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) {
            return readDocxTables(document);
        }
        if (lower.endsWith(".doc")) {
            return readDocTables(document);
        }
        return List.of();
    }

    private static List<List<List<String>>> readDocxTables(Path document) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(document), 64 * 1024);
             XWPFDocument word = new XWPFDocument(input)) {
            List<List<List<String>>> tables = new ArrayList<>();
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : word.getTables()) {
                List<List<String>> rows = new ArrayList<>();
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cleanCell(cell.getTextRecursively()));
                    }
                    rows.add(List.copyOf(cells));
                }
                tables.add(List.copyOf(rows));
            }
            return List.copyOf(tables);
        }
    }

    private static List<List<List<String>>> readDocTables(Path document) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(document), 64 * 1024);
             HWPFDocument word = new HWPFDocument(input)) {
            Range range = word.getRange();
            TableIterator iterator = new TableIterator(range);
            List<List<List<String>>> tables = new ArrayList<>();
            while (iterator.hasNext()) {
                Table table = iterator.next();
                List<List<String>> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                    TableRow row = table.getRow(rowIndex);
                    List<String> cells = new ArrayList<>();
                    for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                        TableCell cell = row.getCell(cellIndex);
                        cells.add(cleanCell(cell.text()));
                    }
                    rows.add(List.copyOf(cells));
                }
                tables.add(List.copyOf(rows));
            }
            return List.copyOf(tables);
        }
    }

    private static void writeCsv(Path output, List<ProbeRecord> records) throws IOException {
        StringBuilder csv = new StringBuilder(4096);
        csv.append('\uFEFF');
        csv.append("SOURCE_RELATIVE_PATH,SOURCE_FILE_NAME,TABLE_INDEX,ROW_INDEX,ROW_SHAPE,")
                .append("LOCAL_FIELD_RAW,FOREIGN_FIELD_RAW,RAW_ROW,READ_ERROR\r\n");
        for (ProbeRecord record : records) {
            csv.append(csv(record.sourceRelativePath())).append(',')
                    .append(csv(record.sourceFileName())).append(',')
                    .append(record.tableIndex() == null ? "" : record.tableIndex()).append(',')
                    .append(record.rowIndex() == null ? "" : record.rowIndex()).append(',')
                    .append(csv(record.rowShape())).append(',')
                    .append(csv(record.localFieldRaw())).append(',')
                    .append(csv(record.foreignFieldRaw())).append(',')
                    .append(csv(record.rawRow())).append(',')
                    .append(csv(record.readError())).append("\r\n");
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static Path requiredDirectory(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Missing required -D" + property + "=<directory>");
        }
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory does not exist: " + path);
        }
        return path;
    }

    private static Path outputFile(Path root) {
        String raw = System.getProperty(OUTPUT_FILE);
        if (raw == null || raw.isBlank()) {
            return root.resolve("facility-legacy-rtl9-dependency-probe.csv").toAbsolutePath().normalize();
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private static boolean isWordDocument(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".docx") || lower.endsWith(".doc");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String cleanCell(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u0007', ' ')
                .replace('\u000B', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private record ProbeRecord(
            String sourceRelativePath,
            String sourceFileName,
            Integer tableIndex,
            Integer rowIndex,
            String rowShape,
            String localFieldRaw,
            String foreignFieldRaw,
            String rawRow,
            String readError
    ) {
        static ProbeRecord error(String relative, String fileName, String error) {
            return new ProbeRecord(relative, fileName, null, null, "READ_ERROR", "", "", "", error);
        }
    }
}
