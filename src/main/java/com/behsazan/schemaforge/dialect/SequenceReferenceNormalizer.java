package com.behsazan.schemaforge.dialect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares Oracle sequence NEXTVAL and PostgreSQL nextval('sequence'::regclass) defaults. */
public final class SequenceReferenceNormalizer {
    private static final Pattern POSTGRESQL_NEXTVAL = Pattern.compile(
            "^NEXTVAL\\('([^']+)'(?:::REGCLASS)?\\)$");

    private SequenceReferenceNormalizer() { }

    public static String normalize(String reference) {
        if (reference == null) return "";
        String normalized = reference.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("\"", "");
        while (normalized.startsWith("(") && normalized.endsWith(")")
                && normalized.length() > 2 && outerParensEnclose(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        Matcher postgres = POSTGRESQL_NEXTVAL.matcher(normalized);
        if (postgres.matches()) return postgres.group(1) + ".NEXTVAL";
        return normalized;
    }

    private static boolean outerParensEnclose(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '(') depth++;
            if (value.charAt(i) == ')') depth--;
            if (depth == 0 && i != value.length() - 1) return false;
        }
        return depth == 0;
    }
}
