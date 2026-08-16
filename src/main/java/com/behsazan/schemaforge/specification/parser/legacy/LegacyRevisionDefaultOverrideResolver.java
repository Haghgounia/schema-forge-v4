package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Severity;

/**
 * Applies conservative revision-history overrides to defaults extracted from legacy Word grids.
 *
 * <p>Some old specifications keep a stale default in the field-definition grid while a later
 * change-log row explicitly says that the default was removed. This resolver only removes an
 * already-extracted default when the history contains an explicit removal statement for the
 * same technical field. It never invents a default from prose.</p>
 */
final class LegacyRevisionDefaultOverrideResolver {
    private static final String IDENTIFIER = "[A-Za-z][A-Za-z0-9_$#]*";
    private static final String IDENTIFIER_LIST = IDENTIFIER
            + "(?:\\s*(?:و|,|،)\\s*" + IDENTIFIER + ")*";

    private static final Pattern REMOVE_DEFAULT = Pattern.compile(
            "(?iu)حذف\\s+(?:مقدار\\s*)?پیش\\s*فرض"
                    + "(?:\\s+(?!برای(?:\\s|$))[^\\s]+){0,3}"
                    + "\\s+برای\\s+فیلد(?:ها|های)?\\s+(" + IDENTIFIER_LIST + ")"
    );

    private static final Pattern SET_DEFAULT = Pattern.compile(
            "(?iu)(?:تعریف|افزودن|اضافه\\s+کردن)\\s+(?:مقدار\\s*)?پیش\\s*فرض"
                    + "(?:\\s+(?!برای(?:\\s|$))[^\\s]+){0,3}"
                    + "\\s+برای\\s+فیلد(?:ها|های)?\\s+(" + IDENTIFIER_LIST + ")"
    );

    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile(IDENTIFIER);

    private LegacyRevisionDefaultOverrideResolver() {
    }

    static List<ColumnDefinition> apply(
            List<ColumnDefinition> columns,
            String rawMainText,
            List<ExtractionWarning> warnings) {
        if (columns == null || columns.isEmpty() || rawMainText == null || rawMainText.isBlank()) {
            return columns == null ? List.of() : List.copyOf(columns);
        }

        String history = TextNormalizer.cleanBlock(rawMainText);
        if (history.isBlank()) {
            return List.copyOf(columns);
        }

        Map<String, Action> latestActions = new LinkedHashMap<>();
        collectActions(history, SET_DEFAULT, ActionType.SET, latestActions);
        collectActions(history, REMOVE_DEFAULT, ActionType.REMOVE, latestActions);
        if (latestActions.isEmpty()) {
            return List.copyOf(columns);
        }

        List<ColumnDefinition> result = new ArrayList<>(columns.size());
        for (ColumnDefinition column : columns) {
            Action action = findActionForColumn(column.fieldName(), latestActions);
            if (action == null || action.type() != ActionType.REMOVE) {
                result.add(column);
                continue;
            }

            FieldSupplementParser.Supplement supplement = FieldSupplementParser.parse(column.referenceOrDefaultRaw());
            if (supplement.defaultValue().isBlank()) {
                result.add(column);
                continue;
            }

            String retainedReferenceOrDescription = removeExplicitDefault(column.referenceOrDefaultRaw());
            result.add(copyWithReferenceOrDefault(column, retainedReferenceOrDescription));
            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "LEGACY_DEFAULT_REMOVED_BY_REVISION_HISTORY",
                    column.fieldName(),
                    column.sourceRowIndex(),
                    "A stale field-grid default was removed because a later revision-history entry explicitly removes the default.",
                    action.sourceIdentifier() + " -> " + column.fieldName()
            ));
        }
        return List.copyOf(result);
    }

    private static void collectActions(
            String history,
            Pattern pattern,
            ActionType type,
            Map<String, Action> latestActions) {
        Matcher matcher = pattern.matcher(history);
        while (matcher.find()) {
            Matcher identifiers = IDENTIFIER_TOKEN.matcher(matcher.group(1));
            while (identifiers.find()) {
                String identifier = identifiers.group();
                String key = identifier.toUpperCase(Locale.ROOT);
                Action previous = latestActions.get(key);
                if (previous == null || matcher.start() > previous.position()) {
                    latestActions.put(key, new Action(type, matcher.start(), identifier));
                }
            }
        }
    }

    private static Action findActionForColumn(String columnName, Map<String, Action> actions) {
        if (columnName == null || columnName.isBlank()) {
            return null;
        }
        String normalized = columnName.toUpperCase(Locale.ROOT);
        Action exact = actions.get(normalized);
        if (exact != null) {
            return exact;
        }

        // Legacy revision rows occasionally contain a one-character typo in a technical name
        // (for example ReuestAmnt instead of RequestAmnt). Accept a one-edit match only when it
        // is unique and both names are long enough to avoid accidental matches.
        if (normalized.length() < 6) {
            return null;
        }
        Action candidate = null;
        for (Map.Entry<String, Action> entry : actions.entrySet()) {
            String actionName = entry.getKey();
            if (actionName.length() < 6 || !editDistanceAtMostOne(normalized, actionName)) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = entry.getValue();
        }
        return candidate;
    }

    private static boolean editDistanceAtMostOne(String left, String right) {
        int lengthDelta = Math.abs(left.length() - right.length());
        if (lengthDelta > 1) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }

        if (left.length() == right.length()) {
            int differences = 0;
            for (int i = 0; i < left.length(); i++) {
                if (left.charAt(i) != right.charAt(i) && ++differences > 1) {
                    return false;
                }
            }
            return true;
        }

        String shorter = left.length() < right.length() ? left : right;
        String longer = left.length() < right.length() ? right : left;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < shorter.length() && j < longer.length()) {
            if (shorter.charAt(i) == longer.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (skipped) {
                return false;
            }
            skipped = true;
            j++;
        }
        return true;
    }

    private static String removeExplicitDefault(String raw) {
        String value = TextNormalizer.cleanCell(raw);
        if (value.isBlank()) {
            return "";
        }
        String withoutPersian = value.replaceFirst(
                "(?iu)(?:مقدار\\s*)?پیش\\s*فرض\\s*[:=]?\\s*.*$", "").trim();
        if (!withoutPersian.equals(value)) {
            return withoutPersian;
        }
        return value.replaceFirst(
                "(?iu)default(?:\\s+value)?\\s*[:=]?\\s*.*$", "").trim();
    }

    private static ColumnDefinition copyWithReferenceOrDefault(ColumnDefinition column, String value) {
        return new ColumnDefinition(
                column.sequence(),
                column.sourceTableIndex(),
                column.sourceRowIndex(),
                column.persianTitle(),
                column.fieldName(),
                column.fieldNameRaw(),
                column.typeRaw(),
                column.lengthRaw(),
                column.keyRaw(),
                column.indexRaw(),
                column.mandatoryRaw(),
                column.mandatory(),
                column.db2TypeRaw(),
                column.db2LengthRaw(),
                value,
                column.keys(),
                column.indexes(),
                column.rawCells()
        );
    }

    private enum ActionType {
        SET,
        REMOVE
    }

    private record Action(ActionType type, int position, String sourceIdentifier) {
    }
}
