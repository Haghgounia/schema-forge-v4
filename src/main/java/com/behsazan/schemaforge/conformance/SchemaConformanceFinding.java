package com.behsazan.schemaforge.conformance;

/** One normalized finding produced by a named SchemaForge conformance rule family. */
public record SchemaConformanceFinding(
        String ruleFamily,
        String severity,
        String code,
        String path,
        String message) {

    public SchemaConformanceFinding {
        ruleFamily = safe(ruleFamily);
        severity = safe(severity);
        code = safe(code);
        path = safe(path);
        message = safe(message);
        if (ruleFamily.isBlank()) {
            throw new IllegalArgumentException("ruleFamily must not be blank");
        }
        if (severity.isBlank()) {
            throw new IllegalArgumentException("severity must not be blank");
        }
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
