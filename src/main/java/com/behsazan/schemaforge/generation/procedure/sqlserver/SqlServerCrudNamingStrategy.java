package com.behsazan.schemaforge.generation.procedure.sqlserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Centralized SQL Server CRUD procedure and parameter naming policy. */
public final class SqlServerCrudNamingStrategy {
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    public String createProcedure(String tableName) {
        return safe(tableName + "_CREATE");
    }

    public String updateProcedure(String tableName) {
        return safe(tableName + "_UPDATE");
    }

    public String deleteProcedure(String tableName) {
        return safe(tableName + "_DELETE");
    }

    public String getByIdProcedure(String tableName) {
        return safe(tableName + "_GET_BY_ID");
    }

    public String searchProcedure(String tableName) {
        return safe(tableName + "_SEARCH");
    }

    public String inputParameter(String columnName) {
        return "@" + safe("P_" + columnName);
    }

    public String outputParameter(String columnName) {
        return "@" + safe("O_" + columnName);
    }

    public String safe(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SQL Server identifier must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_$#@]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL Server identifier contains no usable characters: " + value);
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "X_" + normalized;
        }
        if (normalized.length() <= MAX_IDENTIFIER_LENGTH) {
            return normalized;
        }
        String suffix = "_" + hash(normalized).substring(0, 12);
        return normalized.substring(0, MAX_IDENTIFIER_LENGTH - suffix.length()) + suffix;
    }

    public String quote(String value) {
        return "[" + value.replace("]", "]]") + "]";
    }

    public String qualify(String schema, String object) {
        return quote(schema) + "." + quote(object);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
