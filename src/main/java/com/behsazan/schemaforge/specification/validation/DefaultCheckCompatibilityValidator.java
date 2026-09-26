package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservatively checks simple literal defaults against simple CHECK IN domains.
 *
 * <p>The validator intentionally handles only an exact predicate of the form
 * {@code COLUMN IN (literal, ...)} (with optional outer parentheses). Compound
 * boolean expressions and non-literal defaults are left for DBMS-specific/runtime
 * validation rather than guessed.</p>
 *
 * @since 4.1
 */
public final class DefaultCheckCompatibilityValidator {

    public List<ValidationIssue> validate(DatabaseSchema schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        for (Table table : schema.tables()) {
            for (Column column : table.columns()) {
                if (!column.defaultValue().isPresent()) {
                    continue;
                }
                Optional<String> defaultLiteral = canonicalLiteral(column.defaultValue().expression());
                if (defaultLiteral.isEmpty()) {
                    continue;
                }
                for (CheckConstraint check : table.checkConstraints()) {
                    Optional<List<String>> allowed = exactInDomain(check.expression(), column.name().value());
                    if (allowed.isEmpty() || allowed.get().contains(defaultLiteral.get())) {
                        continue;
                    }
                    issues.add(new ValidationIssue(
                            "WARNING",
                            "DEFAULT_CHECK_INCOMPATIBLE",
                            columnPath(table, column),
                            "Default " + column.defaultValue().expression()
                                    + " is outside the literal domain enforced by CHECK "
                                    + check.name().value() + "."));
                }
            }
        }
        return List.copyOf(issues);
    }

    private static Optional<List<String>> exactInDomain(String expression, String columnName) {
        String normalized = stripOuterParentheses(expression == null ? "" : expression.trim());
        String identifier = "(?:\\\"" + Pattern.quote(columnName) + "\\\"|" + Pattern.quote(columnName) + ")";
        Pattern pattern = Pattern.compile(
                "(?is)^\\s*" + identifier + "\\s+IN\\s*\\((.*)\\)\\s*$",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        List<String> tokens = splitSqlList(matcher.group(1));
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (String token : tokens) {
            Optional<String> literal = canonicalLiteral(token);
            if (literal.isEmpty()) {
                return Optional.empty();
            }
            values.add(literal.get());
        }
        return Optional.of(List.copyOf(values));
    }

    private static String stripOuterParentheses(String value) {
        String result = value;
        while (result.startsWith("(") && result.endsWith(")") && enclosesWholeExpression(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean enclosesWholeExpression(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            if (ch == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && i < value.length() - 1) {
                return false;
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0 && !quoted;
    }

    private static List<String> splitSqlList(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                current.append(ch).append(value.charAt(++i));
                continue;
            }
            if (ch == '\'') {
                quoted = !quoted;
                current.append(ch);
                continue;
            }
            if (ch == ',' && !quoted) {
                String token = current.toString().trim();
                if (token.isEmpty()) return List.of();
                result.add(token);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted) return List.of();
        String token = current.toString().trim();
        if (token.isEmpty()) return List.of();
        result.add(token);
        return result;
    }

    private static Optional<String> canonicalLiteral(String expression) {
        if (expression == null) return Optional.empty();
        String value = expression.trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            String content = value.substring(1, value.length() - 1).replace("''", "'");
            return Optional.of("S:" + content);
        }
        if (value.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
            try {
                return Optional.of("N:" + new BigDecimal(value).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        String keyword = value.toUpperCase(Locale.ROOT);
        if (keyword.equals("TRUE") || keyword.equals("FALSE")) {
            return Optional.of("K:" + keyword);
        }
        return Optional.empty();
    }

    private static String columnPath(Table table, Column column) {
        return "tables." + table.qualifiedName().name().value() + ".columns." + column.name().value();
    }
}
