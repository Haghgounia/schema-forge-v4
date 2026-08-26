package com.behsazan.schemaforge.dialect.db2luw;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps common Oracle-oriented scalar expressions to Db2 LUW equivalents. */
public final class Db2LuwExpressionMapper {
    private static final Pattern NEXTVAL = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_$#]*(?:\\.[A-Z][A-Z0-9_$#]*)?)\\.NEXTVAL\\b");
    private static final Pattern CURRVAL = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_$#]*(?:\\.[A-Z][A-Z0-9_$#]*)?)\\.CURRVAL\\b");
    private static final Pattern NVL = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");

    public String map(String expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        String mapped = expression.trim();
        mapped = replaceSequenceReference(NEXTVAL, mapped, "NEXT VALUE FOR ");
        mapped = replaceSequenceReference(CURRVAL, mapped, "PREVIOUS VALUE FOR ");
        mapped = SYSTIMESTAMP.matcher(mapped).replaceAll("CURRENT TIMESTAMP(12)");
        mapped = SYSDATE.matcher(mapped).replaceAll("CURRENT TIMESTAMP(0)");
        mapped = NVL.matcher(mapped).replaceAll("COALESCE(");
        return mapped;
    }

    private String replaceSequenceReference(Pattern pattern, String expression, String prefix) {
        Matcher matcher = pattern.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(prefix + matcher.group(1).toUpperCase(Locale.ROOT)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
