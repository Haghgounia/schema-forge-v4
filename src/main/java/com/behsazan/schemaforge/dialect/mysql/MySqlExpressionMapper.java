package com.behsazan.schemaforge.dialect.mysql;

import java.util.Objects;
import java.util.regex.Pattern;

/** Converts a small, evidence-safe set of Oracle-oriented expressions to MySQL syntax. */
public final class MySqlExpressionMapper {
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)\\b[A-Za-z][A-Za-z0-9_$#]*(?:\\.[A-Za-z][A-Za-z0-9_$#]*)?\\.NEXTVAL\\b");
    private static final Pattern NVL_FUNCTION = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");
    private static final Pattern SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern CURRENT_DATE_PARENS = Pattern.compile("(?i)\\bCURRENT_DATE\\s*\\(\\s*\\)");

    public String map(String expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        String mapped = expression.trim();
        if (SEQUENCE_NEXTVAL.matcher(mapped).find()) {
            throw new UnsupportedOperationException(
                    "MySQL logical foundation does not map sequence NEXTVAL expressions");
        }
        mapped = SYSTIMESTAMP.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = SYSDATE.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = CURRENT_DATE_PARENS.matcher(mapped).replaceAll("CURRENT_DATE");
        mapped = NVL_FUNCTION.matcher(mapped).replaceAll("COALESCE(");
        return mapped;
    }
}
