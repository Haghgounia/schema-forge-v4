package com.behsazan.schemaforge.dialect.sqlserver;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts common Oracle-oriented scalar expressions to SQL Server syntax. */
public final class SqlServerExpressionMapper {
    private static final Pattern NEXTVAL = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_$#]*(?:\\.[A-Z][A-Z0-9_$#]*)?)\\.NEXTVAL\\b");
    private static final Pattern NVL = Pattern.compile("(?i)\\bNVL\\s*\\(");
    private static final Pattern SYSTIMESTAMP = Pattern.compile("(?i)\\bSYSTIMESTAMP\\b");
    private static final Pattern SYSDATE = Pattern.compile("(?i)\\bSYSDATE\\b");
    private static final Pattern CURRENT_DATE_PARENS = Pattern.compile("(?i)\\bCURRENT_DATE\\s*\\(\\s*\\)");
    private static final Pattern SYS_GUID = Pattern.compile("(?i)\\bSYS_GUID\\s*\\(\\s*\\)");

    public String map(String expression) {
        Objects.requireNonNull(expression, "expression must not be null");
        String mapped = expression.trim();
        mapped = SYSTIMESTAMP.matcher(mapped).replaceAll("SYSDATETIMEOFFSET()");
        mapped = SYSDATE.matcher(mapped).replaceAll("SYSDATETIME()");
        mapped = CURRENT_DATE_PARENS.matcher(mapped).replaceAll("CAST(SYSDATETIME() AS date)");
        mapped = SYS_GUID.matcher(mapped).replaceAll("NEWID()");
        mapped = NVL.matcher(mapped).replaceAll("COALESCE(");
        mapped = replaceSequenceReference(mapped);
        return mapped;
    }

    private String replaceSequenceReference(String expression) {
        Matcher matcher = NEXTVAL.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement("NEXT VALUE FOR " + matcher.group(1).toUpperCase(Locale.ROOT)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
