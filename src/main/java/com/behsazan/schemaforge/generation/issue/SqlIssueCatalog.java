package com.behsazan.schemaforge.generation.issue;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Merges validator findings and parser recovery findings into one searchable issue catalog. */
public final class SqlIssueCatalog {
    private final List<ValidationIssue> issues;
    private final Map<String, List<ValidationIssue>> issuesByPath;

    private SqlIssueCatalog(List<ValidationIssue> issues) {
        this.issues = issues.stream()
                .sorted(Comparator.comparingInt((ValidationIssue issue) -> severityRank(issue.severity()))
                        .thenComparing(issue -> normalize(issue.path()))
                        .thenComparing(issue -> normalize(issue.code()))
                        .thenComparing(issue -> normalize(issue.message())))
                .toList();
        Map<String, List<ValidationIssue>> grouped = new LinkedHashMap<>();
        for (ValidationIssue issue : this.issues) {
            grouped.computeIfAbsent(normalize(issue.path()), ignored -> new ArrayList<>()).add(issue);
        }
        this.issuesByPath = Map.copyOf(grouped);
    }

    public static SqlIssueCatalog from(DatabaseSchema schema, ValidationReport report) {
        List<ValidationIssue> merged = new ArrayList<>();
        if (report != null) {
            merged.addAll(report.issues());
        }
        addRecoveryIssues(schema, merged);
        return new SqlIssueCatalog(deduplicate(merged));
    }

    public List<ValidationIssue> all() {
        return issues;
    }

    /** Returns issues attached directly to the table itself, excluding column and constraint findings. */
    public List<ValidationIssue> forTable(Table table) {
        String path = "tables." + table.qualifiedName().name().value();
        return issuesByPath.getOrDefault(normalize(path), List.of());
    }

    public List<ValidationIssue> forColumn(Table table, String columnName) {
        String path = "tables." + table.qualifiedName().name().value() + ".columns." + columnName;
        return issuesByPath.getOrDefault(normalize(path), List.of());
    }

    private static void addRecoveryIssues(DatabaseSchema schema, List<ValidationIssue> target) {
        String rawWarnings = metadata(schema.metadata(), "recovery.warnings");
        if (rawWarnings == null || rawWarnings.isBlank()) {
            return;
        }
        for (String warning : rawWarnings.lines().filter(line -> !line.isBlank()).toList()) {
            if (warning.startsWith("DUPLICATE_COLUMN|")) {
                Map<String, String> values = parseWarning(warning);
                String column = values.getOrDefault("name", "UNKNOWN");
                String path = resolveColumnPath(schema, column);
                String message = "Duplicate column definition; first Word row "
                        + values.getOrDefault("firstRow", "?")
                        + ", duplicate Word row " + values.getOrDefault("duplicateRow", "?")
                        + ". First definition remains executable. Duplicate definition: "
                        + values.getOrDefault("definition", column);
                target.add(new ValidationIssue("WARNING", "DUPLICATE_COLUMN", path, message));
            } else if (warning.startsWith("COLUMN_DATATYPE_MISSING|")) {
                Map<String, String> values = parseWarning(warning);
                String column = values.getOrDefault("name", "UNKNOWN");
                String path = resolveColumnPath(schema, column);
                String message = "Data type is missing for column " + column
                        + " in Word row " + values.getOrDefault("row", "?") + ".";
                target.add(new ValidationIssue("WARNING", "COLUMN_DATATYPE_MISSING", path, message));
            } else if (warning.startsWith("COLUMN_DESCRIPTION_MISSING|")) {
                Map<String, String> values = parseWarning(warning);
                String column = values.getOrDefault("name", "UNKNOWN");
                String path = resolveColumnPath(schema, column);
                String message = "Persian column name/description is missing for column " + column
                        + " in Word row " + values.getOrDefault("row", "?") + ".";
                target.add(new ValidationIssue("WARNING", "COLUMN_DESCRIPTION_MISSING", path, message));
            }
        }
    }

    private static String resolveColumnPath(DatabaseSchema schema, String columnName) {
        List<Table> matches = schema.tables().stream()
                .filter(table -> table.columns().stream()
                        .anyMatch(column -> column.name().normalized().equalsIgnoreCase(columnName)))
                .toList();
        if (matches.size() == 1) {
            return "tables." + matches.get(0).qualifiedName().name().value() + ".columns." + columnName;
        }
        return "columns." + columnName;
    }

    private static List<ValidationIssue> deduplicate(List<ValidationIssue> source) {
        Map<String, ValidationIssue> unique = new LinkedHashMap<>();
        for (ValidationIssue issue : source) {
            if (issue == null) continue;
            String key = normalize(issue.severity()) + "|" + normalize(issue.code()) + "|"
                    + normalize(issue.path()) + "|" + normalize(issue.message());
            unique.putIfAbsent(key, issue);
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<String, String> parseWarning(String warning) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : warning.split("\\|")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(part.substring(0, separator), part.substring(separator + 1));
            }
        }
        return values;
    }

    private static String metadata(Map<String, String> metadata, String key) {
        return metadata.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static int severityRank(String severity) {
        return switch (normalize(severity)) {
            case "ERROR" -> 0;
            case "WARNING" -> 1;
            case "INFO" -> 2;
            default -> 3;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
