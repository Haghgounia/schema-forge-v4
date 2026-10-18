package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ColumnPhysicalOptionKeys;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.specification.validation.spelling.NoOpSpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellCheckService;
import com.behsazan.schemaforge.specification.validation.spelling.SpellingError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
            if ("true".equalsIgnoreCase(column.physicalOptions().get(
                    ColumnPhysicalOptionKeys.RECOVERY_UNRESOLVED_CHARACTER_LENGTH))) {
                issues.add(error(
                        "COLUMN_CHARACTER_LENGTH_UNRESOLVED",
                        path,
                        "Character data type is known but its source length is unresolved; executable DDL must not be generated until an exact length is provided."));
            }
            if (column.identity() && !numericIdentityType(column)) {
                issues.add(error(
                        "NON_NUMERIC_IDENTITY_COLUMN",
                        path,
                        "Identity is explicitly attached to non-numeric datatype "
                                + column.dataType().name().value()
                                + "; SchemaForge will not guess or coerce identity semantics."));
            }
            addSpellingIssues(column.name().value(), path, issues);
        }
        table.primaryKey().ifPresent(pk -> {
            if (pk.columns().isEmpty()) issues.add(error("PRIMARY_KEY_EMPTY", tablePath + ".primaryKey", "Primary key has no columns."));
        });
        for (CheckConstraint check : table.checkConstraints()) {
            validateCheckExpression(check, tablePath, issues);
        }
    }

    private static boolean numericIdentityType(Column column) {
        String type = column.dataType().name().normalized().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INTEGER", "INT", "BIGINT",
                    "NUMBER", "NUMERIC", "DECIMAL", "DEC", "DECFLOAT",
                    "FLOAT", "REAL", "DOUBLE", "BINARY_FLOAT", "BINARY_DOUBLE" -> true;
            default -> false;
        };
    }

    private static void validateCheckExpression(
            CheckConstraint check, String tablePath, List<ValidationIssue> issues) {
        String expression = check.expression();
        DelimiterState state = delimiterState(expression);
        String checkName = check.name() == null ? "<unnamed>" : check.name().value();
        String path = tablePath + ".checks." + checkName;
        if (state.unterminatedStringLiteral()) {
            issues.add(error(
                    "CHECK_EXPRESSION_UNTERMINATED_STRING_LITERAL",
                    path,
                    "CHECK expression contains an unterminated single-quoted string literal; SQL generation must be blocked until the source expression is corrected."));
        }
        if (state.parenthesisDepth() != 0 || state.closedBelowZero()) {
            issues.add(error(
                    "CHECK_EXPRESSION_UNBALANCED_PARENTHESES",
                    path,
                    "CHECK expression contains unbalanced parentheses; SQL generation must be blocked until the source expression is corrected."));
        }
    }

    /**
     * Conservative delimiter scan used only for fail-closed structural validation.
     * It ignores delimiters inside quoted literals/identifiers and SQL comments.
     */
    private static DelimiterState delimiterState(String expression) {
        int depth = 0;
        boolean belowZero = false;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean bracketQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = 0; index < expression.length(); index++) {
            char ch = expression.charAt(index);
            char next = index + 1 < expression.length() ? expression.charAt(index + 1) : '\0';

            if (lineComment) {
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (singleQuoted) {
                if (ch == '\'' && next == '\'') {
                    index++;
                    continue;
                }
                if (ch == '\'') singleQuoted = false;
                continue;
            }
            if (doubleQuoted) {
                if (ch == '"' && next == '"') {
                    index++;
                    continue;
                }
                if (ch == '"') doubleQuoted = false;
                continue;
            }
            if (backtickQuoted) {
                if (ch == '`' && next == '`') {
                    index++;
                    continue;
                }
                if (ch == '`') backtickQuoted = false;
                continue;
            }
            if (bracketQuoted) {
                if (ch == ']' && next == ']') {
                    index++;
                    continue;
                }
                if (ch == ']') bracketQuoted = false;
                continue;
            }

            if (ch == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (ch == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (ch == '\'') {
                singleQuoted = true;
                continue;
            }
            if (ch == '"') {
                doubleQuoted = true;
                continue;
            }
            if (ch == '`') {
                backtickQuoted = true;
                continue;
            }
            if (ch == '[') {
                bracketQuoted = true;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth < 0) belowZero = true;
            }
        }
        return new DelimiterState(depth, belowZero, singleQuoted);
    }

    private record DelimiterState(
            int parenthesisDepth, boolean closedBelowZero, boolean unterminatedStringLiteral) { }

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
