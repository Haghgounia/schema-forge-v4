package com.behsazan.schemaforge.specification.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts common DOCX datatype defects into canonical datatype expressions.
 *
 * <p>The normalizer is deliberately conservative. It keeps the first
 * recognizable datatype and records every non-trivial correction as a
 * warning.</p>
 */
public final class DataTypeNormalizer {

    private static final Pattern COMPLETE_TYPE = Pattern.compile(
            "(?i)("
                    + "TIMESTAMP"
                    + "(?:\\s+WITH(?:\\s+LOCAL)?\\s+TIME\\s+ZONE)?"
                    + "(?:\\s*\\(\\s*\\d+\\s*\\))?"
                    + "|NUMBER\\s*\\(\\s*\\d+\\s*"
                    + "(?:[,\\.]\\s*\\d+\\s*)?\\)"
                    + "|(?:VAR)?CHAR2?\\s*\\(\\s*\\d+\\s*"
                    + "(?:CHAR|BYTE)?\\s*\\)"
                    + "|NVARCHAR2\\s*\\(\\s*\\d+\\s*\\)"
                    + "|NCHAR\\s*\\(\\s*\\d+\\s*\\)"
                    + "|RAW\\s*\\(\\s*\\d+\\s*\\)"
                    + "|INTEGER"
                    + "|SMALLINT"
                    + "|FLOAT"
                    + "|BINARY_FLOAT"
                    + "|BINARY_DOUBLE"
                    + "|DATE"
                    + "|CLOB"
                    + "|NCLOB"
                    + "|BLOB"
                    + "|LONG\\s+RAW"
                    + "|XMLTYPE"
                    + "|JSON"
                    + ")"
    );

    private static final Pattern NUMBER_MISSING_OPEN =
            Pattern.compile(
                    "(?i)^NUMBER\\s*(\\d+)\\s*[,\\.]\\s*(\\d+)\\)?$"
            );

    private static final Pattern NUMBER_ATTACHED =
            Pattern.compile(
                    "(?i)^NUMBER\\s*(\\d+)\\s*\\(\\s*(\\d+)\\s*\\)$"
            );

    private static final Pattern NUMBER_BROKEN_CLOSE =
            Pattern.compile(
                    "(?i)^NUMBER\\s*\\(\\s*(\\d+)\\s*"
                            + "(?:[,\\.]\\s*(\\d+)\\s*)?\\(?$"
            );

    /**
     * Normalizes a raw datatype value extracted from a DOCX cell.
     *
     * @param rawValue raw datatype expression
     * @return normalized value and recovery warnings
     */
    public RecoveryResult normalize(String rawValue) {
        String original = clean(rawValue);

        if (original.isBlank()) {
            return RecoveryResult.unchanged(original);
        }

        List<String> warnings = new ArrayList<>();

        String value = original
                .toUpperCase(Locale.ROOT)
                .replace("TIME_STAMP", "TIMESTAMP")
                .replaceAll("(?i)IDENTITY", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (!value.equals(original.toUpperCase(Locale.ROOT))) {
            warnings.add(
                    "Normalized datatype text '"
                            + original
                            + "' to '"
                            + value
                            + "'"
            );
        }

        Matcher missingOpen =
                NUMBER_MISSING_OPEN.matcher(value);

        if (missingOpen.matches()) {
            String recovered =
                    "NUMBER("
                            + missingOpen.group(1)
                            + ","
                            + missingOpen.group(2)
                            + ")";

            warnings.add(
                    "Recovered malformed NUMBER datatype '"
                            + original
                            + "' as '"
                            + recovered
                            + "'"
            );

            return new RecoveryResult(
                    recovered,
                    warnings
            );
        }

        Matcher attached =
                NUMBER_ATTACHED.matcher(value);

        if (attached.matches()) {
            String recovered =
                    "NUMBER("
                            + attached.group(1)
                            + ","
                            + attached.group(2)
                            + ")";

            warnings.add(
                    "Recovered attached NUMBER precision and scale '"
                            + original
                            + "' as '"
                            + recovered
                            + "'"
            );

            return new RecoveryResult(
                    recovered,
                    warnings
            );
        }

        Matcher brokenClose =
                NUMBER_BROKEN_CLOSE.matcher(value);

        if (brokenClose.matches()) {
            String recovered =
                    brokenClose.group(2) == null
                            ? "NUMBER("
                            + brokenClose.group(1)
                            + ")"
                            : "NUMBER("
                            + brokenClose.group(1)
                            + ","
                            + brokenClose.group(2)
                            + ")";

            warnings.add(
                    "Closed malformed NUMBER datatype '"
                            + original
                            + "' as '"
                            + recovered
                            + "'"
            );

            return new RecoveryResult(
                    recovered,
                    warnings
            );
        }

        String decimalNormalized =
                value.replaceAll(
                        "(?i)NUMBER\\s*\\(\\s*(\\d+)\\s*"
                                + "\\.\\s*(\\d+)\\s*\\)",
                        "NUMBER($1,$2)"
                );

        if (!decimalNormalized.equals(value)) {
            warnings.add(
                    "Replaced decimal separator in datatype '"
                            + original
                            + "' with a comma"
            );

            value = decimalNormalized;
        }

        if (value.matches(
                "(?i)^NUMBER\\s*\\(\\s*0"
                        + "(?:\\s*,\\s*\\d+)?\\s*\\)$"
        )) {
            String recovered =
                    value.replaceFirst("0", "1");

            warnings.add(
                    "Raised non-positive NUMBER precision in '"
                            + original
                            + "' to '"
                            + recovered
                            + "'"
            );

            value = recovered;
        }

        Matcher matcher =
                COMPLETE_TYPE.matcher(value);

        if (matcher.find()) {
            String recovered =
                    matcher.group(1)
                            .replaceAll("\\s+", " ")
                            .trim();

            if (!recovered.equals(value)) {
                warnings.add(
                        "Selected first recognizable datatype from '"
                                + original
                                + "': '"
                                + recovered
                                + "'"
                );
            }

            return new RecoveryResult(
                    recovered,
                    warnings
            );
        }

        if (containsText(value)) {
            String recovered = "VARCHAR2(4000)";

            warnings.add(
                    "Unrecognized datatype '"
                            + original
                            + "' replaced with fallback '"
                            + recovered
                            + "'"
            );

            return new RecoveryResult(
                    recovered,
                    warnings
            );
        }

        return new RecoveryResult(
                value,
                warnings
        );
    }

    private boolean containsText(String value) {
        return value.matches(".*[A-Z].*")
                || value.matches(".*[\\u0600-\\u06FF].*");
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }
}
