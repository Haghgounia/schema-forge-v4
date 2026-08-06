package com.behsazan.schemaforge.dialect.oracle;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Final Oracle-specific safety policy for column default expressions. */
final class OracleDefaultExpressionPolicy {
    private static final Pattern NUMERIC = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?$");
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)^[A-Z_$#][A-Z0-9_$#]*(?:\\.[A-Z_$#][A-Z0-9_$#]*){0,1}\\.NEXTVAL$");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?i)^(?:NUMBER|NUMERIC|DECIMAL|VARCHAR2?|NVARCHAR2?|CHAR|NCHAR|DATE|TIMESTAMP|RAW)\\s*\\(.*\\)$");
    private static final Pattern SIGNED_QUOTED = Pattern.compile("^[+-]\\s*N?'.*'$", Pattern.DOTALL);
    private static final Set<String> TEMPORAL_KEYWORDS = Set.of(
            "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP");

    private OracleDefaultExpressionPolicy() {
    }

    static Decision evaluate(Column column) {
        if (column == null || !column.defaultValue().isPresent()) {
            return Decision.rejected("EMPTY_DEFAULT");
        }
        String expression = column.defaultValue().expression().trim();
        String upper = expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        DataType type = column.dataType();
        String typeName = type.name().normalized();

        if (expression.isBlank()) {
            return Decision.rejected("EMPTY_DEFAULT");
        }
        if (TYPE_DECLARATION.matcher(expression).matches()) {
            return Decision.rejected("DATATYPE_DECLARATION_IN_DEFAULT");
        }
        if (SIGNED_QUOTED.matcher(expression).matches()) {
            return Decision.rejected("MALFORMED_SIGNED_STRING_DEFAULT");
        }
        if (upper.equals("NULL")) {
            return Decision.accepted("NULL");
        }
        if (TEMPORAL_KEYWORDS.contains(upper)) {
            return temporal(typeName)
                    ? Decision.accepted(upper)
                    : Decision.rejected("TEMPORAL_DEFAULT_INCOMPATIBLE_WITH_" + typeName);
        }
        if (SEQUENCE_NEXTVAL.matcher(expression).matches()) {
            return numeric(typeName)
                    ? Decision.accepted(expression)
                    : Decision.rejected("SEQUENCE_DEFAULT_INCOMPATIBLE_WITH_" + typeName);
        }
        if (NUMERIC.matcher(expression).matches()) {
            if (temporal(typeName)) {
                return Decision.rejected("NUMERIC_DEFAULT_INCOMPATIBLE_WITH_" + typeName);
            }
            if (numeric(typeName) && !fitsNumber(type, expression)) {
                return Decision.rejected("NUMERIC_DEFAULT_EXCEEDS_DECLARED_PRECISION");
            }
            return Decision.accepted(expression);
        }

        String literal = quotedLiteral(expression);
        if (literal != null) {
            if (numeric(typeName)) {
                String trimmed = literal.trim();
                if (trimmed.isEmpty() || !NUMERIC.matcher(trimmed).matches()) {
                    return Decision.rejected("STRING_DEFAULT_INCOMPATIBLE_WITH_" + typeName);
                }
                if (!fitsNumber(type, trimmed)) {
                    return Decision.rejected("NUMERIC_DEFAULT_EXCEEDS_DECLARED_PRECISION");
                }
                return Decision.accepted(trimmed);
            }
            if (character(typeName) && type.length() != null && literal.length() > type.length()) {
                return Decision.rejected("STRING_DEFAULT_EXCEEDS_DECLARED_LENGTH");
            }
            return Decision.accepted(expression);
        }

        if ((upper.equals("USER") || upper.equals("CURRENT_USER")) && character(typeName)) {
            return Decision.accepted(upper);
        }
        if (upper.equals("SYS_GUID()") && (character(typeName) || raw(typeName))) {
            return Decision.accepted(upper);
        }
        if (upper.startsWith("TO_DATE(") || upper.startsWith("TO_TIMESTAMP(")) {
            return temporal(typeName)
                    ? Decision.accepted(expression)
                    : Decision.rejected("TEMPORAL_FUNCTION_INCOMPATIBLE_WITH_" + typeName);
        }

        // Other balanced SQL expressions are retained. The final sanity checker still
        // rejects natural-language leakage, unknown bare identifiers and malformed tokens.
        return Decision.accepted(expression);
    }

    private static boolean fitsNumber(DataType type, String expression) {
        if (type.precision() == null) {
            return true;
        }
        try {
            BigDecimal value = new BigDecimal(expression).stripTrailingZeros();
            int precision = Math.max(1, value.precision());
            int rawScale = value.scale();
            int fractionalDigits = Math.max(0, rawScale);
            int declaredScale = type.scale() == null ? 0 : Math.max(0, type.scale());
            int integerDigits = Math.max(0, precision - rawScale);
            int allowedIntegerDigits = Math.max(0, type.precision() - declaredScale);
            return integerDigits <= allowedIntegerDigits && fractionalDigits <= declaredScale;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String quotedLiteral(String expression) {
        int start = 0;
        if (expression.length() >= 2
                && (expression.charAt(0) == 'N' || expression.charAt(0) == 'n')
                && expression.charAt(1) == '\'') {
            start = 1;
        }
        if (start >= expression.length() || expression.charAt(start) != '\'') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int index = start + 1; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current != '\'') {
                value.append(current);
                continue;
            }
            if (index + 1 < expression.length() && expression.charAt(index + 1) == '\'') {
                value.append('\'');
                index++;
                continue;
            }
            if (!expression.substring(index + 1).trim().isEmpty()) {
                return null;
            }
            return value.toString();
        }
        return null;
    }

    private static boolean numeric(String type) {
        return type.equals("NUMBER") || type.equals("NUMERIC") || type.equals("DECIMAL")
                || type.equals("INT") || type.equals("INTEGER") || type.equals("BIGINT")
                || type.equals("SMALLINT") || type.equals("FLOAT") || type.equals("DOUBLE")
                || type.equals("REAL");
    }

    private static boolean temporal(String type) {
        return type.equals("DATE") || type.startsWith("TIMESTAMP") || type.equals("DATETIME")
                || type.equals("TIME");
    }

    private static boolean character(String type) {
        return type.equals("VARCHAR") || type.equals("VARCHAR2") || type.equals("NVARCHAR")
                || type.equals("NVARCHAR2") || type.equals("CHAR") || type.equals("NCHAR")
                || type.equals("CLOB") || type.equals("NCLOB");
    }

    private static boolean raw(String type) {
        return type.equals("RAW") || type.equals("LONG_RAW") || type.equals("BLOB");
    }

    record Decision(String expression, String reason) {
        static Decision accepted(String expression) {
            return new Decision(expression, "ACCEPTED");
        }

        static Decision rejected(String reason) {
            return new Decision(null, reason);
        }

        boolean accepted() {
            return expression != null && !expression.isBlank();
        }
    }
}
