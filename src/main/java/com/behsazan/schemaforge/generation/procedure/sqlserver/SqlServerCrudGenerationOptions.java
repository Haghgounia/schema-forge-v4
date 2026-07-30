package com.behsazan.schemaforge.generation.procedure.sqlserver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Options controlling SQL Server metadata-based CRUD procedure generation. */
public record SqlServerCrudGenerationOptions(
        int maximumPageSize,
        List<String> executeGrantees) {

    public SqlServerCrudGenerationOptions {
        if (maximumPageSize < 1) {
            throw new IllegalArgumentException("maximumPageSize must be positive");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (executeGrantees != null) {
            for (String grantee : executeGrantees) {
                if (grantee == null || grantee.isBlank()) {
                    continue;
                }
                String value = grantee.trim().toUpperCase(Locale.ROOT);
                if (!value.matches("[A-Z_][A-Z0-9_$#@]*")) {
                    throw new IllegalArgumentException("invalid SQL Server grantee: " + grantee);
                }
                normalized.add(value);
            }
        }
        executeGrantees = List.copyOf(normalized);
    }

    public static SqlServerCrudGenerationOptions defaults() {
        return new SqlServerCrudGenerationOptions(1000, List.of());
    }

    public static SqlServerCrudGenerationOptions ofGrantees(List<String> grantees) {
        return new SqlServerCrudGenerationOptions(1000, Objects.requireNonNullElse(grantees, List.of()));
    }
}
