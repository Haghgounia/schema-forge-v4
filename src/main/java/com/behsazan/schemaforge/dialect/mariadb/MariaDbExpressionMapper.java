package com.behsazan.schemaforge.dialect.mariadb;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the evidence-safe Oracle-oriented expression subset to MariaDB syntax. */
public final class MariaDbExpressionMapper {
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)\\b([A-Za-z][A-Za-z0-9_$#]*(?:\\.[A-Za-z][A-Za-z0-9_$#]*)?)\\.NEXTVAL\\b");
    private static final Pattern NVL_FUNCTION = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");
    private static final Pattern SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern CURRENT_DATE_PARENS = Pattern.compile("(?i)\\bCURRENT_DATE\\s*\\(\\s*\\)");

    public String map(String expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        String mapped = expression.trim();
        mapped = mapSequenceNextval(mapped);
        mapped = SYSTIMESTAMP.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = SYSDATE.matcher(mapped).replaceAll("CURRENT_TIMESTAMP");
        mapped = CURRENT_DATE_PARENS.matcher(mapped).replaceAll("CURRENT_DATE");
        mapped = NVL_FUNCTION.matcher(mapped).replaceAll("COALESCE(");
        return mapped;
    }

    private String mapSequenceNextval(String expression) {
        Matcher matcher = SEQUENCE_NEXTVAL.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement("NEXT VALUE FOR " + matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
