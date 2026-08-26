package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.validation.spelling.NoOpSpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellingError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Structural and optional spelling validation; it never connects to a database. */
public final class SpecificationValidator {
    private final SpellCheckService spellCheckService;

    public SpecificationValidator() {
        this(new NoOpSpellCheckService());
    }

    public SpecificationValidator(SpellCheckService spellCheckService) {
        this.spellCheckService = spellCheckService;
    }

    public ValidationReport validate(DatabaseSchema schema) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (schema.tables().isEmpty()) {
            issues.add(error("SCHEMA_NO_TABLE", "schema", "No table was extracted from the Word file."));
        }
        for (Table table : schema.tables()) validateTable(table, issues);
        return new ValidationReport(issues.stream().noneMatch(i -> "ERROR".equals(i.severity())), issues);
    }

    private void validateTable(Table table, List<ValidationIssue> issues) {
        String tablePath = "tables." + table.qualifiedName().name().value();
        addSpellingIssues(table.qualifiedName().name().value(), tablePath, issues);
        if (table.columns().isEmpty()) {
            issues.add(error("TABLE_NO_COLUMN", tablePath, "Table has no columns."));
            return;
        }
        Set<String> names = new HashSet<>();
        for (Column column : table.columns()) {
            String name = column.name().normalized();
            String path = tablePath + ".columns." + column.name().value();
            if (!names.add(name)) issues.add(error("DUPLICATE_COLUMN", path, "Duplicate column name."));
            if ("MISSING_DATA_TYPE".equalsIgnoreCase(column.dataType().name().normalized())) {
                issues.add(error(
                        "COLUMN_DATATYPE_UNRESOLVED",
                        path,
                        "Column data type is unresolved; executable DDL must not be generated until the source specification provides an exact type."));
            }
            addSpellingIssues(column.name().value(), path, issues);
        }
        table.primaryKey().ifPresent(pk -> {
            if (pk.columns().isEmpty()) issues.add(error("PRIMARY_KEY_EMPTY", tablePath + ".primaryKey", "Primary key has no columns."));
        });
    }

    private void addSpellingIssues(String identifier, String path, List<ValidationIssue> issues) {
        for (SpellingError error : spellCheckService.check(identifier)) {
            if (error.serviceFailure()) {
                issues.add(new ValidationIssue(
                        "WARNING",
                        "SPELL_CHECK_UNAVAILABLE",
                        "spell-check",
                        error.message() + ". SQL generation continued because fail-open is enabled."));
                continue;
            }

            String suggestions = error.suggestions().stream()
                    .map(s -> s.value())
                    .filter(v -> !v.isBlank())
                    .collect(Collectors.joining(", "));
            String message = "Possible spelling error: " + error.word()
                    + (error.message().isBlank() ? "" : ". " + error.message())
                    + (suggestions.isBlank() ? "" : ". Suggestions: " + suggestions)
                    + ". Original identifier is preserved.";
            issues.add(new ValidationIssue("WARNING", "SPELLING_WARNING", path, message));
        }
    }

    private static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue("ERROR", code, path, message);
    }
}
