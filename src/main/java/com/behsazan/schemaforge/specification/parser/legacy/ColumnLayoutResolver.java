package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the physical column order of a Word table-definition grid. */
final class ColumnLayoutResolver {
    private static final Pattern TYPE_LIKE = Pattern.compile("[A-Za-z][A-Za-z0-9_(),. /-]{0,49}");
    private static final String SQL_TYPE_NAMES =
            "BIGINT|SMALLINT|INTEGER|INT|DEC|DECIMAL|NUMERIC|NUMBER|FLOAT|REAL|DOUBLE(?:\\s+PRECISION)?|"
                    + "CHAR|CHARACTER|VARCHAR|VARCHAR2|CHARACTER\\s+VARYING|DATE|TIME|TIMESTAMP|BOOLEAN|BIT|"
                    + "BINARY|VARBINARY|CLOB|BLOB|TEXT|XML";
    private static final Pattern SQL_TYPE = Pattern.compile(
            "(?i)^\\s*(" + SQL_TYPE_NAMES + ")(?:\\s*\\(([^)]+)\\))?\\s*$"
    );
    private static final Pattern MALFORMED_SQL_TYPE_WITH_LENGTH = Pattern.compile(
            "(?i)^\\s*(" + SQL_TYPE_NAMES + ")\\s*\\(\\s*"
                    + "([0-9]+(?:\\s*[,،.:]\\s*[0-9]+)?)\\s*[()]*\\s*$"
    );
    private static final Pattern REVERSED_SQL_TYPE_WITH_LENGTH = Pattern.compile(
            "(?i)^\\s*\\(\\s*([0-9]+(?:\\s*[,،.:]\\s*[0-9]+)?)\\s*\\)\\s*("
                    + SQL_TYPE_NAMES + ")\\s*$"
    );
    private static final Pattern SHORT_TYPE = Pattern.compile(
            "(?i)^(?:N|C|V|I|D|S|T|B|L|F|DT|TD|VC|SI|TI|TIN|BI|SMALL|DC|DE|DEC|FLT|IM|IMG|BLB)$"
    );
    private static final Pattern INDEX_TOKEN = Pattern.compile(
            "(?i)^(?:IX|X|I)[A-Z0-9_-]+(?:[.,][A-Z0-9_-]+)*$"
    );
    private static final Set<String> NON_FIELD_NAMES = Set.of(
            "NAME", "FIELD", "FIELDNAME", "COLUMN", "COLUMNNAME", "TYPE", "LENGTH", "KEY", "INDEX",
            "INTEGER", "INT", "SMALLINT", "BIGINT", "DECIMAL", "NUMERIC", "NUMBER", "VARCHAR",
            "VARCHAR2", "CHAR", "CHARACTER", "TIMESTAMP", "DATE", "TIME", "BOOLEAN", "BLOB", "CLOB"
    );

    enum Kind {
        STANDARD_10,
        DUAL_TYPE_10,
        EXTENDED_12,
        LEGACY_13,
        SHIFTED_STANDARD,
        FLEXIBLE_STANDARD,
        REVERSED_SQL_7,
        TECHNICAL_5
    }

    record Layout(Kind kind) {
        boolean isDefinitionRow(List<String> cells) {
            if (kind == Kind.TECHNICAL_5) {
                return isFiveColumnDefinitionRow(cells);
            }
            if (kind == Kind.REVERSED_SQL_7) {
                return isReversedSqlSevenDefinitionRow(cells);
            }
            return isShiftedStandardDefinitionRow(cells) || isAnyStandardDefinitionRow(cells);
        }

        ResolvedColumn resolve(List<String> cells) {
            if (kind == Kind.TECHNICAL_5) {
                return resolveFiveColumn(cells);
            }
            if (kind == Kind.REVERSED_SQL_7) {
                return resolveReversedSqlSevenColumn(cells);
            }
            if (isShiftedStandardDefinitionRow(cells)) {
                return resolveShiftedStandardColumn(cells);
            }
            if (cells.size() == 10) {
                return isDualTypeTenRow(cells)
                        ? resolveDualTypeTenColumn(cells)
                        : resolveStandardTenColumn(cells);
            }
            return resolveFlexibleStandard(cells);
        }
    }

    record ResolvedColumn(
            String attributeName,
            String fieldName,
            String type,
            String length,
            String key,
            String index,
            String mandatory,
            String db2Type,
            String db2Length,
            String referenceOrDefault
    ) {
    }

    private ColumnLayoutResolver() {
    }

    static Layout resolve(List<List<String>> table) {
        int fiveColumnRows = 0;
        int tenColumnRows = 0;
        int dualTypeTenRows = 0;
        int twelveColumnRows = 0;
        int thirteenColumnRows = 0;
        int shiftedRows = 0;
        int reversedSqlSevenRows = 0;
        int flexibleRows = 0;
        boolean fiveColumnHeader = false;

        for (List<String> row : table) {
            if (isFiveColumnHeader(row)) {
                fiveColumnHeader = true;
            }
            if (isFiveColumnDefinitionRow(row)) {
                fiveColumnRows++;
                continue;
            }
            if (isReversedSqlSevenDefinitionRow(row)) {
                reversedSqlSevenRows++;
                continue;
            }
            if (isShiftedStandardDefinitionRow(row)) {
                shiftedRows++;
                continue;
            }
            if (!isAnyStandardDefinitionRow(row)) {
                continue;
            }
            switch (row.size()) {
                case 10 -> {
                    tenColumnRows++;
                    if (isDualTypeTenRow(row)) {
                        dualTypeTenRows++;
                    }
                }
                case 12 -> twelveColumnRows++;
                case 13 -> thirteenColumnRows++;
                default -> flexibleRows++;
            }
        }

        if (fiveColumnRows >= 2 || (fiveColumnHeader && fiveColumnRows >= 1)) {
            return new Layout(Kind.TECHNICAL_5);
        }
        if (reversedSqlSevenRows >= 2) {
            return new Layout(Kind.REVERSED_SQL_7);
        }
        if (shiftedRows > 0 && shiftedRows >= Math.max(tenColumnRows, flexibleRows)) {
            return new Layout(Kind.SHIFTED_STANDARD);
        }
        if (thirteenColumnRows > 0 && thirteenColumnRows >= flexibleRows) {
            return new Layout(Kind.LEGACY_13);
        }
        if (twelveColumnRows > 0 && twelveColumnRows >= flexibleRows) {
            return new Layout(Kind.EXTENDED_12);
        }
        if (dualTypeTenRows > 0 && dualTypeTenRows * 2 >= Math.max(1, tenColumnRows)) {
            return new Layout(Kind.DUAL_TYPE_10);
        }
        if (tenColumnRows > 0 && flexibleRows == 0) {
            return new Layout(Kind.STANDARD_10);
        }
        return new Layout(Kind.FLEXIBLE_STANDARD);
    }

    static boolean looksLikeDataTypeValue(String raw) {
        String value = normalizeLegacyTypeAlias(raw);
        if (value.isBlank()) {
            return false;
        }
        return SHORT_TYPE.matcher(value).matches() || parseSqlType(value) != null;
    }

    private static boolean isFiveColumnDefinitionRow(List<String> cells) {
        if (cells.size() < 5) {
            return false;
        }
        String fieldName = TextNormalizer.normalizeTechnicalName(cell(cells, 0));
        if (!isUsableFieldName(fieldName)
                && !isExplicitNameField(fieldName, cell(cells, 1), "")) {
            return false;
        }
        return parseSqlType(cell(cells, 1)) != null
                && isBooleanLikeOrBlank(cell(cells, 2))
                && isBooleanLikeOrBlank(cell(cells, 3));
    }

    private static boolean isFiveColumnHeader(List<String> cells) {
        if (cells.size() < 4) {
            return false;
        }
        String first = TextNormalizer.compactForMatching(cell(cells, 0));
        String second = TextNormalizer.compactForMatching(cell(cells, 1));
        String third = TextNormalizer.compactForMatching(cell(cells, 2));
        String fourth = TextNormalizer.compactForMatching(cell(cells, 3));
        return (first.contains("نام فیلد") || first.contains("نام فيلد"))
                && second.contains("نوع")
                && (third.contains("کلید اصلی") || third.contains("كليد اصلي"))
                && (fourth.contains("کلید خارجی") || fourth.contains("كليد خارجي"));
    }

    /**
     * Newer right-to-left templates sometimes store the visible columns in reverse XML
     * order: default, key, mandatory, length, SQL type, field name, Persian title.
     */
    private static boolean isReversedSqlSevenDefinitionRow(List<String> cells) {
        if (cells.size() != 7) {
            return false;
        }
        String fieldName = TextNormalizer.normalizeTechnicalName(cell(cells, 5));
        return isUsableFieldName(fieldName)
                && parseSqlType(cell(cells, 4)) != null
                && (cell(cells, 3).isBlank() || looksLikeLength(cell(cells, 3)));
    }

    private static ResolvedColumn resolveReversedSqlSevenColumn(List<String> cells) {
        SqlTypeParts sqlType = parseSqlType(cell(cells, 4));
        String explicitLength = LengthValueParser.parse(cell(cells, 3)).normalized();
        String length = explicitLength.isBlank() && sqlType != null
                ? sqlType.length()
                : explicitLength;
        KeyAndIndex keyAndIndex = splitKeyAndIndex(cell(cells, 1), "");
        return normalizedColumn(
                cell(cells, 6),
                cell(cells, 5),
                sqlType == null ? cell(cells, 4) : sqlType.baseType(),
                length,
                keyAndIndex.key(),
                keyAndIndex.index(),
                cell(cells, 2),
                "",
                "",
                cell(cells, 0)
        );
    }

    private static boolean isShiftedStandardDefinitionRow(List<String> cells) {
        if (cells.size() < 8) {
            return false;
        }
        String fieldName = TextNormalizer.normalizeTechnicalName(cell(cells, 1));
        return (isUsableFieldName(fieldName)
                || isExplicitNameField(fieldName, cell(cells, 3), cell(cells, 4)))
                && cell(cells, 2).isBlank()
                && looksLikeDataTypeValue(cell(cells, 3))
                && looksLikeLength(cell(cells, 4));
    }

    private static boolean isAnyStandardDefinitionRow(List<String> cells) {
        if (cells.size() < 6) {
            return false;
        }
        String fieldName = TextNormalizer.normalizeTechnicalName(cell(cells, 1));
        String type = cell(cells, 2);
        if (!isUsableFieldName(fieldName)
                && !isExplicitNameField(fieldName, type, cell(cells, 3))) {
            return false;
        }
        if (!type.isBlank() && !TYPE_LIKE.matcher(type).matches()) {
            return false;
        }
        return !type.isBlank()
                || !cell(cells, 3).isBlank()
                || !cell(cells, 4).isBlank()
                || findDb2TypeIndex(cells, effectiveEnd(cells)) >= 0;
    }

    private static boolean isDualTypeTenRow(List<String> cells) {
        if (cells.size() != 10 || !looksLikeDataTypeValue(cell(cells, 8))) {
            return false;
        }
        String firstTypeSlot = cell(cells, 7);
        return firstTypeSlot.isBlank()
                || looksLikeDataTypeValue(firstTypeSlot)
                || isBooleanLikeOrBlank(firstTypeSlot)
                || isDecorativeMarker(firstTypeSlot);
    }

    private static ResolvedColumn resolveFiveColumn(List<String> cells) {
        SqlTypeParts type = parseSqlType(cell(cells, 1));
        String primaryRaw = cell(cells, 2);
        String foreignRaw = cell(cells, 3);
        List<String> keys = new ArrayList<>(2);
        if (isTrue(primaryRaw)) {
            keys.add("PK");
        }
        if (isTrue(foreignRaw)) {
            keys.add("FK");
        }
        return normalizedColumn(
                "", cell(cells, 0),
                type == null ? cell(cells, 1) : type.baseType(),
                type == null ? "" : type.length(),
                String.join(" ", keys), "", "", "", "", cell(cells, 4)
        );
    }

    private static ResolvedColumn resolveStandardTenColumn(List<String> cells) {
        KeyAndIndex keyAndIndex = splitKeyAndIndex(cell(cells, 4), cell(cells, 5));
        String mandatory = cell(cells, 6);
        String db2Type = cell(cells, 7);
        String db2Length = cell(cells, 8);
        if (isDecorativeMarker(db2Type) && db2Length.isBlank()) {
            if (mandatory.isBlank()) {
                mandatory = db2Type;
            }
            db2Type = "";
        }
        return normalizedColumn(
                cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3),
                keyAndIndex.key(), keyAndIndex.index(), mandatory,
                db2Type, db2Length, cell(cells, 9)
        );
    }

    private static ResolvedColumn resolveDualTypeTenColumn(List<String> cells) {
        KeyAndIndex keyAndIndex = splitKeyAndIndex(cell(cells, 4), cell(cells, 5));
        String mandatory = cell(cells, 6);
        if (mandatory.isBlank() && isDecorativeMarker(cell(cells, 7))) {
            mandatory = cell(cells, 7);
        }
        return normalizedColumn(
                cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3),
                keyAndIndex.key(), keyAndIndex.index(), mandatory,
                cell(cells, 8), "", cell(cells, 9)
        );
    }

    private static ResolvedColumn resolveShiftedStandardColumn(List<String> cells) {
        KeyAndIndex keyAndIndex = splitKeyAndIndex(cell(cells, 5), cell(cells, 6));
        String db2Type = looksLikeDataTypeValue(cell(cells, 8)) ? cell(cells, 8) : "";
        String db2Length = db2Type.isBlank() ? "" : cell(cells, 9);
        int referenceStart = db2Type.isBlank() ? 8 : 10;
        return normalizedColumn(
                cell(cells, 0), cell(cells, 1), cell(cells, 3), cell(cells, 4),
                keyAndIndex.key(), keyAndIndex.index(), cell(cells, 7),
                db2Type, db2Length, joinReferenceCells(cells, referenceStart, cells.size(), 7)
        );
    }

    private static ResolvedColumn resolveFlexibleStandard(List<String> cells) {
        int end = effectiveEnd(cells);
        int db2TypeIndex = findDb2TypeIndex(cells, end);
        String db2Type = "";
        String db2Length = "";
        int afterDb2 = db2TypeIndex >= 0 ? db2TypeIndex + 1 : -1;

        if (db2TypeIndex >= 0) {
            String rawType = cell(cells, db2TypeIndex);
            SqlTypeParts parts = parseSqlType(rawType);
            if (parts != null) {
                db2Type = parts.baseType();
                db2Length = parts.length();
            } else {
                db2Type = rawType;
            }
            if (db2Length.isBlank() && afterDb2 < end && looksLikeLength(cell(cells, afterDb2))
                    && !cell(cells, afterDb2).isBlank()) {
                db2Length = cell(cells, afterDb2);
                afterDb2++;
            }
        }

        int mandatoryIndex = findMandatoryIndex(cells, 5, db2TypeIndex >= 0 ? db2TypeIndex : end);
        String mandatory = mandatoryIndex >= 0 ? cell(cells, mandatoryIndex) : "";

        List<String> indexes = new ArrayList<>();
        for (int index = 5; index < (db2TypeIndex >= 0 ? db2TypeIndex : end); index++) {
            if (index == mandatoryIndex) {
                continue;
            }
            String value = cell(cells, index);
            if (looksLikeIndex(value) && !indexes.contains(value)) {
                indexes.add(value);
            }
        }
        KeyAndIndex keyAndIndex = splitKeyAndIndex(cell(cells, 4), String.join(" ", indexes));

        int descriptionStart = afterDb2 >= 0 ? afterDb2 : 5;
        String reference = joinReferenceCells(cells, descriptionStart, end, mandatoryIndex);

        return normalizedColumn(
                cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3),
                keyAndIndex.key(), keyAndIndex.index(), mandatory,
                db2Type, db2Length, reference
        );
    }

    private static int effectiveEnd(List<String> cells) {
        if (cells.size() < 18) {
            return cells.size();
        }
        for (int index = 10; index + 2 < cells.size(); index++) {
            String possibleField = TextNormalizer.normalizeTechnicalName(cell(cells, index));
            if (isUsableFieldName(possibleField)
                    && looksLikeDataTypeValue(cell(cells, index + 1))
                    && looksLikeLength(cell(cells, index + 2))) {
                return index;
            }
        }
        return cells.size();
    }

    private static int findDb2TypeIndex(List<String> cells, int end) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 5; index < end; index++) {
            String value = cell(cells, index);
            if (!looksLikeDataTypeValue(value)) {
                continue;
            }
            int score = 0;
            SqlTypeParts sql = parseSqlType(value);
            if (sql != null) {
                score += 5;
                if (!sql.length().isBlank()) {
                    score += 5;
                }
            }
            if (index + 1 < end && !cell(cells, index + 1).isBlank()
                    && looksLikeLength(cell(cells, index + 1))) {
                score += 8;
            }
            if (!isBooleanLike(value)) {
                score += 3;
            }
            if (index >= 8) {
                score += 2;
            }
            score += index;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static int findMandatoryIndex(List<String> cells, int start, int end) {
        for (int index = end - 1; index >= start; index--) {
            String value = cell(cells, index);
            if (containsCheckboxSymbol(value)) {
                return index;
            }
        }
        for (int index = end - 1; index >= start; index--) {
            String value = cell(cells, index);
            if (isBooleanLike(value)) {
                return index;
            }
        }
        return -1;
    }

    private static String joinReferenceCells(List<String> cells, int start, int end, int mandatoryIndex) {
        List<String> values = new ArrayList<>();
        for (int index = Math.max(5, start); index < end; index++) {
            if (index == mandatoryIndex) {
                continue;
            }
            String value = cell(cells, index);
            if (value.isBlank() || containsCheckboxSymbol(value) || looksLikeIndex(value)) {
                continue;
            }
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        return String.join(" ", values);
    }

    private static ResolvedColumn normalizedColumn(String attributeName,
                                                   String fieldName,
                                                   String type,
                                                   String length,
                                                   String key,
                                                   String index,
                                                   String mandatory,
                                                   String db2Type,
                                                   String db2Length,
                                                   String referenceOrDefault) {
        String normalizedDb2Type = normalizeLegacyTypeAlias(db2Type);
        String normalizedDb2Length = TextNormalizer.cleanCell(db2Length);
        String normalizedReference = TextNormalizer.cleanCell(referenceOrDefault);

        SqlTypeParts db2Parts = parseSqlType(normalizedDb2Type);
        if (db2Parts != null) {
            normalizedDb2Type = db2Parts.baseType();
            if (normalizedDb2Length.isBlank()) {
                normalizedDb2Length = db2Parts.length();
            }
        }

        if (!normalizedDb2Length.isBlank() && !looksLikeLength(normalizedDb2Length)) {
            if (normalizedDb2Type.isBlank() && looksLikeDataTypeValue(normalizedDb2Length)) {
                normalizedDb2Type = normalizedDb2Length;
            } else if (!looksLikeDataTypeValue(normalizedDb2Length)) {
                normalizedReference = joinValues(normalizedDb2Length, normalizedReference);
            }
            normalizedDb2Length = "";
        }

        return new ResolvedColumn(
                TextNormalizer.cleanCell(attributeName), TextNormalizer.cleanCell(fieldName),
                TextNormalizer.cleanCell(type), TextNormalizer.cleanCell(length),
                TextNormalizer.cleanCell(key), TextNormalizer.cleanCell(index),
                TextNormalizer.cleanCell(mandatory), normalizedDb2Type, normalizedDb2Length,
                normalizedReference
        );
    }

    private static KeyAndIndex splitKeyAndIndex(String keyCell, String otherIndexCells) {
        List<String> keyTokens = new ArrayList<>();
        List<String> indexTokens = new ArrayList<>();
        for (String token : TextNormalizer.splitTokens(keyCell)) {
            if (token.startsWith("PK") || token.startsWith("FK")) {
                keyTokens.add(token);
            } else if (!indexTokens.contains(token)) {
                indexTokens.add(token);
            }
        }
        for (String token : TextNormalizer.splitTokens(otherIndexCells)) {
            if (!indexTokens.contains(token)) {
                indexTokens.add(token);
            }
        }
        return new KeyAndIndex(String.join(" ", keyTokens), String.join(" ", indexTokens));
    }

    private static SqlTypeParts parseSqlType(String raw) {
        String cleaned = normalizeLegacyTypeAlias(raw);
        Matcher matcher = SQL_TYPE.matcher(cleaned);
        if (matcher.matches()) {
            String base = matcher.group(1).replaceAll("\\s+", " ");
            String length = matcher.group(2) == null ? ""
                    : LengthValueParser.parse(matcher.group(2)).normalized();
            return new SqlTypeParts(base, length);
        }

        // Some RTL legacy rows store the length before the SQL type, for example
        // (17) Varchar. This is still an unambiguous SQL declaration.
        Matcher reversed = REVERSED_SQL_TYPE_WITH_LENGTH.matcher(cleaned);
        if (reversed.matches()) {
            String length = LengthValueParser.parse(reversed.group(1)).normalized();
            String base = reversed.group(2).replaceAll("\\s+", " ");
            return new SqlTypeParts(base, length);
        }

        // Some legacy specifications contain an unmatched parenthesis, for example
        // VARCHAR(70( or VARCHAR(20. Recover only the unambiguous base type and
        // numeric length; do not accept arbitrary malformed text as a data type.
        Matcher malformed = MALFORMED_SQL_TYPE_WITH_LENGTH.matcher(cleaned);
        if (!malformed.matches()) {
            return null;
        }
        String base = malformed.group(1).replaceAll("\\s+", " ");
        String length = LengthValueParser.parse(malformed.group(2)).normalized();
        return new SqlTypeParts(base, length);
    }

    private static String normalizeLegacyTypeAlias(String raw) {
        return LegacyDataTypeNormalizer.normalize(raw);
    }

    private static boolean isUsableFieldName(String value) {
        return TextNormalizer.isTechnicalFieldName(value)
                && !NON_FIELD_NAMES.contains(value.toUpperCase(Locale.ROOT));
    }

    private static boolean isExplicitNameField(String fieldName, String type, String length) {
        if (!"NAME".equalsIgnoreCase(TextNormalizer.cleanCell(fieldName))) {
            return false;
        }
        if (!looksLikeDataTypeValue(type)) {
            return false;
        }
        String cleanedLength = TextNormalizer.cleanCell(length);
        return cleanedLength.isBlank() || looksLikeLength(cleanedLength);
    }


    private static boolean isBooleanLikeOrBlank(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        return value.isBlank() || isBooleanLike(value);
    }

    private static boolean isBooleanLike(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        return Set.of("YES", "NO", "Y", "N", "TRUE", "FALSE", "1", "0", "بله", "خیر", "ندارد", "دارد")
                .contains(value);
    }

    private static boolean containsCheckboxSymbol(String raw) {
        String value = TextNormalizer.cleanCell(raw);
        return value.contains("✓") || value.contains("✔") || value.contains("☑") || value.contains("√")
                || value.contains("☐") || value.contains("□") || value.contains("þ") || value.contains("\ue10b");
    }

    private static boolean isDecorativeMarker(String raw) {
        String value = TextNormalizer.cleanCell(raw);
        if (value.isBlank()) {
            return false;
        }
        return containsCheckboxSymbol(value)
                || value.matches("^[()\\[\\]{}<>]+$")
                || value.matches("^[^\\p{L}\\p{N}]+$");
    }

    private static boolean isTrue(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        return Set.of("YES", "Y", "TRUE", "1", "بله").contains(value);
    }

    private static boolean looksLikeLength(String raw) {
        String value = TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(raw));
        return value.isBlank() || value.matches("\\(?\\s*[0-9]+(?:\\s*[.,]\\s*[0-9]+)?\\s*\\)?");
    }

    private static boolean looksLikeIndex(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        for (String token : TextNormalizer.splitTokens(value)) {
            if (INDEX_TOKEN.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String joinValues(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first.equals(second) ? first : first + " " + second;
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? TextNormalizer.cleanCell(cells.get(index)) : "";
    }

    private record SqlTypeParts(String baseType, String length) {
    }

    private record KeyAndIndex(String key, String index) {
    }
}
