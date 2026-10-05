package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only probe for the legacy RTL9 {@code وابستگی ها} grid.
 *
 * <p>The Facility documents expose dependencies in a separate Word table whose
 * logical header is normally {@code ردیف | نام فیلد | فیلد خارجی}.  The exact
 * syntax stored in {@code فیلد خارجی} has not yet been qualified across the
 * private corpus, so this component deliberately preserves raw source evidence
 * and performs no foreign-key inference or canonical mapping.</p>
 */
final class LegacyRtl9DependencySectionProbe {
    private LegacyRtl9DependencySectionProbe() {
    }

    static List<DependencyRow> extractRows(List<List<List<String>>> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        List<DependencyRow> rows = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<List<String>> table = tables.get(tableIndex);
            Header header = findHeader(table);
            if (header == null) {
                continue;
            }

            for (int rowIndex = header.rowIndex() + 1; rowIndex < table.size(); rowIndex++) {
                List<String> row = table.get(rowIndex);
                String localFieldRaw = cell(row, header.localFieldIndex()).trim();
                String foreignFieldRaw = cell(row, header.foreignFieldIndex()).trim();
                if (localFieldRaw.isBlank() && foreignFieldRaw.isBlank()) {
                    continue;
                }
                if (isRepeatedHeader(localFieldRaw, foreignFieldRaw)) {
                    continue;
                }
                rows.add(new DependencyRow(
                        tableIndex,
                        rowIndex,
                        localFieldRaw,
                        foreignFieldRaw,
                        List.copyOf(row)
                ));
            }
        }
        return List.copyOf(rows);
    }

    static int countSections(List<List<List<String>>> tables) {
        if (tables == null || tables.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (List<List<String>> table : tables) {
            if (findHeader(table) != null) {
                count++;
            }
        }
        return count;
    }

    private static Header findHeader(List<List<String>> table) {
        if (table == null || table.isEmpty()) {
            return null;
        }
        for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            int local = -1;
            int foreign = -1;
            for (int cellIndex = 0; cellIndex < row.size(); cellIndex++) {
                String normalized = normalizeHeader(cell(row, cellIndex));
                if (isLocalFieldHeader(normalized)) {
                    local = cellIndex;
                }
                if (isForeignFieldHeader(normalized)) {
                    foreign = cellIndex;
                }
            }
            if (local >= 0 && foreign >= 0 && local != foreign) {
                return new Header(rowIndex, local, foreign);
            }
        }
        return null;
    }

    private static boolean isRepeatedHeader(String localFieldRaw, String foreignFieldRaw) {
        return isLocalFieldHeader(normalizeHeader(localFieldRaw))
                && isForeignFieldHeader(normalizeHeader(foreignFieldRaw));
    }

    private static boolean isLocalFieldHeader(String value) {
        return value.equals("نامفیلد") || value.equals("فیلد") || value.equals("فیلدمحلی");
    }

    private static boolean isForeignFieldHeader(String value) {
        return value.equals("فیلدخارجی")
                || value.equals("فیلدمرجع")
                || value.equals("فیلدمقصد")
                || value.equals("فیلدرفرنس");
    }

    private static String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u064A', '\u06CC')
                .replace('\u0643', '\u06A9')
                .replace('\u200C', ' ')
                .replace('\u200D', ' ')
                .replace('\u200E', ' ')
                .replace('\u200F', ' ')
                .replaceAll("[\\s:：_\\-–—/\\\\|]+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String cell(List<String> row, int index) {
        return row != null && index >= 0 && index < row.size() && row.get(index) != null
                ? row.get(index)
                : "";
    }

    record DependencyRow(
            int tableIndex,
            int rowIndex,
            String localFieldRaw,
            String foreignFieldRaw,
            List<String> rawCells
    ) {
        DependencyRow {
            rawCells = rawCells == null ? List.of() : List.copyOf(rawCells);
        }

        boolean completePair() {
            return localFieldRaw != null && !localFieldRaw.isBlank()
                    && foreignFieldRaw != null && !foreignFieldRaw.isBlank();
        }

        String shape() {
            boolean local = localFieldRaw != null && !localFieldRaw.isBlank();
            boolean foreign = foreignFieldRaw != null && !foreignFieldRaw.isBlank();
            if (local && foreign) {
                return "COMPLETE_PAIR";
            }
            if (local) {
                return "LOCAL_ONLY";
            }
            return "FOREIGN_ONLY";
        }
    }

    private record Header(int rowIndex, int localFieldIndex, int foreignFieldIndex) {
    }
}
