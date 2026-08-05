package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FieldSupplementParser {
    private static final Pattern PERSIAN_DEFAULT = Pattern.compile(
            "(?iu)(?:مقدار\\s*)?پیش\\s*فرض\\s*[:=]?\\s*(.+)$"
    );
    private static final Pattern ENGLISH_DEFAULT = Pattern.compile(
            "(?iu)default(?:\\s+value)?\\s*[:=]?\\s*(.+)$"
    );
    private static final Pattern REFERENCE_WITH_LABEL = Pattern.compile(
            "(?iu)(?:reference(?:s)?|table|مرجع|جدول)\\s*[:=]?\\s*([A-Za-z][A-Za-z0-9_$#.]*)"
    );

    private FieldSupplementParser() {
    }

    static Supplement parse(String raw) {
        String value = TextNormalizer.compactForMatching(raw);
        if (value.isBlank()) {
            return new Supplement("", "", "");
        }

        String defaultValue = matchGroup(PERSIAN_DEFAULT, value);
        if (defaultValue.isBlank()) {
            defaultValue = matchGroup(ENGLISH_DEFAULT, value);
        }

        String referenceTable = matchGroup(REFERENCE_WITH_LABEL, value);
        if (referenceTable.isBlank() && defaultValue.isBlank() && TextNormalizer.isTechnicalIdentifier(value)) {
            referenceTable = value;
        }

        return new Supplement(referenceTable, defaultValue, value);
    }

    private static String matchGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? TextNormalizer.cleanCell(matcher.group(1)) : "";
    }

    record Supplement(String referenceTable, String defaultValue, String description) {
    }
}
