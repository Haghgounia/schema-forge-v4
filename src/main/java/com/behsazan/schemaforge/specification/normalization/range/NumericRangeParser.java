package com.behsazan.schemaforge.specification.normalization.range;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Persian/English free-text Range cells into database-independent numeric ranges.
 * Human descriptions are ignored; only integer values are retained.
 */
public final class NumericRangeParser {
    /*
     * Range cells frequently come from DOCX with broken visual runs, for example
     * "روزان1:ه" or "Inacti0:ve". Therefore numeric tokens must not require
     * whitespace or a non-letter boundary. This parser is only applied to the
     * dedicated Range cell, so extracting embedded integers is intentional.
     */
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern INTERVAL = Pattern.compile(
            "(?iu)([-+]?\\d+)\\s*(?:تا|الی|to|through|\\.\\.|[-–—])\\s*([-+]?\\d+)");

    public Optional<NumericRange> parse(String rawRange) {
        String normalized = normalize(rawRange);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        Matcher intervalMatcher = INTERVAL.matcher(normalized);
        if (intervalMatcher.find()) {
            long first = Long.parseLong(intervalMatcher.group(1));
            long second = Long.parseLong(intervalMatcher.group(2));
            return Optional.of(new NumericRange.Interval(
                    Math.min(first, second),
                    Math.max(first, second)));
        }

        List<Long> values = new ArrayList<>();
        Matcher integerMatcher = INTEGER.matcher(normalized);
        while (integerMatcher.find()) {
            values.add(Long.parseLong(integerMatcher.group()));
        }

        List<Long> distinct = values.stream().distinct().sorted().toList();
        if (distinct.size() >= 2) {
            return Optional.of(new NumericRange.Enumeration(distinct));
        }
        return Optional.empty();
    }

    public Optional<String> toCheckExpression(String columnName, String rawRange) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must not be blank");
        }
        return parse(rawRange).map(range -> switch (range) {
            case NumericRange.Interval interval -> columnName + " BETWEEN "
                    + interval.minimum() + " AND " + interval.maximum();
            case NumericRange.Enumeration enumeration -> columnName + " IN ("
                    + enumeration.values().stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElseThrow()
                    + ")";
        });
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u200c', ' ')
                .replace('\u200f', ' ')
                .replace('\u202a', ' ')
                .replace('\u202b', ' ')
                .replace('\u202c', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character >= '\u06F0' && character <= '\u06F9') {
                result.append((char) ('0' + character - '\u06F0'));
            } else if (character >= '\u0660' && character <= '\u0669') {
                result.append((char) ('0' + character - '\u0660'));
            } else {
                result.append(character);
            }
        }
        return result.toString().trim();
    }
}
