package com.behsazan.schemaforge.validation.constraint;

import com.behsazan.schemaforge.domain.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative identifier-reference audit for CHECK expressions. */
public final class CheckConstraintReferenceAnalyzer {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");
    private static final Set<String> KEYWORDS = Set.of(
            "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "ESCAPE",
            "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE", "UNKNOWN",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "SYSDATE", "SYSTIMESTAMP",
            "DATE", "TIME", "TIMESTAMP", "INTERVAL", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND",
            "CAST", "AS", "CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2",
            "NUMBER", "NUMERIC", "DECIMAL", "DEC", "INTEGER", "INT", "SMALLINT", "BIGINT",
            "FLOAT", "DOUBLE", "REAL", "BOOLEAN", "BINARY", "VARBINARY",
            "MOD", "DIV", "PRIOR", "LEVEL", "ROWNUM"
    );

    private CheckConstraintReferenceAnalyzer() {
    }

    public static Set<String> unknownColumns(Table table, String expression) {
        if (expression == null || expression.isBlank()) return Set.of();
        Set<String> columns = new LinkedHashSet<>();
        table.columns().forEach(column -> columns.add(column.name().normalized()));

        String source = maskStringLiterals(expression);
        Matcher matcher = TOKEN.matcher(source);
        Set<String> unknown = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group().toUpperCase(Locale.ROOT);
            if (columns.contains(token) || KEYWORDS.contains(token)) continue;
            if (isFunctionCall(source, matcher.end())) continue;
            if (isQualifierToken(source, matcher.end())) continue;
            unknown.add(token);
        }
        return Set.copyOf(unknown);
    }

    /** Returns referenced table columns in first-occurrence order. */
    public static List<String> referencedColumns(Table table, String expression) {
        if (expression == null || expression.isBlank()) return List.of();
        Set<String> columns = new LinkedHashSet<>();
        table.columns().forEach(column -> columns.add(column.name().normalized()));

        String source = maskStringLiterals(expression);
        Matcher matcher = TOKEN.matcher(source);
        Set<String> referenced = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group().toUpperCase(Locale.ROOT);
            if (!columns.contains(token)) continue;
            referenced.add(token);
        }
        return List.copyOf(new ArrayList<>(referenced));
    }

    public static boolean valid(Table table, String expression) {
        return unknownColumns(table, expression).isEmpty();
    }

    private static boolean isFunctionCall(String source, int end) {
        int i = end;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        return i < source.length() && source.charAt(i) == '(';
    }

    private static boolean isQualifierToken(String source, int end) {
        int i = end;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        return i < source.length() && source.charAt(i) == '.';
    }

    private static String maskStringLiterals(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (inString) {
                if (ch == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    out.append("  ");
                    i++;
                } else if (ch == '\'') {
                    inString = false;
                    out.append(' ');
                } else {
                    out.append(' ');
                }
            } else if (ch == '\'') {
                inString = true;
                out.append(' ');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
