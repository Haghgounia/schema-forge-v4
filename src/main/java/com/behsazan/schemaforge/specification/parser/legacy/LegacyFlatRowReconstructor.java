package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservatively reconstructs legacy Word rows whose vertically aligned values were flattened
 * into whitespace-separated text before the normal merged-row splitter could see paragraph
 * boundaries.
 *
 * <p>A split is accepted only when field names and logical data types have the same cardinality
 * and every structural cell can be mapped without guessing. Descriptive attribute text may be
 * omitted when it has lost its original boundaries; structural key/index/default evidence is
 * never duplicated or assigned heuristically.</p>
 */
final class LegacyFlatRowReconstructor {
    private static final int MAX_FIELDS_PER_ROW = 16;
    private static final Set<String> BOOLEAN_VALUES = Set.of(
            "YES", "NO", "Y", "N", "TRUE", "FALSE", "1", "0", "بله", "خیر", "ندارد", "دارد");

    private LegacyFlatRowReconstructor() {
    }

    static List<List<String>> split(List<String> sourceCells, ColumnLayoutResolver.Layout layout) {
        List<String> flattened = flatten(sourceCells);
        if (sourceCells == null || sourceCells.size() < 4
                || layout.kind() == ColumnLayoutResolver.Kind.TECHNICAL_5) {
            return List.of(flattened);
        }

        String rawFieldCell = cell(sourceCells, 1);
        // This reconstructor is invoked only after the paragraph-aware splitter has returned
        // a single row. Some DOCX files preserve paragraph separators in the field-name cell
        // while losing one-to-one alignment in the remaining cells. Do not reject those rows
        // here: flatten the cell and let the strict field/type/length cardinality checks below
        // decide whether a second-chance split is deterministic.
        List<String> fields = whitespaceTokens(rawFieldCell);
        if (fields.size() < 2 || fields.size() > MAX_FIELDS_PER_ROW
                || fields.stream().anyMatch(value -> !TextNormalizer.isTechnicalIdentifier(value))) {
            return List.of(flattened);
        }

        List<String> types = whitespaceTokens(cell(sourceCells, 2));
        if (types.size() != fields.size()
                || types.stream().anyMatch(value -> !ColumnLayoutResolver.looksLikeDataTypeValue(value))) {
            return List.of(flattened);
        }

        List<String> lengths = alignedLengths(cell(sourceCells, 3), fields.size());
        if (lengths == null) {
            return List.of(flattened);
        }

        List<List<String>> rows = emptyRows(fields.size(), sourceCells.size());
        for (int index = 0; index < fields.size(); index++) {
            rows.get(index).set(1, fields.get(index));
            rows.get(index).set(2, types.get(index));
            rows.get(index).set(3, lengths.get(index));
        }

        for (int cellIndex = 0; cellIndex < sourceCells.size(); cellIndex++) {
            if (cellIndex == 1 || cellIndex == 2 || cellIndex == 3) {
                continue;
            }
            List<String> mapped = alignedCell(cell(sourceCells, cellIndex), fields.size(), cellIndex);
            if (mapped == null) {
                return List.of(flattened);
            }
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                rows.get(fieldIndex).set(cellIndex, mapped.get(fieldIndex));
            }
        }

        return rows.stream().map(List::copyOf).toList();
    }

    private static List<String> alignedCell(String raw, int fieldCount, int cellIndex) {
        List<String> structured = structuredValues(raw);
        if (structured != null) {
            if (structured.isEmpty()) {
                return blanks(fieldCount);
            }
            if (structured.size() == fieldCount) {
                return structured;
            }
            if (cellIndex == 0) {
                return blanks(fieldCount);
            }
            // Recovery6: a DOCX cell can retain a paragraph wrapper while the values inside
            // that single paragraph were already flattened (for example "✓✓ ✓").  Treat a
            // single structured value as unstructured input and let the strict cell-specific
            // cardinality rules below decide whether it can be split.  Multiple mismatched
            // paragraphs remain ambiguous and are rejected.
            if (structured.size() != 1) {
                return null;
            }
            raw = structured.get(0);
        }

        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return blanks(fieldCount);
        }

        // Persian/descriptive attribute text is not structural. If Word has already destroyed
        // its boundaries, keep the recovered columns but leave the title absent rather than
        // copying one combined description to several fields.
        if (cellIndex == 0) {
            return blanks(fieldCount);
        }

        if (cellIndex == 4 || cellIndex == 5) {
            List<String> tokens = TextNormalizer.splitTokens(cleaned);
            if (tokens.size() != fieldCount) {
                return null;
            }
            boolean valid = cellIndex == 4
                    ? tokens.stream().allMatch(LegacyFlatRowReconstructor::isKeyOrIndexToken)
                    : tokens.stream().allMatch(LegacyDataTypeNormalizer::isIndexLikeToken);
            return valid ? tokens : null;
        }

        if (cellIndex == 6) {
            List<String> tokens = whitespaceTokens(cleaned);
            if (tokens.size() == fieldCount && tokens.stream().allMatch(LegacyFlatRowReconstructor::isBooleanLike)) {
                return tokens;
            }
            // Recovery4: Word frequently flattens one checkmark per logical field into forms
            // such as "√ √" or "✓✓ ✓". Count glyphs only when the cell contains nothing
            // except checkmarks/whitespace, so no mandatory value is guessed.
            String compact = cleaned.replaceAll("\\s+", "");
            if (compact.matches("[✓√]+") && compact.codePointCount(0, compact.length()) == fieldCount) {
                return java.util.Collections.nCopies(fieldCount, "Y");
            }
            return null;
        }

        if (cellIndex == 7) {
            List<String> tokens = whitespaceTokens(cleaned);
            if (tokens.size() != fieldCount
                    || tokens.stream().anyMatch(value -> !ColumnLayoutResolver.looksLikeDataTypeValue(value))) {
                return null;
            }
            return tokens;
        }

        if (cellIndex == 8) {
            return alignedLengths(cleaned, fieldCount);
        }

        // Reference/default and extension columns are semantically significant. Without explicit
        // paragraph boundaries there is no safe way to decide which logical field owns the text.
        return null;
    }

    /** Returns null when a structured cell exists but cannot be mapped one-to-one. */
    private static List<String> structuredValues(String raw) {
        if (!TextNormalizer.hasStructuredCellParagraphs(raw)) {
            return null;
        }
        List<String> values = TextNormalizer.splitCellParagraphs(raw).stream()
                .map(TextNormalizer::cleanCell)
                .filter(value -> !value.isBlank())
                .toList();
        return values.isEmpty() ? List.of() : values;
    }

    private static List<String> alignedLengths(String raw, int fieldCount) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return blanks(fieldCount);
        }
        List<String> tokens = whitespaceTokens(cleaned);
        if (tokens.size() != fieldCount) {
            return null;
        }
        List<String> normalized = new ArrayList<>(fieldCount);
        for (String token : tokens) {
            LengthValueParser.ParsedLength parsed = LengthValueParser.parse(token);
            if (parsed.ambiguous() || parsed.normalized().isBlank()) {
                return null;
            }
            normalized.add(parsed.normalized());
        }
        return List.copyOf(normalized);
    }


    private static boolean isKeyOrIndexToken(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        return value.matches("^(?:PK|FK|PFK|UK|UQ)[A-Z0-9_:-]*$")
                || LegacyDataTypeNormalizer.isIndexLikeToken(value);
    }

    private static boolean isBooleanLike(String raw) {
        return BOOLEAN_VALUES.contains(TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT));
    }

    private static List<String> whitespaceTokens(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return List.of();
        }
        return List.of(cleaned.split("\\s+"));
    }

    private static List<List<String>> emptyRows(int rowCount, int columnCount) {
        List<List<String>> rows = new ArrayList<>(rowCount);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            List<String> row = new ArrayList<>(columnCount);
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                row.add("");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> blanks(int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add("");
        }
        return List.copyOf(values);
    }

    private static List<String> flatten(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        return cells.stream().map(TextNormalizer::cleanCell).toList();
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }
}
