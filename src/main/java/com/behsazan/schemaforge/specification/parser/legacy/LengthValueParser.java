package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses legacy length, precision and scale values without making unsafe assumptions.
 *
 * <p>The parser normalizes Persian digits and separators and repairs a limited set of verified
 * Word-layout artifacts, including split trailing zeroes, reversed parentheses and tracked
 * precision/scale revisions. Values that contain competing numeric groups are marked ambiguous
 * rather than collapsed into a guessed database type definition.</p>
 */
final class LengthValueParser {
    private static final Pattern SINGLE = Pattern.compile("^\\(?\\s*(\\d+)\\s*\\)?$");
    private static final Pattern PRECISION_SCALE = Pattern.compile("^\\(?\\s*(\\d+)\\s*[,.]\\s*(\\d+)\\s*\\)?$");
    private static final Pattern NUMERIC_GROUP = Pattern.compile("\\d+");
    private static final Pattern SPACED_SINGLE_DIGITS = Pattern.compile(
            "^(\\d(?:\\s+\\d){2,})$"
    );
    private static final Pattern ZERO_TAIL_SPLIT = Pattern.compile(
            "^(\\d+)\\s+(0+)$"
    );
    private static final Pattern CONCATENATED_PRECISION_SCALE = Pattern.compile(
            "^(\\d+)[,.](\\d+)[,.](\\d+)$"
    );

    private LengthValueParser() {
    }

    static ParsedLength parse(String raw) {
        String normalized = TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(raw))
                .replace('،', ',')
                // Some Persian specifications use the conjunction character between
                // precision and scale, for example 20و5 instead of 20,5.
                .replaceAll("(?<=\\d)\\s*و\\s*(?=\\d)", ",")
                // A colon is also used as precision/scale separator in a few legacy tables.
                .replaceAll("(?<=\\d)\\s*:\\s*(?=\\d)", ",")
                // Common Word-editing artifact: "(23.,5)" means DECIMAL(23,5).
                .replaceAll("(?<=\\d)\\s*\\.\\s*(?=,)", "")
                // RTL Word runs sometimes reverse or duplicate visible parentheses.
                .replaceFirst("^[()]+\\s*", "")
                .replaceFirst("\\s*[()]+$", "");
        if (normalized.isBlank()) {
            return new ParsedLength("", null, null, null, false);
        }

        // A small number of legacy Word cells carry a formatting slash/backtick
        // immediately before or after an otherwise unambiguous numeric length.
        // Strip only those wrappers; do not remove signs or competing numeric text.
        normalized = normalized
                .replaceFirst("^`+(?=\\d)", "")
                .replaceFirst("^\\\\+(?=\\d)", "")
                .replaceFirst("(?<=\\d)`+$", "")
                .replaceFirst("(?<=\\d)\\\\+$", "");

        // Word sometimes splits a continuous number immediately before a trailing
        // zero group: "3 00" and "100 0" are visual renderings of 300 and 1000.
        // Do not collapse ordinary competing values such as "8 10" or "30 15".
        Matcher zeroTail = ZERO_TAIL_SPLIT.matcher(normalized);
        if (zeroTail.matches()) {
            normalized = zeroTail.group(1) + zeroTail.group(2);
        }

        // Word sometimes stores a visually continuous number as one digit per run or
        // paragraph. Only collapse three or more one-digit groups so ordinary values
        // such as "9 11" remain ambiguous rather than being guessed.
        Matcher spacedDigits = SPACED_SINGLE_DIGITS.matcher(normalized);
        if (spacedDigits.matches()) {
            normalized = spacedDigits.group(1).replaceAll("\\s+", "");
        }

        Matcher pair = PRECISION_SCALE.matcher(normalized);
        if (pair.matches()) {
            return new ParsedLength(
                    pair.group(1) + "," + pair.group(2),
                    null,
                    parseInteger(pair.group(1)),
                    parseInteger(pair.group(2)),
                    false
            );
        }

        Matcher single = SINGLE.matcher(normalized);
        if (single.matches()) {
            return new ParsedLength(
                    single.group(1),
                    parseInteger(single.group(1)),
                    null,
                    null,
                    false
            );
        }

        ParsedLength revised = parseConcatenatedRevision(normalized);
        if (revised != null) {
            return revised;
        }

        Matcher groups = NUMERIC_GROUP.matcher(normalized);
        int count = 0;
        while (groups.find()) {
            count++;
        }
        return new ParsedLength(normalized, null, null, null, count > 1);
    }

    /**
     * Recovers a tracked-change artifact such as {@code 20,530,5}, which represents
     * an obsolete {@code 20,5} immediately followed by the current {@code 30,5}.
     * Recovery is accepted only when a single split of the middle digit group yields
     * two valid precision/scale pairs with the same scale.
     */
    private static ParsedLength parseConcatenatedRevision(String value) {
        Matcher matcher = CONCATENATED_PRECISION_SCALE.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        int oldPrecision = parseInteger(matcher.group(1));
        int finalScale = parseInteger(matcher.group(3));
        String middle = matcher.group(2);
        ParsedLength candidate = null;
        for (int split = 1; split < middle.length(); split++) {
            int oldScale = parseInteger(middle.substring(0, split));
            int newPrecision = parseInteger(middle.substring(split));
            if (oldPrecision <= 0 || newPrecision <= 0 || oldScale < 0 || finalScale < 0
                    || oldScale > oldPrecision || finalScale > newPrecision
                    || oldScale != finalScale) {
                continue;
            }
            ParsedLength current = new ParsedLength(
                    newPrecision + "," + finalScale,
                    null,
                    newPrecision,
                    finalScale,
                    false
            );
            if (candidate != null) {
                return null;
            }
            candidate = current;
        }
        return candidate;
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Returns the normalized source together with either a length or precision/scale pair and
     * an ambiguity flag for values that require manual or later-stage resolution.
     */
    record ParsedLength(
            String normalized,
            Integer length,
            Integer precision,
            Integer scale,
            boolean ambiguous
    ) {
    }
}
