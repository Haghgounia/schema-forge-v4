package com.behsazan.schemaforge.dialect.postgresql;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts common Oracle-oriented SQL expressions to PostgreSQL syntax. */
public final class PostgreSqlExpressionMapper {
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)([A-Za-z][A-Za-z0-9_$#]*(?:\\.[A-Za-z][A-Za-z0-9_$#]*)?)\\.NEXTVAL");
    private static final Pattern NVL_FUNCTION = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");
    private static final Pattern SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern CURRENT_DATE_PARENS = Pattern.compile("(?i)\\bCURRENT_DATE\\s*\\(\\s*\\)");

    public String map(String expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        String mapped = expression.trim();
        mapped = SYSTIMESTAMP.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = SYSDATE.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = CURRENT_DATE_PARENS.matcher(mapped).replaceAll("CURRENT_DATE");
        mapped = NVL_FUNCTION.matcher(mapped).replaceAll("COALESCE(");
        mapped = replaceSequenceNextval(mapped);
        return com.behsazan.schemaforge.dialect.PortableRegexLikeAdapter.rewrite(mapped,
                (column, regex) -> {
                    if (!com.behsazan.schemaforge.dialect.PortableRegexLikeAdapter
                            .isApprovedAsciiPattern(regex)) {
                        throw new IllegalArgumentException("Unreviewed PostgreSQL REGEXP_LIKE "
                                + "portability pattern: " + regex);
                    }
                    // POSIX regular-expression operator is supported before PostgreSQL 15;
                    // C collation preserves the ASCII range semantics in the source.
                    return "(" + column + " COLLATE \"C\" ~ '" + regex + "')";
                });
    }

    private String replaceSequenceNextval(String expression) {
        Matcher matcher = SEQUENCE_NEXTVAL.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String sequenceName = matcher.group(1).toLowerCase(Locale.ROOT);
            matcher.appendReplacement(result, Matcher.quoteReplacement("nextval('" + sequenceName + "')"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
