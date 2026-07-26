package com.behsazan.schemaforge.metadata;

import com.behsazan.schemaforge.metadata.repository.MetadataTypeFrequency;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces stable datatype signatures for metadata validation and future
 * document-to-database comparison reports (including Excel output).
 */
public final class DataTypeCanonicalizer {
    private static final Pattern PARAMETERIZED_TYPE =
            Pattern.compile("^([A-Z][A-Z0-9_ ]*?)\\(([^)]*)\\)(.*)$");

    public boolean equivalent(String database, String documentType, String metadataType) {
        return canonicalize(database, documentType).equals(canonicalize(database, metadataType));
    }

    public String canonicalize(String database, String type) {
        String db = normalizeDatabase(database);
        String value = MetadataTypeFrequency.normalize(type)
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.isEmpty()) return value;

        value = canonicalAliases(db, value);
        Matcher matcher = PARAMETERIZED_TYPE.matcher(value);
        if (matcher.matches()) {
            String base = matcher.group(1).trim();
            String arguments = normalizeArguments(base, matcher.group(2));
            String suffix = matcher.group(3).trim();
            value = base + "(" + arguments + ")" + (suffix.isEmpty() ? "" : " " + suffix);
        } else {
            value = applyDefaultPrecision(db, value);
        }
        return value;
    }

    private static String canonicalAliases(String db, String value) {
        return switch (db) {
            case "ORACLE" -> value
                    .replaceFirst("^DECIMAL(?=\\(|$)", "NUMBER")
                    .replaceFirst("^DEC(?=\\(|$)", "NUMBER")
                    .replaceFirst("^NUMERIC(?=\\(|$)", "NUMBER")
                    .replaceFirst("^NVARCHAR(?=\\(|$)", "NVARCHAR2")
                    .replaceFirst("^VARCHAR(?=\\(|$)", "VARCHAR2");
            case "POSTGRESQL" -> value
                    .replaceFirst("^DECIMAL(?=\\(|$)", "NUMERIC")
                    .replaceFirst("^CHARACTER VARYING(?=\\(|$)", "VARCHAR")
                    .replaceFirst("^CHARACTER(?=\\(|$)", "CHAR");
            case "DB2" -> value
                    .replaceFirst("^NUMERIC(?=\\(|$)", "DECIMAL")
                    .replaceFirst("^DEC(?=\\(|$)", "DECIMAL")
                    .replaceFirst("^CHARACTER VARYING(?=\\(|$)", "VARCHAR")
                    .replaceFirst("^CHARACTER(?=\\(|$)", "CHAR");
            case "MYSQL" -> value
                    .replaceFirst("^NUMERIC(?=\\(|$)", "DECIMAL")
                    .replaceFirst("^FIXED(?=\\(|$)", "DECIMAL")
                    .replaceFirst("^CHARACTER(?=\\(|$)", "CHAR");
            case "SQLSERVER" -> value
                    .replaceFirst("^NUMERIC(?=\\(|$)", "DECIMAL")
                    .replaceFirst("^CHARACTER VARYING(?=\\(|$)", "VARCHAR")
                    .replaceFirst("^CHARACTER(?=\\(|$)", "CHAR");
            default -> value;
        };
    }

    private static String normalizeArguments(String base, String arguments) {
        String normalized = arguments.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s+", " ");

        if (isCharacterType(base)) {
            normalized = normalized.replaceFirst("\\s+(BYTE|CHAR|CHARACTERS?)$", "");
        }
        if (isExactNumericType(base) && normalized.matches("\\d+")) {
            normalized += ",0";
        }
        return normalized;
    }

    private static String applyDefaultPrecision(String db, String value) {
        return switch (db) {
            case "ORACLE", "POSTGRESQL", "DB2" ->
                    value.equals("TIMESTAMP") ? "TIMESTAMP(6)" : value;
            case "MYSQL" -> value.equals("TIMESTAMP") ? "TIMESTAMP(0)"
                    : value.equals("DATETIME") ? "DATETIME(0)" : value;
            case "SQLSERVER" -> value.equals("DATETIME2") ? "DATETIME2(7)"
                    : value.equals("DATETIMEOFFSET") ? "DATETIMEOFFSET(7)"
                    : value.equals("TIME") ? "TIME(7)" : value;
            default -> value;
        };
    }

    private static boolean isExactNumericType(String base) {
        return base.equals("NUMBER") || base.equals("NUMERIC") || base.equals("DECIMAL") || base.equals("DEC");
    }

    private static boolean isCharacterType(String base) {
        return base.equals("CHAR") || base.equals("VARCHAR") || base.equals("VARCHAR2")
                || base.equals("NCHAR") || base.equals("NVARCHAR") || base.equals("NVARCHAR2");
    }

    private static String normalizeDatabase(String database) {
        if (database == null) return "";
        String normalized = database.trim().toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL" -> "POSTGRESQL";
            case "MSSQL", "SQLSERVER", "MICROSOFTSQLSERVER" -> "SQLSERVER";
            case "IBMDB2", "DB2" -> "DB2";
            default -> normalized;
        };
    }
}
