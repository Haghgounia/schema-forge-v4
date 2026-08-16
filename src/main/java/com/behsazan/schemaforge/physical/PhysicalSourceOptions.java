package com.behsazan.schemaforge.physical;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Small source-aware helper for Phase-1 physical comments.
 *
 * <p>The input document is treated as evidence, not truth. A syntactically
 * acceptable source value can be retained in the commented candidate. An
 * invalid value is never silently clamped or normalized: the block records a
 * SOURCE PHYSICAL ISSUE and emits a placeholder that requires DBA review.</p>
 */
public final class PhysicalSourceOptions {
    private PhysicalSourceOptions() {
    }

    public static Optional<String> find(Table table, String... keys) {
        if (table == null || keys == null || keys.length == 0) {
            return Optional.empty();
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            for (Map.Entry<String, String> entry : table.physicalOptions().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)
                        && entry.getValue() != null
                        && !entry.getValue().isBlank()) {
                    return Optional.of(entry.getValue().trim());
                }
            }
        }
        return Optional.empty();
    }

    public static String integerClause(
            List<String> lines,
            Table table,
            String platform,
            String clause,
            int documentedDefault,
            int minimum,
            int maximum,
            String placeholder,
            String... keys) {
        Optional<String> source = find(table, keys);
        if (source.isEmpty()) {
            return clause + " " + documentedDefault;
        }

        String raw = source.get();
        try {
            int value = Integer.parseInt(raw);
            if (value >= minimum && value <= maximum) {
                lines.add("-- [SOURCE PHYSICAL] " + firstKey(keys) + "=" + raw
                        + " retained for DBA review.");
                return clause + " " + value;
            }
        } catch (NumberFormatException ignored) {
            // handled below; source must remain visible and must not be normalized.
        }

        lines.add("-- [SOURCE PHYSICAL ISSUE][" + platform + "] " + firstKey(keys)
                + "=" + raw + " is outside the accepted " + minimum + ".." + maximum
                + " integer range; source value was not normalized.");
        return clause + " <" + placeholder + ">";
    }

    public static String enumClause(
            List<String> lines,
            Table table,
            String platform,
            String label,
            String documentedDefault,
            String placeholder,
            Set<String> acceptedValues,
            String... keys) {
        Optional<String> source = find(table, keys);
        if (source.isEmpty()) {
            return documentedDefault;
        }

        String raw = source.get().trim();
        String normalized = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (acceptedValues.contains(normalized)) {
            lines.add("-- [SOURCE PHYSICAL] " + firstKey(keys) + "=" + raw
                    + " retained for DBA review.");
            return normalized;
        }

        lines.add("-- [SOURCE PHYSICAL ISSUE][" + platform + "] " + label + "=" + raw
                + " is not one of " + acceptedValues
                + "; source value was not normalized.");
        return "<" + placeholder + ">";
    }


    public static String sourceIntegerOrPlaceholder(
            List<String> lines,
            Table table,
            String platform,
            String prefix,
            String placeholder,
            IntPredicate accepted,
            String acceptedDescription,
            String... keys) {
        Optional<String> source = find(table, keys);
        if (source.isEmpty()) {
            return prefix + "<" + placeholder + ">";
        }

        String raw = source.get();
        try {
            int value = Integer.parseInt(raw);
            if (accepted.test(value)) {
                lines.add("-- [SOURCE PHYSICAL] " + firstKey(keys) + "=" + raw
                        + " retained for DBA review.");
                return prefix + value;
            }
        } catch (NumberFormatException ignored) {
            // handled below; source must remain visible and must not be normalized.
        }

        lines.add("-- [SOURCE PHYSICAL ISSUE][" + platform + "] " + firstKey(keys)
                + "=" + raw + " must be " + acceptedDescription
                + "; source value was not normalized.");
        return prefix + "<" + placeholder + ">";
    }

    public static String sourceOrPlaceholder(
            List<String> lines,
            Table table,
            String prefix,
            String placeholder,
            String... keys) {
        Optional<String> source = find(table, keys);
        if (source.isPresent()) {
            lines.add("-- [SOURCE PHYSICAL] " + firstKey(keys) + "=" + source.get()
                    + " retained for DBA review.");
            return prefix + source.get();
        }
        return prefix + "<" + placeholder + ">";
    }

    private static String firstKey(String... keys) {
        return Arrays.stream(keys)
                .filter(key -> key != null && !key.isBlank())
                .findFirst()
                .orElse("PHYSICAL_OPTION");
    }
}
