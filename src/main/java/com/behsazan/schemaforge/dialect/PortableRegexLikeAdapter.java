package com.behsazan.schemaforge.dialect;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed, two-argument REGEXP_LIKE adapter for a validated subset of EA
 * identifier checks. Arbitrary source regex patterns are never copied to a
 * target without proving portability.
 */
public final class PortableRegexLikeAdapter {
    private static final Pattern CALL = Pattern.compile(
            "(?i)\\bREGEXP_LIKE\\s*\\(\\s*([A-Z_][A-Z0-9_$#]*)\\s*,\\s*'([^']*)'\\s*\\)");
    private static final Pattern TOKEN = Pattern.compile("(?i)\\bREGEXP_LIKE\\s*\\(");

    private PortableRegexLikeAdapter() { }

    public static String rewrite(String expression, BiFunction<String, String, String> renderer) {
        Objects.requireNonNull(expression, "expression must not be null");
        Objects.requireNonNull(renderer, "renderer must not be null");
        if (!TOKEN.matcher(expression).find()) return expression;
        Matcher matcher = CALL.matcher(expression);
        StringBuffer buffer = new StringBuffer();
        int matches = 0;
        while (matcher.find()) {
            matches++;
            String replacement = renderer.apply(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        if (matches == 0 || TOKEN.matcher(buffer.toString()).find()) {
            throw new IllegalArgumentException("Unsupported REGEXP_LIKE signature; "
                    + "requires explicit, verified target DBMS support");
        }
        return buffer.toString();
    }

    public static boolean isApprovedAsciiPattern(String regex) {
        return switch (regex) {
            case "^[A-Z]{3}$", "^[0-9]{3}$", "^[A-Z][A-Z0-9_]*$",
                    "^[A-Z0-9_\\-]{1,30}$", "^[A-Z0-9_-]{1,30}$" -> true;
            default -> false;
        };
    }
}
