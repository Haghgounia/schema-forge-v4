package com.behsazan.schemaforge.reporting;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.metadata.NumericTypeEquivalenceService;
import com.behsazan.schemaforge.metadata.validation.PhysicalComparisonRow;
import com.behsazan.schemaforge.metadata.validation.PhysicalComparisonStatus;
import com.behsazan.schemaforge.metadata.validation.PhysicalMetadataComparator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the historical 22-column document/database comparison sheet and
 * database-neutral object comparison sheets for keys and indexes.
 */
@Component
public final class SchemaCompareExcelWriter {

    public static final String[] HEADERS = {
            "COLUMN_USAGE", "COLUMN_ID", "COLUMN_NAME", "COMMENTS", "DATA_TYPE",
            "PRIMARY/FOREIGN KEY", "UNIQUE", "INDEX", "REQUIRED", "DEFAULT", "RANGE",
            "COLUMN_ID", "COLUMN_NAME", "DATA_TYPE", "NULLABLE", "DATA_DEFAULT",
            "COMMENTS", "INDEX", "UNIQUE_INDEX", "FOREIGN KEY", "CHECK CONSTRAINT", "DIFF"
    };

    private static final int[] WIDTHS = {
            14, 11, 28, 40, 24, 30, 16, 30, 13, 28, 38,
            11, 28, 24, 13, 28, 40, 32, 34, 42, 46, 36
    };

    private static final String[] OBJECT_HEADERS = {
            "OBJECT_TYPE", "DOCUMENT_NAME", "DOCUMENT_DEFINITION",
            "DATABASE_NAME", "DATABASE_DEFINITION", "STATUS", "DIFF"
    };

    private static final int[] OBJECT_WIDTHS = {22, 34, 72, 34, 72, 16, 42};

    private static final String[] TABLE_METADATA_HEADERS = {
            "TECHNICAL_NAME", "PERSIAN_NAME", "DOCUMENT_DESCRIPTION",
            "DATABASE_COMMENT", "COMMENT_STATUS"
    };

    private static final int[] TABLE_METADATA_WIDTHS = {34, 34, 80, 80, 18};

    private static final String[] TABLE_PHYSICAL_HEADERS = {
            "OBJECT", "PROPERTY", "EXPECTED", "ACTUAL", "STATUS", "NOTE"
    };

    private static final int[] TABLE_PHYSICAL_WIDTHS = {36, 30, 34, 34, 20, 80};

    private static final String[] INDEX_PHYSICAL_HEADERS = {
            "SCOPE", "OBJECT", "PROPERTY", "EXPECTED", "ACTUAL", "STATUS", "NOTE"
    };

    private static final int[] INDEX_PHYSICAL_WIDTHS = {20, 40, 34, 34, 34, 20, 80};

    private static final String[] COLUMN_PHYSICAL_HEADERS = {
            "COLUMN", "PROPERTY", "EXPECTED", "ACTUAL", "STATUS", "NOTE"
    };

    private static final int[] COLUMN_PHYSICAL_WIDTHS = {40, 30, 34, 34, 20, 80};

    private static final Pattern SHORT_MARKER =
            Pattern.compile("(?:^|_)([UI]\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);

    private final NumericTypeEquivalenceService typeEquivalence = new NumericTypeEquivalenceService();

    public byte[] write(
            Table documentTable,
            Table databaseTable,
            Map<String, Long> columnUsageCounts,
            DatabasePlatform platform) {
        Objects.requireNonNull(platform, "platform must not be null");
        return write(documentTable, databaseTable, columnUsageCounts,
                platform.name(), DialectFactory.create(platform));
    }

    /**
     * Database-neutral comparison entry point. The writer receives only the
     * canonical document/database tables and the generic dialect contract.
     * Oracle, PostgreSQL and future database adapters remain outside the
     * reporting implementation.
     */
    public byte[] write(
            Table documentTable,
            Table databaseTable,
            Map<String, Long> columnUsageCounts,
            String databaseType,
            Dialect dialect) {

        Objects.requireNonNull(documentTable, "documentTable must not be null");
        Objects.requireNonNull(databaseTable, "databaseTable must not be null");
        Objects.requireNonNull(databaseType, "databaseType must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        Map<String, Long> usageCounts = normalizeUsage(columnUsageCounts);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(safeSheetName(documentTable.qualifiedName().name().value()));
            Styles styles = new Styles(workbook);
            writeHeader(sheet, styles.header);

            int rowNumber = 1;
            for (ColumnPair pair : pairColumns(documentTable, databaseTable, dialect, databaseType)) {
                List<String> differences = differences(documentTable, databaseTable, pair, dialect, databaseType);
                CellStyle rowStyle = rowStyle(pair, differences, styles);
                Row row = sheet.createRow(rowNumber++);
                row.setHeightInPoints(28);

                writeDocument(row, documentTable, pair.document(), usageCounts, dialect, rowStyle);
                writeDatabase(row, databaseTable, pair.database(), dialect, rowStyle);
                setCell(row, 21, diffText(differences), rowStyle);
            }

            configureSheet(sheet, Math.max(1, rowNumber - 1));
            writeTableMetadataSheet(workbook, documentTable, databaseTable, styles);
            if (supportsPhysicalComparison(databaseType)) {
                writeTablePhysicalComparisonSheet(
                        workbook, documentTable, databaseTable, databaseType, styles);
                writeIndexPhysicalComparisonSheet(
                        workbook, documentTable, databaseTable, databaseType, styles);
                writeColumnPhysicalComparisonSheet(
                        workbook, documentTable, databaseTable, databaseType, styles);
            }
            writePrimaryKeySheet(workbook, documentTable, databaseTable, styles);
            writeObjectComparisonSheet(workbook, "FOREIGN_KEYS_COMPARE",
                    foreignKeySnapshots(documentTable), foreignKeySnapshots(databaseTable), styles);
            writeObjectComparisonSheet(workbook, "INDEXES_COMPARE",
                    indexSnapshots(documentTable, false), indexSnapshots(databaseTable, false), styles);
            writeObjectComparisonSheet(workbook, "UNIQUE_INDEXES_COMPARE",
                    uniqueSnapshots(documentTable), uniqueSnapshots(databaseTable), styles);

            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create schema comparison Excel", exception);
        }
    }

    /**
     * Physical comparison is an explicit vendor contract, not a generic fallback.
     * MySQL metadata can be acquired for logical comparison, but its physical
     * design contract is intentionally deferred until vendor-specific expected
     * properties and comparison rules are modeled and frozen.
     */
    private static boolean supportsPhysicalComparison(String databaseType) {
        String normalized = databaseType.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        return switch (normalized) {
            case "ORACLE", "POSTGRESQL", "DB2ZOS", "SQLSERVER" -> true;
            default -> false;
        };
    }


    private static void writeTableMetadataSheet(
            XSSFWorkbook workbook,
            Table documentTable,
            Table databaseTable,
            Styles styles) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, "TABLE_METADATA"));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < TABLE_METADATA_HEADERS.length; index++) {
            setCell(header, index, TABLE_METADATA_HEADERS[index], styles.header);
        }

        String documentDescription = documentTable.description().value();
        String expectedDatabaseComment = documentTable.persianName().isEmpty()
                ? documentDescription
                : documentTable.persianName().value();
        String databaseComment = databaseTable.description().value();
        boolean commentsEqual = normalizeText(expectedDatabaseComment).equals(normalizeText(databaseComment));

        Row row = sheet.createRow(1);
        row.setHeightInPoints(42);
        CellStyle style = commentsEqual ? styles.normal : styles.changed;
        setCell(row, 0, documentTable.qualifiedName().toString(), style);
        setCell(row, 1, documentTable.persianName().value(), style);
        setCell(row, 2, documentDescription, style);
        setCell(row, 3, databaseComment, style);
        setCell(row, 4, commentsEqual ? "SAME" : "DIFFERENT", style);

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 1, 0, TABLE_METADATA_HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        for (int index = 0; index < TABLE_METADATA_WIDTHS.length; index++) {
            sheet.setColumnWidth(index, TABLE_METADATA_WIDTHS[index] * 256);
        }
    }


    private static void writeTablePhysicalComparisonSheet(
            XSSFWorkbook workbook,
            Table documentTable,
            Table databaseTable,
            String databaseType,
            Styles styles) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, "TABLE_PHYSICAL_COMPARE"));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < TABLE_PHYSICAL_HEADERS.length; index++) {
            setCell(header, index, TABLE_PHYSICAL_HEADERS[index], styles.header);
        }

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareTable(documentTable, databaseTable, databaseType);
        int rowNumber = 1;
        for (PhysicalComparisonRow comparison : rows) {
            Row row = sheet.createRow(rowNumber++);
            row.setHeightInPoints(30);
            CellStyle style = physicalRowStyle(comparison.status(), styles);
            setCell(row, 0, comparison.objectName(), style);
            setCell(row, 1, comparison.property(), style);
            setCell(row, 2, comparison.expectedValue(), style);
            setCell(row, 3, comparison.actualValue(), style);
            setCell(row, 4, comparison.status().name(), style);
            setCell(row, 5, comparison.note(), style);
        }

        sheet.createFreezePane(0, 1);
        int lastRow = Math.max(1, rowNumber - 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, TABLE_PHYSICAL_HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        for (int index = 0; index < TABLE_PHYSICAL_WIDTHS.length; index++) {
            sheet.setColumnWidth(index, TABLE_PHYSICAL_WIDTHS[index] * 256);
        }
    }

    private static void writeIndexPhysicalComparisonSheet(
            XSSFWorkbook workbook,
            Table documentTable,
            Table databaseTable,
            String databaseType,
            Styles styles) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, "INDEX_PHYSICAL_COMPARE"));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < INDEX_PHYSICAL_HEADERS.length; index++) {
            setCell(header, index, INDEX_PHYSICAL_HEADERS[index], styles.header);
        }

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareIndexes(documentTable, databaseTable, databaseType);
        int rowNumber = 1;
        for (PhysicalComparisonRow comparison : rows) {
            Row row = sheet.createRow(rowNumber++);
            row.setHeightInPoints(30);
            CellStyle style = physicalRowStyle(comparison.status(), styles);
            setCell(row, 0, comparison.scope(), style);
            setCell(row, 1, comparison.objectName(), style);
            setCell(row, 2, comparison.property(), style);
            setCell(row, 3, comparison.expectedValue(), style);
            setCell(row, 4, comparison.actualValue(), style);
            setCell(row, 5, comparison.status().name(), style);
            setCell(row, 6, comparison.note(), style);
        }

        sheet.createFreezePane(0, 1);
        int lastRow = Math.max(1, rowNumber - 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, INDEX_PHYSICAL_HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        for (int index = 0; index < INDEX_PHYSICAL_WIDTHS.length; index++) {
            sheet.setColumnWidth(index, INDEX_PHYSICAL_WIDTHS[index] * 256);
        }
    }

    private static void writeColumnPhysicalComparisonSheet(
            XSSFWorkbook workbook,
            Table documentTable,
            Table databaseTable,
            String databaseType,
            Styles styles) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, "COLUMN_PHYSICAL_COMPARE"));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < COLUMN_PHYSICAL_HEADERS.length; index++) {
            setCell(header, index, COLUMN_PHYSICAL_HEADERS[index], styles.header);
        }

        List<PhysicalComparisonRow> rows = new PhysicalMetadataComparator()
                .compareColumns(documentTable, databaseTable, databaseType);
        int rowNumber = 1;
        for (PhysicalComparisonRow comparison : rows) {
            Row row = sheet.createRow(rowNumber++);
            row.setHeightInPoints(30);
            CellStyle style = physicalRowStyle(comparison.status(), styles);
            setCell(row, 0, comparison.objectName(), style);
            setCell(row, 1, comparison.property(), style);
            setCell(row, 2, comparison.expectedValue(), style);
            setCell(row, 3, comparison.actualValue(), style);
            setCell(row, 4, comparison.status().name(), style);
            setCell(row, 5, comparison.note(), style);
        }

        sheet.createFreezePane(0, 1);
        int lastRow = Math.max(1, rowNumber - 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, COLUMN_PHYSICAL_HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        for (int index = 0; index < COLUMN_PHYSICAL_WIDTHS.length; index++) {
            sheet.setColumnWidth(index, COLUMN_PHYSICAL_WIDTHS[index] * 256);
        }
    }

    private static CellStyle physicalRowStyle(PhysicalComparisonStatus status, Styles styles) {
        return switch (status) {
            case MATCH -> styles.normal;
            case MISMATCH -> styles.changed;
            case NOT_SPECIFIED -> styles.positionChanged;
            case NOT_AVAILABLE -> styles.missingInDatabase;
            case REVIEW -> styles.renameCandidate;
        };
    }

    private static void writePrimaryKeySheet(
            XSSFWorkbook workbook,
            Table documentTable,
            Table databaseTable,
            Styles styles) {
        List<ObjectSnapshot> document = documentTable.primaryKey()
                .map(SchemaCompareExcelWriter::primaryKeySnapshot)
                .map(List::of)
                .orElseGet(List::of);
        List<ObjectSnapshot> database = databaseTable.primaryKey()
                .map(SchemaCompareExcelWriter::primaryKeySnapshot)
                .map(List::of)
                .orElseGet(List::of);
        writeObjectComparisonSheet(workbook, "PRIMARY_KEY_COMPARE", document, database, styles);
    }

    private static void writeObjectComparisonSheet(
            XSSFWorkbook workbook,
            String preferredSheetName,
            List<ObjectSnapshot> documentObjects,
            List<ObjectSnapshot> databaseObjects,
            Styles styles) {

        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, preferredSheetName));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(30);
        for (int index = 0; index < OBJECT_HEADERS.length; index++) {
            setCell(header, index, OBJECT_HEADERS[index], styles.header);
        }

        int rowNumber = 1;
        for (ObjectPair pair : pairObjects(documentObjects, databaseObjects)) {
            List<String> differences = objectDifferences(pair);
            String status = objectStatus(pair, differences);
            CellStyle style = objectRowStyle(status, styles);
            Row row = sheet.createRow(rowNumber++);
            row.setHeightInPoints(30);

            ObjectSnapshot document = pair.document();
            ObjectSnapshot database = pair.database();
            String objectType = document != null ? document.objectType()
                    : database == null ? "" : database.objectType();

            setCell(row, 0, objectType, style);
            setCell(row, 1, document == null ? "" : document.name(), style);
            setCell(row, 2, document == null ? "" : document.definition(), style);
            setCell(row, 3, database == null ? "" : database.name(), style);
            setCell(row, 4, database == null ? "" : database.definition(), style);
            setCell(row, 5, status, style);
            setCell(row, 6, diffText(differences), style);
        }

        configureObjectSheet(sheet, rowNumber - 1);
    }

    private static List<ObjectPair> pairObjects(
            List<ObjectSnapshot> documentObjects,
            List<ObjectSnapshot> databaseObjects) {
        List<ObjectSnapshot> remaining = new ArrayList<>(databaseObjects);
        List<ObjectPair> result = new ArrayList<>();

        for (ObjectSnapshot document : documentObjects) {
            ObjectSnapshot exact = remaining.stream()
                    .filter(database -> !document.name().isBlank())
                    .filter(database -> document.name().equalsIgnoreCase(database.name()))
                    .findFirst()
                    .orElse(null);
            if (exact != null) {
                result.add(new ObjectPair(document, exact));
                remaining.remove(exact);
                continue;
            }

            ObjectSnapshot structural = remaining.stream()
                    .filter(database -> document.structuralKey().equals(database.structuralKey()))
                    .findFirst()
                    .orElse(null);
            if (structural != null) {
                result.add(new ObjectPair(document, structural));
                remaining.remove(structural);
            } else {
                result.add(new ObjectPair(document, null));
            }
        }

        remaining.forEach(database -> result.add(new ObjectPair(null, database)));
        return result;
    }

    private static List<String> objectDifferences(ObjectPair pair) {
        if (pair.document() == null || pair.database() == null) return List.of();

        List<String> result = new ArrayList<>();
        if (!pair.document().name().equalsIgnoreCase(pair.database().name())) result.add("NAME");

        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        pair.document().attributes().forEach(attributes::putIfAbsent);
        pair.database().attributes().forEach(attributes::putIfAbsent);
        for (String attribute : attributes.keySet()) {
            String documentValue = pair.document().attributes().getOrDefault(attribute, "");
            String databaseValue = pair.database().attributes().getOrDefault(attribute, "");
            if (!Objects.equals(documentValue, databaseValue)) result.add(attribute);
        }
        return result;
    }

    private static String objectStatus(ObjectPair pair, List<String> differences) {
        if (pair.document() != null && pair.database() == null) return "ADD";
        if (pair.document() == null && pair.database() != null) return "DROP";
        return differences.isEmpty() ? "SAME" : "MODIFY";
    }

    private static CellStyle objectRowStyle(String status, Styles styles) {
        return switch (status) {
            case "ADD" -> styles.missingInDatabase;
            case "DROP" -> styles.extraInDatabase;
            case "MODIFY" -> styles.changed;
            default -> styles.normal;
        };
    }

    private static ObjectSnapshot primaryKeySnapshot(PrimaryKey key) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("COLUMNS", identifiers(key.columns()));
        attributes.put("DEFERRABLE", yesNo(key.deferrable()));
        attributes.put("INITIALLY_DEFERRED", yesNo(key.initiallyDeferred()));
        return snapshot("PRIMARY_KEY", identifierName(key.name()),
                "PRIMARY_KEY", attributes, null);
    }

    private static List<ObjectSnapshot> foreignKeySnapshots(Table table) {
        List<ObjectSnapshot> result = new ArrayList<>();
        for (ForeignKey key : table.foreignKeys()) {
            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            attributes.put("COLUMNS", identifiers(key.columns()));
            attributes.put("REFERENCED_TABLE", qualifiedName(key.referencedTable()));
            attributes.put("REFERENCED_COLUMNS", identifiers(key.referencedColumns()));
            attributes.put("ON_DELETE", key.onDelete().name());
            attributes.put("ON_UPDATE", key.onUpdate().name());
            attributes.put("DEFERRABLE", yesNo(key.deferrable()));
            attributes.put("INITIALLY_DEFERRED", yesNo(key.initiallyDeferred()));
            String extra = "REFERENCE_MODE=" + (key.physicalReference() ? "PHYSICAL" : "LOGICAL");
            result.add(snapshot("FOREIGN_KEY", identifierName(key.name()),
                    "FK:" + attributes.get("COLUMNS"), attributes, extra));
        }
        return result;
    }

    private static List<ObjectSnapshot> indexSnapshots(Table table, boolean uniqueOnly) {
        List<ObjectSnapshot> result = new ArrayList<>();
        Set<String> constraintBackedNames = constraintBackedUniqueIndexNames(table);
        for (Index index : table.indexes()) {
            boolean unique = index.type() == IndexType.UNIQUE;
            if (uniqueOnly != unique) continue;
            if (unique && index.name() != null
                    && constraintBackedNames.contains(index.name().normalized())) continue;

            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            attributes.put("INDEX_TYPE", index.type().name());
            attributes.put("COLUMNS", indexColumns(index));
            attributes.put("INCLUDE_COLUMNS", identifiers(index.includeColumns()));
            attributes.put("PREDICATE", normalizeExpression(index.predicate()));
            String structuralKey = "INDEX:" + attributes.get("COLUMNS");
            result.add(snapshot(unique ? "UNIQUE_INDEX" : "INDEX",
                    identifierName(index.name()), structuralKey, attributes, null));
        }
        return result;
    }

    private static List<ObjectSnapshot> uniqueSnapshots(Table table) {
        List<ObjectSnapshot> result = new ArrayList<>();
        for (UniqueKey key : table.uniqueKeys()) {
            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            attributes.put("KIND", "UNIQUE_CONSTRAINT");
            attributes.put("COLUMNS", identifiers(key.columns()));
            attributes.put("DEFERRABLE", yesNo(key.deferrable()));
            attributes.put("INITIALLY_DEFERRED", yesNo(key.initiallyDeferred()));
            result.add(snapshot("UNIQUE_OBJECT", identifierName(key.name()),
                    "UNIQUE:" + attributes.get("COLUMNS"), attributes, null));
        }
        result.addAll(indexSnapshots(table, true).stream()
                .map(index -> new ObjectSnapshot(
                        "UNIQUE_OBJECT",
                        index.name(),
                        index.definition(),
                        "UNIQUE:" + index.attributes().getOrDefault("COLUMNS", ""),
                        withKind(index.attributes(), "UNIQUE_INDEX")))
                .toList());
        return result;
    }

    private static Map<String, String> withKind(Map<String, String> source, String kind) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("KIND", kind);
        source.forEach(result::putIfAbsent);
        return result;
    }

    private static Set<String> constraintBackedUniqueIndexNames(Table table) {
        Set<String> result = new TreeSet<>();
        table.primaryKey().map(PrimaryKey::name)
                .filter(Objects::nonNull).map(Identifier::normalized).ifPresent(result::add);
        table.uniqueKeys().stream().map(UniqueKey::name)
                .filter(Objects::nonNull).map(Identifier::normalized).forEach(result::add);
        return result;
    }

    private static ObjectSnapshot snapshot(
            String objectType,
            String name,
            String structuralKey,
            LinkedHashMap<String, String> attributes,
            String extraDefinition) {
        String definition = attributes.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
        if (extraDefinition != null && !extraDefinition.isBlank()) {
            definition = definition.isBlank() ? extraDefinition : definition + "; " + extraDefinition;
        }
        return new ObjectSnapshot(
                objectType,
                name == null ? "" : name,
                definition,
                structuralKey == null ? "" : structuralKey,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes)));
    }

    private static String identifierName(Identifier identifier) {
        return identifier == null ? "" : identifier.value();
    }

    private static String identifiers(List<Identifier> identifiers) {
        return identifiers == null ? "" : identifiers.stream()
                .map(Identifier::normalized)
                .collect(Collectors.joining(","));
    }

    private static String indexColumns(Index index) {
        return index.columns().stream()
                .map(column -> column.expressionBased()
                        ? normalizeExpression(column.expression()) + " " + column.direction().name()
                        : column.column().normalized() + " " + column.direction().name())
                .collect(Collectors.joining(","));
    }

    private static String qualifiedName(com.behsazan.schemaforge.domain.valueobject.QualifiedName name) {
        String table = name.name().normalized();
        return name.schemaName().map(Identifier::normalized)
                .map(schema -> schema + "." + table)
                .orElse(table);
    }

    private static String yesNo(boolean value) {
        return value ? "Y" : "N";
    }

    private static void configureObjectSheet(Sheet sheet, int dataRows) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, dataRows), 0, OBJECT_HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        for (int index = 0; index < OBJECT_WIDTHS.length; index++) {
            sheet.setColumnWidth(index, OBJECT_WIDTHS[index] * 256);
        }
    }

    private static String uniqueSheetName(XSSFWorkbook workbook, String preferred) {
        String base = safeSheetName(preferred);
        if (workbook.getSheet(base) == null) return base;
        for (int index = 2; index < 1000; index++) {
            String suffix = "_" + index;
            String candidate = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
            if (workbook.getSheet(candidate) == null) return candidate;
        }
        throw new IllegalStateException("Cannot allocate unique sheet name for " + preferred);
    }

    private static Map<String, Long> normalizeUsage(Map<String, Long> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null) result.put(normalizeName(key), value == null ? 0L : value);
        });
        return Map.copyOf(result);
    }

    private static void writeHeader(Sheet sheet, CellStyle style) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(34);
        for (int index = 0; index < HEADERS.length; index++) {
            setCell(row, index, HEADERS[index], style);
        }
    }

    private void writeDocument(
            Row row,
            Table table,
            Column column,
            Map<String, Long> usageCounts,
            Dialect dialect,
            CellStyle style) {

        if (column == null) {
            fill(row, 0, 10, style);
            return;
        }

        setCell(row, 0, usageCounts.getOrDefault(column.name().normalized(), 0L), style);
        setCell(row, 1, column.ordinalPosition(), style);
        setCell(row, 2, column.name().value(), style);
        setCell(row, 3, column.description().value(), style);
        setCell(row, 4, (column.identity() ? "IDENTITY " : "") + dialect.sqlType(column), style);
        setCell(row, 5, documentKey(table, column.name()), style);
        setCell(row, 6, uniqueMarkers(table, column.name()), style);
        setCell(row, 7, indexMarkers(table, column.name()), style);
        setCell(row, 8, column.nullable() ? "FALSE" : "TRUE", style);
        setCell(row, 9, column.defaultValue().expression(), style);
        setCell(row, 10, String.join("; ", checkExpressions(table, column.name())), style);
    }

    private void writeDatabase(
            Row row,
            Table table,
            Column column,
            Dialect dialect,
            CellStyle style) {

        if (column == null) {
            fill(row, 11, 20, style);
            return;
        }

        setCell(row, 11, column.ordinalPosition(), style);
        setCell(row, 12, column.name().value(), style);
        setCell(row, 13, dialect.sqlType(column), style);
        setCell(row, 14, column.nullable() ? "Y" : "N", style);
        setCell(row, 15, column.defaultValue().expression(), style);
        setCell(row, 16, column.description().value(), style);
        setCell(row, 17, joinWithTrailingComma(normalIndexNames(table, column.name())), style);
        setCell(row, 18, joinWithTrailingComma(uniqueIndexNames(table, column.name())), style);
        setCell(row, 19, joinWithTrailingComma(databaseForeignKeys(table, column.name())), style);
        setCell(row, 20, joinWithTrailingComma(databaseChecks(table, column.name())), style);
    }

    private List<String> differences(
            Table documentTable,
            Table databaseTable,
            ColumnPair pair,
            Dialect dialect,
            String databaseType) {

        if (pair.document() != null && pair.database() == null) return List.of("NOT_EXISTS_IN_TABLE");
        if (pair.document() == null && pair.database() != null) return List.of("NOT_EXISTS_IN_DOCUMENT");

        Column document = pair.document();
        Column database = pair.database();
        List<String> result = new ArrayList<>();

        if (pair.renameCandidate()) {
            result.add("COLUMN_NAME");
            result.add("SIMILARITY");
        }
        if (!Objects.equals(document.ordinalPosition(), database.ordinalPosition())) result.add("COLUMN ID");
        if (!typeEquivalence.equivalent(
                databaseType, dialect.sqlType(document), dialect.sqlType(database),
                dialect.numericMappingStrategy())) {
            result.add("DATA_TYPE");
        }
        if (document.nullable() != database.nullable()) result.add("NULLABLE");
        boolean sequenceBackedIdentityEquivalent = sequenceBackedIdentityEquivalent(
                documentTable, document, database, dialect);
        if (!sequenceBackedIdentityEquivalent
                && !normalizeDefault(document.defaultValue().expression())
                .equals(normalizeDefault(database.defaultValue().expression()))) result.add("DATA_DEFAULT");
        if (!normalizeText(document.description().value())
                .equals(normalizeText(database.description().value()))) result.add("COMMENTS");
        if (!identityEquivalent(documentTable, document, database, dialect)) result.add("IDENTITY_MODE");
        if (!primaryKeyDefinitions(documentTable, document.name())
                .equals(primaryKeyDefinitions(databaseTable, database.name()))) result.add("PRIMARY_KEY");
        if (!foreignKeyDefinitions(documentTable, document.name())
                .equals(foreignKeyDefinitions(databaseTable, database.name()))) result.add("FOREIGN_KEY");
        if (!uniqueDefinitions(documentTable, document.name())
                .equals(uniqueDefinitions(databaseTable, database.name()))) result.add("UNIQUE_INDEX");
        if (!normalIndexDefinitions(documentTable, document.name())
                .equals(normalIndexDefinitions(databaseTable, database.name()))) result.add("INDEX");
        if (!checkExpressions(documentTable, document.name())
                .equals(checkExpressions(databaseTable, database.name()))) result.add("CHECK CONSTRAINT");

        return result;
    }

    private List<ColumnPair> pairColumns(
            Table documentTable,
            Table databaseTable,
            Dialect dialect,
            String databaseType) {

        Map<String, Column> databaseByName = databaseTable.columns().stream()
                .collect(Collectors.toMap(
                        column -> column.name().normalized(),
                        column -> column,
                        (first, second) -> first,
                        LinkedHashMap::new));

        Set<String> documentNames = documentTable.columns().stream()
                .map(column -> column.name().normalized())
                .collect(Collectors.toSet());

        List<Column> unmatchedDatabase = databaseTable.columns().stream()
                .filter(column -> !documentNames.contains(column.name().normalized()))
                .sorted(byPosition())
                .collect(Collectors.toCollection(ArrayList::new));

        List<ColumnPair> result = new ArrayList<>();
        documentTable.columns().stream().sorted(byPosition()).forEach(document -> {
            Column exact = databaseByName.get(document.name().normalized());
            if (exact != null) {
                result.add(new ColumnPair(document, exact, false));
                return;
            }

            Column candidate = bestRenameCandidate(document, unmatchedDatabase, dialect, databaseType);
            if (candidate == null) {
                result.add(new ColumnPair(document, null, false));
            } else {
                result.add(new ColumnPair(document, candidate, true));
                unmatchedDatabase.remove(candidate);
            }
        });

        unmatchedDatabase.forEach(column -> result.add(new ColumnPair(null, column, false)));
        return result;
    }

    private Column bestRenameCandidate(
            Column document,
            List<Column> candidates,
            Dialect dialect,
            String databaseType) {

        Column best = null;
        double bestScore = 0.0;
        for (Column candidate : candidates) {
            if (!typeEquivalence.equivalent(
                    databaseType, dialect.sqlType(document), dialect.sqlType(candidate),
                    dialect.numericMappingStrategy())) continue;

            double nameScore = similarity(document.name().normalized(), candidate.name().normalized());
            int documentPosition = document.ordinalPosition() == null ? Integer.MAX_VALUE : document.ordinalPosition();
            int databasePosition = candidate.ordinalPosition() == null ? Integer.MAX_VALUE : candidate.ordinalPosition();
            double positionScore = documentPosition == Integer.MAX_VALUE || databasePosition == Integer.MAX_VALUE
                    ? 0.0 : 1.0 / (1.0 + Math.abs(documentPosition - databasePosition));
            double commentScore = similarity(
                    normalizeText(document.description().value()),
                    normalizeText(candidate.description().value()));
            double score = nameScore * 0.65 + positionScore * 0.20 + commentScore * 0.15;
            if (nameScore >= 0.55 && score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return bestScore >= 0.60 ? best : null;
    }

    private static Comparator<Column> byPosition() {
        return Comparator.comparing(Column::ordinalPosition, Comparator.nullsLast(Integer::compareTo));
    }

    private static String documentKey(Table table, Identifier column) {
        if (inPrimaryKey(table, column)) return "PK";
        return table.foreignKeys().stream()
                .filter(foreignKey -> foreignKey.columns().contains(column))
                .map(foreignKey -> {
                    String tableName = foreignKey.schemaExplicit()
                            ? foreignKey.referencedTable().toString()
                            : foreignKey.referencedTable().name().value();
                    return tableName + "/" + (foreignKey.physicalReference() ? "Y" : "N");
                })
                .collect(Collectors.joining(","));
    }

    private static String uniqueMarkers(Table table, Identifier column) {
        return table.uniqueKeys().stream()
                .filter(key -> key.columns().contains(column))
                .map(UniqueKey::name)
                .map(name -> shortMarker(name, "U"))
                .collect(Collectors.joining(","));
    }

    private static String indexMarkers(Table table, Identifier column) {
        return table.indexes().stream()
                .filter(index -> index.type() != IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(Index::name)
                .map(name -> shortMarker(name, "I"))
                .collect(Collectors.joining(","));
    }

    private static String shortMarker(Identifier identifier, String fallback) {
        if (identifier == null) return fallback;
        Matcher matcher = SHORT_MARKER.matcher(identifier.value());
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : identifier.value();
    }

    private static Set<String> normalIndexNames(Table table, Identifier column) {
        return table.indexes().stream()
                .filter(index -> index.type() != IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(Index::name).filter(Objects::nonNull).map(Identifier::value)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> uniqueIndexNames(Table table, Identifier column) {
        Set<String> result = new TreeSet<>();
        table.primaryKey().filter(key -> key.columns().contains(column)).map(key -> key.name())
                .filter(Objects::nonNull).map(Identifier::value).ifPresent(result::add);
        table.uniqueKeys().stream().filter(key -> key.columns().contains(column)).map(UniqueKey::name)
                .filter(Objects::nonNull).map(Identifier::value).forEach(result::add);
        table.indexes().stream().filter(index -> index.type() == IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(Index::name).filter(Objects::nonNull).map(Identifier::value).forEach(result::add);
        return result;
    }

    private static Set<String> databaseForeignKeys(Table table, Identifier column) {
        return table.foreignKeys().stream().filter(key -> key.columns().contains(column))
                .map(key -> {
                    int position = key.columns().indexOf(column);
                    String referencedColumn = position >= 0 && position < key.referencedColumns().size()
                            ? key.referencedColumns().get(position).value() : "";
                    return key.referencedTable() + "." + referencedColumn;
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> databaseChecks(Table table, Identifier column) {
        String token = column.normalized();
        return table.checkConstraints().stream()
                .filter(check -> containsIdentifier(check.expression(), token))
                .map(check -> (check.name() == null ? "" : check.name().value() + ": ") + check.expression())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> foreignKeyDefinitions(Table table, Identifier column) {
        return table.foreignKeys().stream().filter(key -> key.columns().contains(column))
                .map(key -> {
                    String referencedSchema = key.referencedTable().schemaName()
                            .map(Identifier::normalized)
                            .orElseGet(() -> table.qualifiedName().schemaName()
                                    .map(Identifier::normalized).orElse(""));
                    String qualifiedTable = referencedSchema.isBlank()
                            ? key.referencedTable().name().normalized()
                            : referencedSchema + "." + key.referencedTable().name().normalized();
                    return identifierName(key.name()).toUpperCase(Locale.ROOT)
                            + "|" + identifiers(key.columns())
                            + "|" + qualifiedTable
                            + "|" + identifiers(key.referencedColumns())
                            + "|" + key.onDelete().name()
                            + "|" + key.onUpdate().name()
                            + "|" + key.deferrable()
                            + "|" + key.initiallyDeferred();
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> primaryKeyDefinitions(Table table, Identifier column) {
        return table.primaryKey()
                .filter(key -> key.columns().contains(column))
                .map(key -> Set.of(
                        identifierName(key.name()).toUpperCase(Locale.ROOT)
                                + "|" + identifiers(key.columns())
                                + "|" + key.deferrable()
                                + "|" + key.initiallyDeferred()))
                .orElseGet(Set::of);
    }



    private static Set<String> uniqueDefinitions(Table table, Identifier column) {
        Set<String> result = new TreeSet<>();
        table.uniqueKeys().stream().filter(key -> key.columns().contains(column))
                .map(key -> "CONSTRAINT|"
                        + identifierName(key.name()).toUpperCase(Locale.ROOT)
                        + "|" + identifiers(key.columns())
                        + "|" + key.deferrable()
                        + "|" + key.initiallyDeferred())
                .forEach(result::add);

        Set<String> constraintBacked = constraintBackedUniqueIndexNames(table);
        table.indexes().stream().filter(index -> index.type() == IndexType.UNIQUE)
                .filter(index -> index.name() == null || !constraintBacked.contains(index.name().normalized()))
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(index -> "INDEX|" + indexComparisonSignature(index))
                .forEach(result::add);
        return result;
    }

    private static Set<String> normalIndexDefinitions(Table table, Identifier column) {
        return table.indexes().stream().filter(index -> index.type() != IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(SchemaCompareExcelWriter::indexComparisonSignature)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String indexComparisonSignature(Index index) {
        return identifierName(index.name()).toUpperCase(Locale.ROOT)
                + "|" + index.type().name()
                + "|" + indexColumns(index)
                + "|" + identifiers(index.includeColumns())
                + "|" + normalizeExpression(index.predicate());
    }

    private static Set<String> checkExpressions(Table table, Identifier column) {
        String token = column.normalized();
        return table.checkConstraints().stream().map(CheckConstraint::expression)
                .filter(expression -> containsIdentifier(expression, token))
                .map(SchemaCompareExcelWriter::normalizeCheckExpression)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String normalizeCheckExpression(String value) {
        if (value == null || value.isBlank()) return "";

        String source = stripBalancedOuterParentheses(
                value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));

        Matcher anyArrayMatcher = Pattern.compile(
                        "^(.*?)=\\s*ANY\\s*\\(\\s*ARRAY\\s*\\[(.*)]\\s*\\)(.*)$",
                        Pattern.CASE_INSENSITIVE)
                .matcher(source);

        if (anyArrayMatcher.matches()) {
            return canonicalInExpression(
                    anyArrayMatcher.group(1),
                    anyArrayMatcher.group(2),
                    anyArrayMatcher.group(3));
        }

        Matcher matcher = Pattern.compile(
                        "^(.*?)\\bIN\\s*\\(([^()]*)\\)(.*)$",
                        Pattern.CASE_INSENSITIVE)
                .matcher(source);

        if (!matcher.matches()) return normalizeExpression(source);

        return canonicalInExpression(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static String canonicalInExpression(String prefix, String rawValues, String suffix) {
        List<String> values = new ArrayList<>();
        for (String item : rawValues.split(",")) {
            String token = normalizeCheckValue(item);
            if (!token.isEmpty()) values.add(token);
        }
        values.sort(String::compareTo);

        return normalizeExpression(stripBalancedOuterParentheses(prefix))
                + "IN("
                + String.join(",", values)
                + ")"
                + normalizeExpression(stripBalancedOuterParentheses(suffix));
    }

    private static String normalizeCheckValue(String value) {
        String result = value == null ? "" : value.trim();
        result = stripBalancedOuterParentheses(result);
        result = result.replaceFirst("(?i)::[A-Z0-9_.$\" ]+(?:\\[\\])?$", "");
        result = stripBalancedOuterParentheses(result.trim());
        return normalizeExpression(result);
    }

    private static String stripBalancedOuterParentheses(String value) {
        String result = value == null ? "" : value.trim();
        while (result.length() >= 2 && result.startsWith("(") && result.endsWith(")")
                && enclosesWholeExpression(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean enclosesWholeExpression(String value) {
        int depth = 0;
        boolean inSingleQuote = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\'' && (index == 0 || value.charAt(index - 1) != '\\')) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (inSingleQuote) continue;
            if (current == '(') depth++;
            if (current == ')') depth--;
            if (depth == 0 && index < value.length() - 1) return false;
            if (depth < 0) return false;
        }
        return depth == 0;
    }

    private static boolean identityEquivalent(
            Table documentTable, Column document, Column database, Dialect dialect) {
        if (document.identity() == database.identity()) return true;
        String documentDefault = normalizeDefault(document.defaultValue().expression());
        String databaseDefault = normalizeDefault(database.defaultValue().expression());
        if (document.identity() && !database.identity()
                && !documentDefault.isBlank()
                && documentDefault.equals(databaseDefault)) {
            return true;
        }
        return sequenceBackedIdentityEquivalent(documentTable, document, database, dialect);
    }

    /**
     * Oracle implements SchemaForge logical identity columns with the deterministic
     * SEQ_<TABLE>[ _<COLUMN>] sequence. Treat that exact persisted NEXTVAL default
     * as semantically equivalent to the EA logical IDENTITY marker. Arbitrary
     * sequences are deliberately not accepted.
     */
    private static boolean sequenceBackedIdentityEquivalent(
            Table documentTable, Column document, Column database, Dialect dialect) {
        if (!document.identity() || database.identity() || !dialect.identityUsesNamedSequence()) {
            return false;
        }
        if (document.defaultValue().isPresent()) {
            return false;
        }
        String databaseDefault = normalizeSequenceReference(database.defaultValue().expression());
        if (databaseDefault.isBlank()) {
            return false;
        }
        long identityCount = documentTable.columns().stream().filter(Column::identity).count();
        QualifiedName expectedSequence = dialect.identitySequenceName(
                documentTable.qualifiedName(), document, identityCount > 1);
        String expected = normalizeSequenceReference(expectedSequence + ".NEXTVAL");
        String expectedUnqualified = normalizeSequenceReference(
                expectedSequence.name().value() + ".NEXTVAL");
        return expected.equals(databaseDefault) || expectedUnqualified.equals(databaseDefault);
    }

    private static String normalizeSequenceReference(String value) {
        return normalizeDefault(value).replace("\"", "");
    }

    private static boolean containsIdentifier(String expression, String normalizedColumn) {
        String source = expression == null ? "" : expression.toUpperCase(Locale.ROOT);
        return Pattern.compile("(^|[^A-Z0-9_$#])" + Pattern.quote(normalizedColumn)
                        + "([^A-Z0-9_$#]|$)")
                .matcher(source).find();
    }

    private static boolean inPrimaryKey(Table table, Identifier column) {
        return table.primaryKey().map(key -> key.columns().contains(column)).orElse(false);
    }

    private static CellStyle rowStyle(ColumnPair pair, List<String> differences, Styles styles) {
        if (pair.document() != null && pair.database() == null) return styles.missingInDatabase;
        if (pair.document() == null) return styles.extraInDatabase;
        if (pair.renameCandidate()) return styles.renameCandidate;
        if (differences.size() == 1 && differences.contains("COLUMN ID")) return styles.positionChanged;
        return differences.isEmpty() ? styles.normal : styles.changed;
    }

    private static String diffText(List<String> differences) {
        return differences.isEmpty() ? "" : String.join(",", differences) + ",";
    }

    private static String joinWithTrailingComma(Set<String> values) {
        return values.isEmpty() ? "" : String.join(",", values) + ",";
    }

    private static String normalizeDefault(String value) {
        String normalized = normalizeExpression(value);
        while (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeExpression(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static double similarity(String left, String right) {
        if (left == null || right == null) return 0.0;
        if (left.equals(right)) return 1.0;
        int maximum = Math.max(left.length(), right.length());
        if (maximum == 0) return 1.0;
        return 1.0 - ((double) levenshtein(left, right) / maximum);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static void configureSheet(Sheet sheet, int dataRows) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, dataRows), 0, HEADERS.length - 1));
        sheet.setDisplayGridlines(false);
        sheet.getPrintSetup().setLandscape(true);
        sheet.setAutobreaks(true);
        sheet.setRepeatingRows(CellRangeAddress.valueOf("1:1"));
        for (int index = 0; index < WIDTHS.length; index++) {
            sheet.setColumnWidth(index, WIDTHS[index] * 256);
        }
    }

    private static void fill(Row row, int from, int to, CellStyle style) {
        for (int index = from; index <= to; index++) setCell(row, index, "", style);
    }

    private static void setCell(Row row, int index, Object value, CellStyle style) {
        Cell cell = row.createCell(index);
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else cell.setCellValue(value == null ? "" : String.valueOf(value));
        cell.setCellStyle(style);
    }

    private static String safeSheetName(String value) {
        String candidate = value == null || value.isBlank() ? "TABLE_COMPARE" : value.trim();
        candidate = candidate.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return candidate.substring(0, Math.min(candidate.length(), 31));
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ColumnPair(Column document, Column database, boolean renameCandidate) { }

    private record ObjectSnapshot(
            String objectType,
            String name,
            String definition,
            String structuralKey,
            Map<String, String> attributes) { }

    private record ObjectPair(ObjectSnapshot document, ObjectSnapshot database) { }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle normal;
        private final CellStyle changed;
        private final CellStyle missingInDatabase;
        private final CellStyle extraInDatabase;
        private final CellStyle renameCandidate;
        private final CellStyle positionChanged;

        private Styles(XSSFWorkbook workbook) {
            header = headerStyle(workbook);
            normal = plainStyle(workbook);
            changed = filledStyle(workbook, IndexedColors.LIGHT_ORANGE);
            missingInDatabase = filledStyle(workbook, IndexedColors.BRIGHT_GREEN);
            extraInDatabase = filledStyle(workbook, IndexedColors.RED);
            renameCandidate = filledStyle(workbook, IndexedColors.LIGHT_ORANGE);
            positionChanged = filledStyle(workbook, IndexedColors.GREY_25_PERCENT);
        }

        private static CellStyle headerStyle(XSSFWorkbook workbook) {
            CellStyle style = filledStyle(workbook, IndexedColors.GREY_40_PERCENT);
            style.setWrapText(false);
            style.setAlignment(HorizontalAlignment.CENTER);
            return style;
        }

        private static CellStyle plainStyle(XSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            style.setFillPattern(FillPatternType.NO_FILL);
            applyThinBorders(style);
            return style;
        }

        private static CellStyle filledStyle(XSSFWorkbook workbook, IndexedColors color) {
            CellStyle style = plainStyle(workbook);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }

        private static void applyThinBorders(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
