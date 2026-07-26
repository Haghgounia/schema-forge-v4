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
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.metadata.DataTypeCanonicalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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
import java.util.HashSet;
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
 * Writes the one-sheet document/database comparison workbook used by the
 * historical SchemaForge v3 output corpus.
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

    private static final Pattern SHORT_MARKER =
            Pattern.compile("(?:^|_)([UI]\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);

    private final DataTypeCanonicalizer canonicalizer = new DataTypeCanonicalizer();

    public byte[] write(
            Table documentTable,
            Table databaseTable,
            Map<String, Long> columnUsageCounts,
            DatabasePlatform platform) {

        Objects.requireNonNull(documentTable, "documentTable must not be null");
        Objects.requireNonNull(databaseTable, "databaseTable must not be null");
        Objects.requireNonNull(platform, "platform must not be null");

        Map<String, Long> usageCounts = normalizeUsage(columnUsageCounts);
        Dialect dialect = DialectFactory.create(platform);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(safeSheetName(documentTable.qualifiedName().name().value()));
            Styles styles = new Styles(workbook);
            writeHeader(sheet, styles.header);

            int rowNumber = 1;
            for (ColumnPair pair : pairColumns(documentTable, databaseTable, dialect, platform)) {
                List<String> differences = differences(documentTable, databaseTable, pair, dialect, platform);
                CellStyle rowStyle = rowStyle(pair, differences, styles);
                Row row = sheet.createRow(rowNumber++);
                row.setHeightInPoints(28);

                writeDocument(row, documentTable, pair.document(), usageCounts, dialect, rowStyle);
                writeDatabase(row, databaseTable, pair.database(), dialect, rowStyle);
                setCell(row, 21, diffText(differences), rowStyle);
            }

            configureSheet(sheet, Math.max(1, rowNumber - 1));
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create schema comparison Excel", exception);
        }
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
            DatabasePlatform platform) {

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
        if (!canonicalizer.equivalent(platform.name(), dialect.sqlType(document), dialect.sqlType(database))) {
            result.add("DATA_TYPE");
        }
        if (document.nullable() != database.nullable()) result.add("NULLABLE");
        if (!normalizeDefault(document.defaultValue().expression())
                .equals(normalizeDefault(database.defaultValue().expression()))) result.add("DATA_DEFAULT");
        if (!normalizeText(document.description().value())
                .equals(normalizeText(database.description().value()))) result.add("COMMENTS");
        if (!identityEquivalent(document, database)) result.add("IDENTITY_MODE");
        if (inPrimaryKey(documentTable, document.name()) != inPrimaryKey(databaseTable, database.name())) {
            result.add("PRIMARY_KEY");
        }
        if (!foreignKeyDefinitions(documentTable, document.name())
                .equals(foreignKeyDefinitions(databaseTable, database.name()))) result.add("FOREIGN_KEY");
        if (isUnique(documentTable, document.name()) != isUnique(databaseTable, database.name())) {
            result.add("UNIQUE");
        }
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
            DatabasePlatform platform) {

        Map<String, Column> databaseByName = databaseTable.columns().stream()
                .collect(Collectors.toMap(
                        column -> column.name().normalized(),
                        column -> column,
                        (first, second) -> first,
                        LinkedHashMap::new));

        List<ColumnPair> result = new ArrayList<>();
        Set<String> matchedDatabase = new HashSet<>();
        List<Column> unmatchedDocument = new ArrayList<>();

        documentTable.columns().stream().sorted(byPosition()).forEach(column -> {
            Column exact = databaseByName.get(column.name().normalized());
            if (exact == null) unmatchedDocument.add(column);
            else {
                result.add(new ColumnPair(column, exact, false));
                matchedDatabase.add(exact.name().normalized());
            }
        });

        List<Column> unmatchedDatabase = databaseTable.columns().stream()
                .filter(column -> !matchedDatabase.contains(column.name().normalized()))
                .sorted(byPosition())
                .collect(Collectors.toCollection(ArrayList::new));

        for (Column document : unmatchedDocument) {
            Column candidate = bestRenameCandidate(document, unmatchedDatabase, dialect, platform);
            if (candidate == null) result.add(new ColumnPair(document, null, false));
            else {
                result.add(new ColumnPair(document, candidate, true));
                unmatchedDatabase.remove(candidate);
            }
        }
        unmatchedDatabase.forEach(column -> result.add(new ColumnPair(null, column, false)));
        return result;
    }

    private Column bestRenameCandidate(
            Column document,
            List<Column> candidates,
            Dialect dialect,
            DatabasePlatform platform) {

        Column best = null;
        double bestScore = 0.0;
        for (Column candidate : candidates) {
            if (!canonicalizer.equivalent(platform.name(),
                    dialect.sqlType(document), dialect.sqlType(candidate))) continue;

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
                    int position = key.columns().indexOf(column);
                    String referencedColumn = position >= 0 && position < key.referencedColumns().size()
                            ? key.referencedColumns().get(position).normalized() : "";
                    String referencedSchema = key.referencedTable().schemaName()
                            .map(Identifier::normalized)
                            .orElseGet(() -> table.qualifiedName().schemaName()
                                    .map(Identifier::normalized).orElse(""));
                    String qualifiedTable = referencedSchema.isBlank()
                            ? key.referencedTable().name().normalized()
                            : referencedSchema + "." + key.referencedTable().name().normalized();
                    return qualifiedTable + "." + referencedColumn;
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }


    private static boolean isUnique(Table table, Identifier column) {
        if (inPrimaryKey(table, column)) return true;
        if (table.uniqueKeys().stream().anyMatch(key -> key.columns().contains(column))) return true;
        return table.indexes().stream()
                .filter(index -> index.type() == IndexType.UNIQUE)
                .anyMatch(index -> index.columns().stream()
                        .filter(item -> !item.expressionBased())
                        .map(IndexColumn::column)
                        .anyMatch(column::equals));
    }

    private static Set<String> uniqueDefinitions(Table table, Identifier column) {
        Set<String> result = new TreeSet<>();
        if (inPrimaryKey(table, column)) result.add("PK");
        table.uniqueKeys().stream().filter(key -> key.columns().contains(column))
                .map(key -> key.columns().stream().map(Identifier::normalized).collect(Collectors.joining(",")))
                .forEach(result::add);
        table.indexes().stream().filter(index -> index.type() == IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(SchemaCompareExcelWriter::indexDefinition)
                .forEach(result::add);
        return result;
    }

    private static Set<String> normalIndexDefinitions(Table table, Identifier column) {
        return table.indexes().stream().filter(index -> index.type() != IndexType.UNIQUE)
                .filter(index -> index.columns().stream().filter(item -> !item.expressionBased())
                        .map(IndexColumn::column).anyMatch(column::equals))
                .map(SchemaCompareExcelWriter::indexDefinition)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String indexDefinition(Index index) {
        return index.columns().stream()
                .map(item -> item.expressionBased()
                        ? normalizeExpression(item.expression()) + " " + item.direction().name()
                        : item.column().normalized() + " " + item.direction().name())
                .collect(Collectors.joining(","));
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

        String source = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        Matcher matcher = Pattern.compile(
                        "^(.*?)\\bIN\\s*\\(([^()]*)\\)(.*)$",
                        Pattern.CASE_INSENSITIVE)
                .matcher(source);

        if (!matcher.matches()) return normalizeExpression(source);

        List<String> values = new ArrayList<>();
        for (String item : matcher.group(2).split(",")) {
            String token = normalizeExpression(item);
            if (!token.isEmpty()) values.add(token);
        }
        values.sort(String::compareTo);

        return normalizeExpression(matcher.group(1))
                + "IN("
                + String.join(",", values)
                + ")"
                + normalizeExpression(matcher.group(3));
    }

    private static boolean identityEquivalent(Column document, Column database) {
        if (document.identity() == database.identity()) return true;
        String documentDefault = normalizeDefault(document.defaultValue().expression());
        String databaseDefault = normalizeDefault(database.defaultValue().expression());
        return document.identity() && !database.identity()
                && !documentDefault.isBlank()
                && documentDefault.equals(databaseDefault);
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
            changed = filledStyle(workbook, IndexedColors.ORANGE);
            missingInDatabase = filledStyle(workbook, IndexedColors.BRIGHT_GREEN);
            extraInDatabase = filledStyle(workbook, IndexedColors.RED);
            renameCandidate = filledStyle(workbook, IndexedColors.ORANGE);
            positionChanged = filledStyle(workbook, IndexedColors.GREY_25_PERCENT);
        }

        private static CellStyle headerStyle(XSSFWorkbook workbook) {
            CellStyle style = filledStyle(workbook, IndexedColors.GREY_40_PERCENT);
            style.setAlignment(HorizontalAlignment.CENTER);
            return style;
        }

        private static CellStyle plainStyle(XSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            return style;
        }

        private static CellStyle filledStyle(XSSFWorkbook workbook, IndexedColors color) {
            CellStyle style = plainStyle(workbook);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }
    }
}
