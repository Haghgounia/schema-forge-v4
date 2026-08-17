package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Severity;

/**
 * Final evidence-only fallback for a physical Word row that survived table extraction as one
 * synthetic column even though its retained raw cells can be split one-to-one.
 *
 * <p>No semantic value is invented here. The existing {@link LegacyFlatRowReconstructor} must
 * first prove that every structural cell can be assigned deterministically; otherwise the source
 * column is returned unchanged.</p>
 */
final class LegacyPostExtractionMergedColumnRecovery {
    private static final Set<String> TRUE_VALUES = Set.of(
            "YES", "Y", "TRUE", "1", "بله", "دارد");
    private static final Set<String> FALSE_VALUES = Set.of(
            "NO", "N", "FALSE", "0", "خیر", "ندارد");

    private LegacyPostExtractionMergedColumnRecovery() {
    }

    static List<ColumnDefinition> recover(
            List<ColumnDefinition> columns,
            List<ExtractionWarning> warnings) {
        List<ColumnDefinition> recovered = new ArrayList<>();
        for (ColumnDefinition column : columns) {
            List<String> rawCells = column.rawCells();
            if (rawCells == null || rawCells.size() < 10) {
                recovered.add(column);
                continue;
            }

            ColumnLayoutResolver.Layout localLayout = ColumnLayoutResolver.resolve(List.of(rawCells));
            List<List<String>> logicalRows = LegacyFlatRowReconstructor.split(rawCells, localLayout);
            if (logicalRows.size() <= 1) {
                recovered.add(column);
                continue;
            }

            List<ColumnDefinition> splitColumns = new ArrayList<>(logicalRows.size());
            boolean safe = true;
            for (List<String> cells : logicalRows) {
                ColumnLayoutResolver.ResolvedColumn resolved = localLayout.resolve(cells);
                String fieldNameRaw = resolved.fieldName();
                String fieldName = TextNormalizer.normalizeTechnicalName(fieldNameRaw);
                String typeRaw = resolved.type();
                if (!TextNormalizer.isTechnicalIdentifier(fieldName)
                        || !ColumnLayoutResolver.looksLikeDataTypeValue(typeRaw)) {
                    safe = false;
                    break;
                }

                String mandatoryRaw = resolved.mandatory();
                splitColumns.add(new ColumnDefinition(
                        0,
                        column.sourceTableIndex(),
                        column.sourceRowIndex(),
                        resolved.attributeName(),
                        fieldName,
                        fieldNameRaw,
                        typeRaw,
                        resolved.length(),
                        resolved.key(),
                        resolved.index(),
                        mandatoryRaw,
                        parseMandatory(mandatoryRaw),
                        resolved.db2Type(),
                        resolved.db2Length(),
                        resolved.referenceOrDefault(),
                        TextNormalizer.splitTokens(resolved.key()),
                        TextNormalizer.splitTokens(resolved.index()),
                        List.copyOf(cleanCells(cells))
                ));
            }

            if (!safe || splitColumns.size() != logicalRows.size()) {
                recovered.add(column);
                continue;
            }

            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "POST_EXTRACT_FLAT_MERGED_DEFINITION_ROW_SPLIT",
                    null,
                    column.sourceRowIndex(),
                    "A previously collapsed column definition was split from retained raw-cell evidence into "
                            + splitColumns.size() + " logical columns after table selection; all structural cells mapped one-to-one.",
                    column.fieldNameRaw()
            ));
            recovered.addAll(splitColumns);
        }
        return List.copyOf(recovered);
    }

    private static Boolean parseMandatory(String raw) {
        String value = TextNormalizer.cleanCell(raw);
        if (value.isBlank()) {
            return null;
        }
        if (value.matches(".*[✓✔☑√].*")) {
            return Boolean.TRUE;
        }
        if (value.matches(".*[☐□].*")) {
            return Boolean.FALSE;
        }
        String token = value
                .replaceAll("^[\\s()\\[\\]{}<>._:：,،;؛-]+", "")
                .replaceAll("[\\s()\\[\\]{}<>._:：,،;؛-]+$", "")
                .toUpperCase(Locale.ROOT);
        if (TRUE_VALUES.contains(token)) {
            return Boolean.TRUE;
        }
        if (FALSE_VALUES.contains(token)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static List<String> cleanCells(List<String> cells) {
        return cells.stream().map(TextNormalizer::cleanCell).toList();
    }
}
