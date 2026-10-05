package com.behsazan.schemaforge.reporting;

import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consolidates a directory of canonical SchemaForge snapshots into one Excel catalog.
 *
 * <p>The workbook is deliberately read-only/reporting oriented: it does not reinterpret or repair
 * parser output. Every value is copied from the canonical snapshot contract. This makes the file a
 * convenient review/export surface while preserving fail-closed recovery markers such as
 * {@code MISSING_DATA_TYPE} or unresolved character lengths.</p>
 */
public final class CanonicalSnapshotCatalogExcelWriter {
    private static final int EXCEL_TEXT_LIMIT = 32_767;

    private static final String[] TABLE_HEADERS = {
            "SOURCE_RELATIVE_PATH", "SOURCE_FILE_NAME", "SOURCE_SHA256", "PARSER_ID",
            "SNAPSHOT_VERSION", "MODEL_VERSION", "PARSER_VERSION", "GENERATED_AT_UTC",
            "SCHEMA", "TABLE_SCHEMA", "TABLE_NAME", "PERSIAN_NAME", "DESCRIPTION",
            "COLUMN_COUNT", "PRIMARY_KEY_NAME", "PRIMARY_KEY_COLUMNS",
            "FOREIGN_KEY_COUNT", "UNIQUE_KEY_COUNT", "CHECK_CONSTRAINT_COUNT", "INDEX_COUNT",
            "TABLE_PHYSICAL_OPTIONS"
    };

    private static final String[] FIELD_HEADERS = {
            "SOURCE_RELATIVE_PATH", "SOURCE_FILE_NAME", "SOURCE_SHA256", "PARSER_ID",
            "SCHEMA", "TABLE_SCHEMA", "TABLE_NAME", "PERSIAN_TABLE_NAME",
            "ORDINAL_POSITION", "FIELD_NAME", "DATA_TYPE", "LENGTH", "LENGTH_SEMANTICS",
            "PRECISION", "SCALE", "NULLABLE", "DEFAULT_EXPRESSION", "DESCRIPTION",
            "IDENTITY", "GENERATED_EXPRESSION", "FIELD_PHYSICAL_OPTIONS"
    };

    private static final String[] OBJECT_HEADERS = {
            "SOURCE_RELATIVE_PATH", "SCHEMA", "TABLE_SCHEMA", "TABLE_NAME",
            "OBJECT_TYPE", "OBJECT_NAME", "COLUMNS", "DEFINITION"
    };

    private static final String[] METADATA_HEADERS = {
            "SOURCE_RELATIVE_PATH", "SOURCE_FILE_NAME", "SCHEMA", "TABLE_NAME", "KEY", "VALUE"
    };

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Writes the workbook and returns summary counts. */
    public Result write(Path snapshotRoot, Path outputFile) throws IOException {
        Objects.requireNonNull(snapshotRoot, "snapshotRoot");
        Objects.requireNonNull(outputFile, "outputFile");
        Path root = snapshotRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Snapshot directory does not exist: " + root);
        }

        List<Path> snapshotFiles;
        try (var paths = Files.walk(root)) {
            snapshotFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(CanonicalSnapshotCatalogExcelWriter::isSnapshotFile)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }
        if (snapshotFiles.isEmpty()) {
            throw new IllegalArgumentException("No *.schema.json snapshots found under: " + root);
        }

        List<CanonicalSchemaSnapshot> snapshots = new ArrayList<>(snapshotFiles.size());
        for (Path file : snapshotFiles) {
            snapshots.add(store.readSnapshot(file));
        }

        int tableCount = 0;
        int fieldCount = 0;
        int unresolvedDatatypeCount = 0;
        int unresolvedCharacterLengthCount = 0;
        Set<String> parserVersions = new LinkedHashSet<>();
        Set<String> schemas = new LinkedHashSet<>();

        Path absoluteOutput = outputFile.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            Sheet summary = workbook.createSheet("SUMMARY");
            Sheet tables = workbook.createSheet("TABLES");
            Sheet fields = workbook.createSheet("FIELDS");
            Sheet objects = workbook.createSheet("OBJECTS");
            Sheet metadata = workbook.createSheet("METADATA");

            writeHeader(tables, TABLE_HEADERS, styles.header);
            writeHeader(fields, FIELD_HEADERS, styles.header);
            writeHeader(objects, OBJECT_HEADERS, styles.header);
            writeHeader(metadata, METADATA_HEADERS, styles.header);

            int tableRow = 1;
            int fieldRow = 1;
            int objectRow = 1;
            int metadataRow = 1;

            for (CanonicalSchemaSnapshot snapshot : snapshots) {
                CanonicalSchemaSnapshot.SourceSnapshot source = snapshot.source();
                CanonicalSchemaSnapshot.SchemaSnapshot schema = snapshot.schema();
                if (snapshot.parserVersion() != null && !snapshot.parserVersion().isBlank()) {
                    parserVersions.add(snapshot.parserVersion());
                }
                if (schema != null && schema.name() != null && !schema.name().isBlank()) {
                    schemas.add(schema.name());
                }

                if (schema != null) {
                    for (Map.Entry<String, String> entry : safeMap(schema.metadata()).entrySet()) {
                        Row row = metadata.createRow(metadataRow++);
                        set(row, 0, source == null ? "" : source.relativePath(), styles.imported);
                        set(row, 1, source == null ? "" : source.fileName(), styles.imported);
                        set(row, 2, schema.name(), styles.imported);
                        set(row, 3, "", styles.staticText);
                        set(row, 4, entry.getKey(), styles.staticText);
                        set(row, 5, entry.getValue(), styles.imported);
                    }
                }

                for (CanonicalSchemaSnapshot.TableSnapshot table : safeTables(snapshot)) {
                    tableCount++;
                    List<CanonicalSchemaSnapshot.ColumnSnapshot> columns = safeList(table.columns());
                    fieldCount += columns.size();

                    Row tableExcelRow = tables.createRow(tableRow++);
                    int c = 0;
                    set(tableExcelRow, c++, source == null ? "" : source.relativePath(), styles.imported);
                    set(tableExcelRow, c++, source == null ? "" : source.fileName(), styles.imported);
                    set(tableExcelRow, c++, source == null ? "" : source.sha256(), styles.imported);
                    set(tableExcelRow, c++, source == null ? "" : source.parserId(), styles.imported);
                    set(tableExcelRow, c++, snapshot.snapshotVersion(), styles.imported);
                    set(tableExcelRow, c++, snapshot.modelVersion(), styles.imported);
                    set(tableExcelRow, c++, snapshot.parserVersion(), styles.imported);
                    set(tableExcelRow, c++, snapshot.generatedAtUtc(), styles.imported);
                    set(tableExcelRow, c++, schema == null ? "" : schema.name(), styles.imported);
                    set(tableExcelRow, c++, table.schema(), styles.imported);
                    set(tableExcelRow, c++, table.name(), styles.imported);
                    set(tableExcelRow, c++, table.persianName(), styles.imported);
                    set(tableExcelRow, c++, table.description(), styles.imported);
                    set(tableExcelRow, c++, columns.size(), styles.imported);
                    CanonicalSchemaSnapshot.PrimaryKeySnapshot pk = table.primaryKey();
                    set(tableExcelRow, c++, pk == null ? "" : pk.name(), styles.imported);
                    set(tableExcelRow, c++, pk == null ? "" : join(pk.columns()), styles.imported);
                    set(tableExcelRow, c++, safeList(table.foreignKeys()).size(), styles.imported);
                    set(tableExcelRow, c++, safeList(table.uniqueKeys()).size(), styles.imported);
                    set(tableExcelRow, c++, safeList(table.checkConstraints()).size(), styles.imported);
                    set(tableExcelRow, c++, safeList(table.indexes()).size(), styles.imported);
                    set(tableExcelRow, c, json(table.physicalOptions()), styles.imported);

                    if (pk != null) {
                        objectRow = writeObject(objects, objectRow, source, schema, table,
                                "PRIMARY_KEY", pk.name(), join(pk.columns()), json(pk), styles);
                    }
                    for (CanonicalSchemaSnapshot.ForeignKeySnapshot fk : safeList(table.foreignKeys())) {
                        objectRow = writeObject(objects, objectRow, source, schema, table,
                                "FOREIGN_KEY", fk.name(), join(fk.columns()), json(fk), styles);
                    }
                    for (CanonicalSchemaSnapshot.UniqueKeySnapshot uk : safeList(table.uniqueKeys())) {
                        objectRow = writeObject(objects, objectRow, source, schema, table,
                                "UNIQUE_KEY", uk.name(), join(uk.columns()), json(uk), styles);
                    }
                    for (CanonicalSchemaSnapshot.CheckConstraintSnapshot check : safeList(table.checkConstraints())) {
                        objectRow = writeObject(objects, objectRow, source, schema, table,
                                "CHECK", check.name(), "", json(check), styles);
                    }
                    for (CanonicalSchemaSnapshot.IndexSnapshot index : safeList(table.indexes())) {
                        String indexColumns = safeList(index.columns()).stream()
                                .map(value -> value.column() == null || value.column().isBlank()
                                        ? value.expression() : value.column())
                                .filter(Objects::nonNull)
                                .reduce((left, right) -> left + ", " + right)
                                .orElse("");
                        objectRow = writeObject(objects, objectRow, source, schema, table,
                                "INDEX", index.name(), indexColumns, json(index), styles);
                    }

                    for (CanonicalSchemaSnapshot.ColumnSnapshot column : columns) {
                        CanonicalSchemaSnapshot.DataTypeSnapshot type = column.dataType();
                        String typeName = type == null ? "" : type.name();
                        if ("MISSING_DATA_TYPE".equalsIgnoreCase(typeName)) {
                            unresolvedDatatypeCount++;
                        }
                        if (isUnresolvedCharacterLength(column, type)) {
                            unresolvedCharacterLengthCount++;
                        }

                        Row fieldExcelRow = fields.createRow(fieldRow++);
                        int f = 0;
                        set(fieldExcelRow, f++, source == null ? "" : source.relativePath(), styles.imported);
                        set(fieldExcelRow, f++, source == null ? "" : source.fileName(), styles.imported);
                        set(fieldExcelRow, f++, source == null ? "" : source.sha256(), styles.imported);
                        set(fieldExcelRow, f++, source == null ? "" : source.parserId(), styles.imported);
                        set(fieldExcelRow, f++, schema == null ? "" : schema.name(), styles.imported);
                        set(fieldExcelRow, f++, table.schema(), styles.imported);
                        set(fieldExcelRow, f++, table.name(), styles.imported);
                        set(fieldExcelRow, f++, table.persianName(), styles.imported);
                        set(fieldExcelRow, f++, column.ordinalPosition(), styles.imported);
                        set(fieldExcelRow, f++, column.name(), styles.imported);
                        set(fieldExcelRow, f++, typeName, cautionStyle(typeName, styles));
                        set(fieldExcelRow, f++, type == null ? null : type.length(), styles.imported);
                        set(fieldExcelRow, f++, type == null ? "" : type.lengthSemantics(), styles.imported);
                        set(fieldExcelRow, f++, type == null ? null : type.precision(), styles.imported);
                        set(fieldExcelRow, f++, type == null ? null : type.scale(), styles.imported);
                        set(fieldExcelRow, f++, column.nullable(), styles.imported);
                        set(fieldExcelRow, f++, column.defaultExpression(), styles.imported);
                        set(fieldExcelRow, f++, column.description(), styles.imported);
                        set(fieldExcelRow, f++, column.identity(), styles.imported);
                        set(fieldExcelRow, f++, column.generatedExpression(), styles.imported);
                        set(fieldExcelRow, f, json(column.physicalOptions()),
                                isUnresolvedCharacterLength(column, type) ? styles.caution : styles.imported);
                    }
                }
            }

            configure(tables, TABLE_HEADERS.length, tableRow);
            configure(fields, FIELD_HEADERS.length, fieldRow);
            configure(objects, OBJECT_HEADERS.length, objectRow);
            configure(metadata, METADATA_HEADERS.length, metadataRow);
            setWidths(tables, 44, 38, 68, 18, 18, 16, 46, 26, 20, 20, 30, 36, 60, 14, 34, 52, 16, 16, 20, 14, 70);
            setWidths(fields, 44, 38, 68, 18, 20, 20, 30, 36, 16, 34, 22, 12, 18, 12, 12, 12, 34, 70, 12, 42, 70);
            setWidths(objects, 44, 20, 20, 30, 20, 38, 60, 100);
            setWidths(metadata, 44, 38, 20, 30, 42, 110);

            writeSummary(summary, styles, root, absoluteOutput, snapshotFiles.size(), tableCount, fieldCount,
                    unresolvedDatatypeCount, unresolvedCharacterLengthCount, parserVersions, schemas);

            workbook.setActiveSheet(0);
            try (OutputStream output = Files.newOutputStream(absoluteOutput)) {
                workbook.write(output);
            }
        }

        return new Result(snapshotFiles.size(), tableCount, fieldCount,
                unresolvedDatatypeCount, unresolvedCharacterLengthCount, absoluteOutput);
    }

    private static int writeObject(
            Sheet sheet,
            int rowNumber,
            CanonicalSchemaSnapshot.SourceSnapshot source,
            CanonicalSchemaSnapshot.SchemaSnapshot schema,
            CanonicalSchemaSnapshot.TableSnapshot table,
            String objectType,
            String objectName,
            String columns,
            String definition,
            Styles styles) {
        Row row = sheet.createRow(rowNumber);
        set(row, 0, source == null ? "" : source.relativePath(), styles.imported);
        set(row, 1, schema == null ? "" : schema.name(), styles.imported);
        set(row, 2, table.schema(), styles.imported);
        set(row, 3, table.name(), styles.imported);
        set(row, 4, objectType, styles.staticText);
        set(row, 5, objectName, styles.imported);
        set(row, 6, columns, styles.imported);
        set(row, 7, definition, styles.imported);
        return rowNumber + 1;
    }

    private static void writeSummary(
            Sheet sheet,
            Styles styles,
            Path snapshotRoot,
            Path output,
            int snapshots,
            int tables,
            int fields,
            int unresolvedDatatype,
            int unresolvedCharacterLength,
            Set<String> parserVersions,
            Set<String> schemas) {
        sheet.setDisplayGridlines(false);
        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("SchemaForge Table / Field Catalog");
        titleCell.setCellStyle(styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        String[][] values = {
                {"Generated at UTC", Instant.now().toString()},
                {"Snapshot directory", normalize(snapshotRoot)},
                {"Workbook", normalize(output)},
                {"Snapshot files", Integer.toString(snapshots)},
                {"Tables", Integer.toString(tables)},
                {"Fields", Integer.toString(fields)},
                {"Unresolved datatypes", Integer.toString(unresolvedDatatype)},
                {"Unresolved character lengths", Integer.toString(unresolvedCharacterLength)},
                {"Schemas", String.join(", ", schemas)},
                {"Parser versions", String.join(", ", parserVersions)}
        };
        for (int i = 0; i < values.length; i++) {
            Row row = sheet.createRow(i + 2);
            set(row, 0, values[i][0], styles.staticText);
            set(row, 1, values[i][1], (i == 6 || i == 7) && !"0".equals(values[i][1]) ? styles.caution : styles.imported);
        }
        sheet.setColumnWidth(0, 34 * 256);
        sheet.setColumnWidth(1, 110 * 256);
        sheet.setColumnWidth(2, 4 * 256);
    }

    private static void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            set(row, i, headers[i], style);
        }
    }

    private static void configure(Sheet sheet, int columnCount, int rowCount) {
        sheet.createFreezePane(0, 1);
        sheet.setDisplayGridlines(false);
        if (rowCount > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowCount - 1, 0, columnCount - 1));
        }
    }

    private static void setWidths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, Math.min(255, widths[i]) * 256);
        }
    }

    private static CellStyle cautionStyle(String typeName, Styles styles) {
        return "MISSING_DATA_TYPE".equalsIgnoreCase(typeName) ? styles.caution : styles.imported;
    }

    private static boolean isUnresolvedCharacterLength(
            CanonicalSchemaSnapshot.ColumnSnapshot column,
            CanonicalSchemaSnapshot.DataTypeSnapshot type) {
        if (column == null || type == null || type.length() != null) return false;
        String name = type.name() == null ? "" : type.name().trim().toUpperCase(Locale.ROOT);
        if (!(name.equals("CHAR") || name.equals("CHARACTER") || name.equals("VARCHAR") || name.equals("VARCHAR2"))) {
            return false;
        }
        String marker = safeMap(column.physicalOptions()).get("schemaforge.recovery.unresolvedCharacterLength");
        return Boolean.parseBoolean(marker);
    }

    private String json(Object value) {
        if (value == null) return "";
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private static String join(List<String> values) {
        return String.join(", ", safeList(values));
    }

    private static List<CanonicalSchemaSnapshot.TableSnapshot> safeTables(CanonicalSchemaSnapshot snapshot) {
        if (snapshot == null || snapshot.schema() == null) return List.of();
        return safeList(snapshot.schema().tables());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static boolean isSnapshotFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schema.json");
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void set(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            String text = String.valueOf(value);
            if (text.length() > EXCEL_TEXT_LIMIT) {
                text = text.substring(0, EXCEL_TEXT_LIMIT - 15) + "...[TRUNCATED]";
            }
            cell.setCellValue(text);
        }
        cell.setCellStyle(style);
    }

    /** Summary returned to callers and integration runners. */
    public record Result(
            int snapshotFiles,
            int tables,
            int fields,
            int unresolvedDatatypes,
            int unresolvedCharacterLengths,
            Path outputFile) {
    }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle imported;
        private final CellStyle staticText;
        private final CellStyle caution;

        private Styles(XSSFWorkbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            Font importedFont = workbook.createFont();
            importedFont.setColor(IndexedColors.DARK_GREEN.getIndex());
            imported = workbook.createCellStyle();
            imported.setFont(importedFont);
            imported.setVerticalAlignment(VerticalAlignment.TOP);
            imported.setWrapText(true);

            Font staticFont = workbook.createFont();
            staticFont.setColor(IndexedColors.GREY_80_PERCENT.getIndex());
            staticText = workbook.createCellStyle();
            staticText.setFont(staticFont);
            staticText.setVerticalAlignment(VerticalAlignment.TOP);
            staticText.setWrapText(true);

            caution = workbook.createCellStyle();
            caution.cloneStyleFrom(imported);
            caution.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            caution.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}
