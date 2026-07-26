package com.behsazan.schemaforge.generation.issue;

import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Renders compact, deterministic SQL comments for all issues attached to one schema object. */
public final class InlineIssueRenderer {
    private static final int DEFAULT_MAX_LENGTH = 120;

    private static final Map<String, String> SHORT_CODES = Map.ofEntries(
            Map.entry("SPELLING_WARNING", "SPELL"),
            Map.entry("SPELL_CHECK_UNAVAILABLE", "SPELL-SVC"),
            Map.entry("DUPLICATE_COLUMN", "DUP"),
            Map.entry("METADATA_DATATYPE_MISMATCH", "TYPE"),
            Map.entry("METADATA_LENGTH_MISMATCH", "LEN"),
            Map.entry("METADATA_PRECISION_MISMATCH", "PREC"),
            Map.entry("METADATA_SCALE_MISMATCH", "SCALE"),
            Map.entry("METADATA_NULLABILITY_MISMATCH", "NULL"),
            Map.entry("INVALID_DEFAULT_VALUE", "DEFAULT"),
            Map.entry("INVALID_CHECK", "CHECK"),
            Map.entry("FK_PARENT_NOT_FOUND", "FK"),
            Map.entry("RESERVED_WORD", "RESERVED"),
            Map.entry("DATATYPE_NORMALIZED", "NORMALIZED"),
            Map.entry("SCHEMA_NOT_FOUND", "SCHEMA"),
            Map.entry("TABLE_IN_DIFFERENT_SCHEMA", "TBL-SCHEMA"),
            Map.entry("FK_TABLE_NOT_FOUND", "FK-TABLE"),
            Map.entry("FK_SCHEMA_RESOLVED", "FK-SCHEMA"),
            Map.entry("FK_SCHEMA_AMBIGUOUS", "FK-AMB"),
            Map.entry("PLURAL_COLUMN_COMPONENT", "SINGULAR"),
            Map.entry("COLUMN_DATATYPE_MISSING", "TYPE-MISSING"),
            Map.entry("COLUMN_DESCRIPTION_MISSING", "DESC-MISSING"),
            Map.entry("TABLE_NAME_NOT_PLURAL", "TABLE-PLURAL")
    );

    private final int maxLength;

    public InlineIssueRenderer() {
        this(DEFAULT_MAX_LENGTH);
    }

    public InlineIssueRenderer(int maxLength) {
        if (maxLength < 40) {
            throw new IllegalArgumentException("maxLength must be at least 40");
        }
        this.maxLength = maxLength;
    }

    public String render(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }

        Map<String, List<String>> bySeverity = new LinkedHashMap<>();
        bySeverity.put("ERROR", new ArrayList<>());
        bySeverity.put("WARNING", new ArrayList<>());
        bySeverity.put("INFO", new ArrayList<>());

        issues.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((ValidationIssue issue) -> severityRank(issue.severity()))
                        .thenComparing(issue -> normalize(issue.code())))
                .forEach(issue -> {
                    String severity = normalizeSeverity(issue.severity());
                    String code = shortCode(issue.code());
                    List<String> codes = bySeverity.computeIfAbsent(severity, ignored -> new ArrayList<>());
                    if (!codes.contains(code)) {
                        codes.add(code);
                    }
                });

        List<String> groups = new ArrayList<>();
        appendGroup(groups, "E", bySeverity.get("ERROR"));
        appendGroup(groups, "W", bySeverity.get("WARNING"));
        appendGroup(groups, "I", bySeverity.get("INFO"));
        bySeverity.entrySet().stream()
                .filter(entry -> !List.of("ERROR", "WARNING", "INFO").contains(entry.getKey()))
                .forEach(entry -> appendGroup(groups, severityPrefix(entry.getKey()), entry.getValue()));

        if (groups.isEmpty()) {
            return "";
        }
        return truncate(" -- " + String.join(" ", groups));
    }

    private void appendGroup(List<String> groups, String prefix, List<String> codes) {
        if (codes != null && !codes.isEmpty()) {
            groups.add(prefix + ":" + String.join("|", codes));
        }
    }

    private String shortCode(String code) {
        String normalized = normalize(code);
        return SHORT_CODES.getOrDefault(normalized, compactFallback(normalized));
    }

    private String compactFallback(String code) {
        if (code.isBlank()) {
            return "ISSUE";
        }
        String compact = code
                .replace("_WARNING", "")
                .replace("_MISMATCH", "")
                .replace("METADATA_", "META_")
                .replace("INVALID_", "INV_");
        return compact.length() <= 24 ? compact : compact.substring(0, 24);
    }

    private String truncate(String text) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 4) + " ...";
    }

    private static int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "ERROR" -> 0;
            case "WARNING" -> 1;
            case "INFO" -> 2;
            default -> 3;
        };
    }

    private static String normalizeSeverity(String severity) {
        String value = normalize(severity);
        return value.isBlank() ? "WARNING" : value;
    }

    private static String severityPrefix(String severity) {
        return severity.isBlank() ? "W" : severity.substring(0, 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
