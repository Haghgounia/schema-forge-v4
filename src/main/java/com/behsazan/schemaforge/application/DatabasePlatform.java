package com.behsazan.schemaforge.application;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Database engines currently supported by SchemaForge DDL generation. */
public enum DatabasePlatform {
    ORACLE("oracle", "ora"),
    POSTGRESQL("postgresql", "postgres", "pg"),
    DB2_ZOS("db2zos", "db2-zos", "db2", "zos"),
    DB2_LUW("db2luw", "db2-luw", "luw"),
    SQLSERVER("sqlserver", "sql-server", "mssql", "sqlsrv"),
    MYSQL("mysql");

    private final String[] aliases;

    DatabasePlatform(String... aliases) {
        this.aliases = aliases;
    }

    public String commandLineName() {
        return aliases[0];
    }

    /** Returns selected platform command names in enum order for deterministic manifests. */
    public static List<String> valuesAsList(Set<DatabasePlatform> selected) {
        return Arrays.stream(values())
                .filter(selected::contains)
                .map(DatabasePlatform::commandLineName)
                .toList();
    }

    /** Resolves an optional REST platform selection. Missing/blank selection means all supported platforms. */
    public static Set<DatabasePlatform> parseSelection(List<String> values) {
        if (values == null || values.isEmpty()) {
            return EnumSet.allOf(DatabasePlatform.class);
        }
        EnumSet<DatabasePlatform> selected = EnumSet.noneOf(DatabasePlatform.class);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String token : value.split(",")) {
                String normalized = token.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                if (normalized.equalsIgnoreCase("all")) {
                    return EnumSet.allOf(DatabasePlatform.class);
                }
                selected.add(parse(normalized));
            }
        }
        if (selected.isEmpty()) {
            return EnumSet.allOf(DatabasePlatform.class);
        }
        return selected;
    }

    public static DatabasePlatform parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Database platform must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(platform -> Arrays.asList(platform.aliases).contains(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported database platform: " + value
                                + ". Supported values: oracle, postgresql, db2zos, db2luw, sqlserver, mysql"));
    }
}
