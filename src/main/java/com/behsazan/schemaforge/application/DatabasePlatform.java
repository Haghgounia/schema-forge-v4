package com.behsazan.schemaforge.application;

import java.util.Arrays;
import java.util.Locale;

/** Database engines currently supported by SchemaForge DDL generation. */
public enum DatabasePlatform {
    ORACLE("oracle", "ora"),
    POSTGRESQL("postgresql", "postgres", "pg"),
    DB2_ZOS("db2zos", "db2-zos", "db2", "zos");

    private final String[] aliases;

    DatabasePlatform(String... aliases) {
        this.aliases = aliases;
    }

    public String commandLineName() {
        return aliases[0];
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
                                + ". Supported values: oracle, postgresql, db2zos"));
    }
}
