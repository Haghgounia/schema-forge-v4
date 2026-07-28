package com.behsazan.schemaforge.generation.procedure.oracle;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Options controlling Oracle metadata-based CRUD package generation. */
public record OracleCrudGenerationOptions(
        int maximumPageSize,
        List<String> executeGrantees) {

    public OracleCrudGenerationOptions {
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
                if (!value.matches("[A-Z][A-Z0-9_$#]*")) {
                    throw new IllegalArgumentException("invalid Oracle grantee: " + grantee);
                }
                normalized.add(value);
            }
        }
        executeGrantees = List.copyOf(normalized);
    }

    public static OracleCrudGenerationOptions defaults() {
        return new OracleCrudGenerationOptions(1000, List.of());
    }

    public static OracleCrudGenerationOptions ofGrantees(List<String> grantees) {
        return new OracleCrudGenerationOptions(1000, Objects.requireNonNullElse(grantees, List.of()));
    }
}
