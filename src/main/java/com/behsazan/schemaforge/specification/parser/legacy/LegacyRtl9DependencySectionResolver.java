package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Severity;

/**
 * Recovers explicit RTL9 dependency rows into field-level FK evidence.
 *
 * <p>The Facility dependency grid has the logical columns
 * {@code ردیف | نام فیلد | فیلد خارجی}.  Corpus qualification established that usable
 * references are written as {@code SCHEMA.TABLE.COLUMN} or, for same-schema targets,
 * {@code TABLE.COLUMN}.  This resolver is deliberately conservative: local fields must
 * resolve exactly, target paths must contain two or three technical identifiers, and no
 * fuzzy or same-name inference is performed.</p>
 *
 * <p>The actual schema/table/column interpretation remains in the canonical adapter because
 * it has the caller-resolved schema name and can distinguish {@code TABLE.COLUMN} from an
 * incomplete {@code SCHEMA.TABLE} row.  Recovered rows are tagged with the synthetic key
 * token {@code FKD}; the original default/reference cell is preserved.</p>
 */
final class LegacyRtl9DependencySectionResolver {
    static final String DEPENDENCY_KEY_TOKEN = "FKD";

    private LegacyRtl9DependencySectionResolver() {
    }

    static List<ColumnDefinition> apply(
            List<ColumnDefinition> sourceColumns,
            List<List<List<String>>> tables,
            List<ExtractionWarning> warnings) {
        if (sourceColumns == null || sourceColumns.isEmpty() || tables == null || tables.isEmpty()) {
            return sourceColumns == null ? List.of() : List.copyOf(sourceColumns);
        }

        Map<String, Integer> columnIndex = new LinkedHashMap<>();
        for (int index = 0; index < sourceColumns.size(); index++) {
            String normalized = normalizeTechnical(sourceColumns.get(index).fieldName());
            if (!normalized.isBlank()) {
                columnIndex.putIfAbsent(normalized, index);
            }
        }

        List<ColumnDefinition> columns = new ArrayList<>(sourceColumns);
        for (LegacyRtl9DependencySectionProbe.DependencyRow row
                : LegacyRtl9DependencySectionProbe.extractRows(tables)) {
            String localRaw = TextNormalizer.cleanCell(row.localFieldRaw());
            String foreignRaw = TextNormalizer.cleanCell(row.foreignFieldRaw());

            if (localRaw.isBlank()) {
                warnings.add(warning(
                        "LEGACY_DEPENDENCY_LOCAL_FIELD_NOT_PRESENT",
                        row,
                        "Dependency row has a foreign reference but no local field; the row was not applied.",
                        foreignRaw));
                continue;
            }

            Integer index = columnIndex.get(normalizeTechnical(localRaw));
            if (index == null) {
                warnings.add(warning(
                        "LEGACY_DEPENDENCY_LOCAL_FIELD_UNRESOLVED",
                        row,
                        "Dependency local field could not be mapped exactly to an extracted technical field; the row was not applied.",
                        localRaw));
                continue;
            }

            if (foreignRaw.isBlank()) {
                warnings.add(warning(
                        "LEGACY_DEPENDENCY_REFERENCE_NOT_PRESENT",
                        row,
                        "Dependency row has a local field but no foreign reference; the row was not applied.",
                        localRaw));
                continue;
            }

            ReferencePath path = normalizeReferencePath(foreignRaw);
            if (path == null) {
                String code = isSingleTechnicalIdentifier(foreignRaw)
                        ? "LEGACY_DEPENDENCY_REFERENCE_UNQUALIFIED"
                        : "LEGACY_DEPENDENCY_REFERENCE_INVALID";
                warnings.add(warning(
                        code,
                        row,
                        "Dependency foreign reference is not an explicit TABLE.COLUMN or SCHEMA.TABLE.COLUMN path; the row was not applied.",
                        foreignRaw));
                continue;
            }

            ColumnDefinition source = columns.get(index);
            columns.set(index, withDependency(source, path.normalized()));
        }
        return List.copyOf(columns);
    }

    private static ExtractionWarning warning(
            String code,
            LegacyRtl9DependencySectionProbe.DependencyRow row,
            String message,
            String raw) {
        return new ExtractionWarning(
                Severity.WARNING,
                code,
                null,
                row.rowIndex() + 1,
                message,
                raw
        );
    }

    private static ReferencePath normalizeReferencePath(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return null;
        }
        String[] parts = cleaned.split("\\s*\\.\\s*", -1);
        if (parts.length != 2 && parts.length != 3) {
            return null;
        }
        List<String> normalized = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = TextNormalizer.normalizeTechnicalName(part);
            if (!TextNormalizer.isTechnicalIdentifier(token)) {
                return null;
            }
            normalized.add(token);
        }
        return new ReferencePath(String.join(".", normalized));
    }

    private static boolean isSingleTechnicalIdentifier(String raw) {
        return TextNormalizer.isTechnicalIdentifier(TextNormalizer.normalizeTechnicalName(raw));
    }

    private static String normalizeTechnical(String raw) {
        String normalized = TextNormalizer.normalizeTechnicalName(raw);
        return TextNormalizer.isTechnicalIdentifier(normalized)
                ? normalized.toUpperCase(Locale.ROOT)
                : "";
    }

    private static ColumnDefinition withDependency(ColumnDefinition source, String reference) {
        List<String> keys = appendDistinct(source.keys(), DEPENDENCY_KEY_TOKEN);
        String supplement = mergeReference(source.referenceOrDefaultRaw(), reference);
        return new ColumnDefinition(
                source.sequence(), source.sourceTableIndex(), source.sourceRowIndex(),
                source.persianTitle(), source.fieldName(), source.fieldNameRaw(),
                source.typeRaw(), source.lengthRaw(), mergeRaw(source.keyRaw(), DEPENDENCY_KEY_TOKEN), source.indexRaw(),
                source.mandatoryRaw(), source.mandatory(), source.db2TypeRaw(), source.db2LengthRaw(),
                supplement, keys, source.indexes(), source.rawCells()
        );
    }

    private static List<String> appendDistinct(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        if (copy.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static String mergeRaw(String raw, String token) {
        if (raw == null || raw.isBlank()) {
            return token;
        }
        if (raw.toUpperCase(Locale.ROOT).contains(token.toUpperCase(Locale.ROOT))) {
            return raw;
        }
        return raw.trim() + " " + token;
    }

    private static String mergeReference(String existing, String reference) {
        String labeled = "reference: " + reference;
        if (existing == null || existing.isBlank()) {
            return labeled;
        }
        if (existing.toUpperCase(Locale.ROOT).contains(reference.toUpperCase(Locale.ROOT))) {
            return existing;
        }
        return labeled + " | " + existing.trim();
    }

    private record ReferencePath(String normalized) {
    }
}
