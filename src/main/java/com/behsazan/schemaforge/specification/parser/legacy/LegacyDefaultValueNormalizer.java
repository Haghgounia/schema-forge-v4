package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.valueobject.DataType;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes legacy Word default-value cells before they enter the canonical model.
 *
 * <p>Old specifications frequently place the executable default and its human-readable
 * explanation in the same cell, for example {@code 0 1- permanent 2- temporary}. This
 * component extracts only the executable expression. Values that cannot be reduced to a
 * conservative SQL expression are dropped and reported by the caller instead of being
 * emitted as invalid DDL.</p>
 */
public final class LegacyDefaultValueNormalizer {
    private static final Pattern LEADING_DEFAULT_LABEL = Pattern.compile(
            "(?iu)^(?:(?:WITH\\s+)?DEFAULT(?:\\s+VALUE)?|(?:مقدار\\s*)?پیش\\s*فرض)\\s*[:=]?\\s*");
    private static final Pattern TRAILING_NULLABILITY = Pattern.compile(
            "(?iu)\\s+NOT\\s+NULL\\s*$");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?$");
    private static final Pattern NUMERIC_PREFIX = Pattern.compile(
            "^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?)(.*)$");
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)^[A-Z_$#][A-Z0-9_$#]*(?:\\.[A-Z_$#][A-Z0-9_$#]*){0,1}\\.NEXTVAL$");
    private static final Pattern FUNCTION_EXPRESSION = Pattern.compile(
            "(?is)^[A-Z_$#][A-Z0-9_$#]*(?:\\.[A-Z_$#][A-Z0-9_$#]*)*\\s*\\(.*\\)$");
    private static final Pattern ARABIC_SCRIPT = Pattern.compile("\\p{InArabic}");
    private static final Pattern SAFE_ASCII_EXPRESSION = Pattern.compile(
            "^[A-Za-z0-9_$#.,()+\\-*/'\" :]+$");
    private static final Pattern TYPE_DECLARATION_DEFAULT = Pattern.compile(
            "(?i)^(?:NUMBER|NUMERIC|DECIMAL|VARCHAR2?|NVARCHAR2?|CHAR|NCHAR|DATE|TIMESTAMP|RAW)\\s*\\(.*\\)$");
    private static final Pattern SIGNED_QUOTED_LITERAL = Pattern.compile(
            "^[+-]\\s*N?'.*'$", Pattern.DOTALL);
    private static final Set<String> TEMPORAL_KEYWORDS = Set.of(
            "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP");


    public Result normalize(String rawValue, DataType dataType) {
        String raw = TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(rawValue));
        if (raw.isBlank()) {
            return Result.empty();
        }

        String value = normalizeQuotes(raw);
        value = stripTrailingSemicolon(value);
        value = LEADING_DEFAULT_LABEL.matcher(value).replaceFirst("").trim();
        value = stripLeadingAssignment(value);
        value = TRAILING_NULLABILITY.matcher(value).replaceFirst("").trim();
        if (value.isBlank()) {
            return Result.dropped(raw, "EMPTY_AFTER_NORMALIZATION");
        }

        Result temporal = normalizeTemporalPhrase(raw, value, dataType);
        if (temporal != null) {
            return temporal;
        }

        String keyword = normalizeKeyword(value, dataType);
        if (keyword != null) {
            return accept(raw, keyword, reason(raw, keyword, "KEYWORD_NORMALIZED"), dataType);
        }

        if (NUMERIC_LITERAL.matcher(value).matches()) {
            return accept(raw, value, reason(raw, value, "TRIMMED"), dataType);
        }

        Matcher numericPrefix = NUMERIC_PREFIX.matcher(value);
        if (numericPrefix.matches() && !numericPrefix.group(1).isBlank()) {
            String remainder = numericPrefix.group(2);
            if (isTrailingAnnotation(remainder)) {
                String expression = numericPrefix.group(1);
                return accept(raw, expression, "TRAILING_ANNOTATION_REMOVED", dataType);
            }
        }

        int quotedEnd = quotedLiteralEnd(value);
        if (quotedEnd > 0) {
            String expression = value.substring(0, quotedEnd).trim();
            String remainder = value.substring(quotedEnd).trim();
            if (remainder.isEmpty()) {
                return accept(raw, expression, reason(raw, expression, "QUOTE_NORMALIZED"), dataType);
            }
            if (isTrailingAnnotation(remainder)) {
                return accept(raw, expression, "TRAILING_ANNOTATION_REMOVED", dataType);
            }
        }

        String upper = value.toUpperCase(Locale.ROOT);
        if (SEQUENCE_NEXTVAL.matcher(upper).matches()) {
            return accept(raw, value, reason(raw, value, "TRIMMED"), dataType);
        }

        if (FUNCTION_EXPRESSION.matcher(upper).matches()
                && isSafeAsciiExpression(value)
                && isBalanced(value)) {
            return accept(raw, value, reason(raw, value, "TRIMMED"), dataType);
        }

        if (isSafeAsciiExpression(value) && isBalanced(value) && containsOperator(value)) {
            return accept(raw, value, reason(raw, value, "TRIMMED"), dataType);
        }

        return Result.dropped(raw, unsafeReason(value));
    }

    private Result accept(String raw, String expression, String acceptedReason, DataType dataType) {
        String incompatibility = incompatibilityReason(expression, dataType);
        return incompatibility == null
                ? Result.of(raw, expression, acceptedReason)
                : Result.dropped(raw, incompatibility);
    }

    private String incompatibilityReason(String expression, DataType dataType) {
        if (dataType == null || expression == null || expression.isBlank()) {
            return null;
        }
        String typeName = dataType.name().normalized();
        String upper = expression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        if (TYPE_DECLARATION_DEFAULT.matcher(expression).matches()) {
            return "DATATYPE_DECLARATION_IN_DEFAULT";
        }
        if (SIGNED_QUOTED_LITERAL.matcher(expression).matches()) {
            return "MALFORMED_SIGNED_STRING_DEFAULT";
        }
        if (upper.equals("NULL")) {
            return null;
        }
        if (TEMPORAL_KEYWORDS.contains(upper)) {
            return isTemporal(typeName) ? null : "TEMPORAL_DEFAULT_INCOMPATIBLE_WITH_" + typeName;
        }
        if (SEQUENCE_NEXTVAL.matcher(expression).matches()) {
            return isNumeric(typeName) ? null : "SEQUENCE_DEFAULT_INCOMPATIBLE_WITH_" + typeName;
        }
        if (NUMERIC_LITERAL.matcher(expression).matches()) {
            if (isTemporal(typeName)) {
                return "NUMERIC_DEFAULT_INCOMPATIBLE_WITH_" + typeName;
            }
            return isNumeric(typeName) && !fitsNumber(dataType, expression)
                    ? "NUMERIC_DEFAULT_EXCEEDS_DECLARED_PRECISION"
                    : null;
        }

        String literal = quotedLiteral(expression);
        if (literal != null) {
            if (isNumeric(typeName)) {
                String trimmed = literal.trim();
                if (trimmed.isEmpty() || !NUMERIC_LITERAL.matcher(trimmed).matches()) {
                    return "STRING_DEFAULT_INCOMPATIBLE_WITH_" + typeName;
                }
                return fitsNumber(dataType, trimmed)
                        ? null
                        : "NUMERIC_DEFAULT_EXCEEDS_DECLARED_PRECISION";
            }
            if (isCharacter(typeName) && dataType.length() != null
                    && literal.length() > dataType.length()) {
                return "STRING_DEFAULT_EXCEEDS_DECLARED_LENGTH";
            }
        }
        if ((upper.startsWith("TO_DATE(") || upper.startsWith("TO_TIMESTAMP("))
                && !isTemporal(typeName)) {
            return "TEMPORAL_FUNCTION_INCOMPATIBLE_WITH_" + typeName;
        }
        return null;
    }

    private boolean fitsNumber(DataType dataType, String expression) {
        if (dataType.precision() == null) {
            return true;
        }
        try {
            BigDecimal value = new BigDecimal(expression).stripTrailingZeros();
            int precision = Math.max(1, value.precision());
            int rawScale = value.scale();
            int fractionalDigits = Math.max(0, rawScale);
            int declaredScale = dataType.scale() == null ? 0 : Math.max(0, dataType.scale());
            int integerDigits = Math.max(0, precision - rawScale);
            int allowedIntegerDigits = Math.max(0, dataType.precision() - declaredScale);
            return integerDigits <= allowedIntegerDigits && fractionalDigits <= declaredScale;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String quotedLiteral(String expression) {
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

    private boolean isNumeric(String type) {
        return type.equals("NUMBER") || type.equals("NUMERIC") || type.equals("DECIMAL")
                || type.equals("INT") || type.equals("INTEGER") || type.equals("BIGINT")
                || type.equals("SMALLINT") || type.equals("FLOAT") || type.equals("DOUBLE")
                || type.equals("REAL");
    }

    private boolean isTemporal(String type) {
        return type.equals("DATE") || type.startsWith("TIMESTAMP") || type.equals("DATETIME")
                || type.equals("TIME");
    }

    private boolean isCharacter(String type) {
        return type.equals("VARCHAR") || type.equals("VARCHAR2") || type.equals("NVARCHAR")
                || type.equals("NVARCHAR2") || type.equals("CHAR") || type.equals("NCHAR")
                || type.equals("CLOB") || type.equals("NCLOB");
    }

    private Result normalizeTemporalPhrase(String raw, String value, DataType dataType) {
        String compact = value.replaceAll("[\\s_\\-]+", "");
        String typeName = dataType == null ? "" : dataType.name().normalized();
        boolean timestamp = typeName.startsWith("TIMESTAMP");
        boolean date = typeName.equals("DATE");

        if (compact.startsWith("زمانجاری") || compact.startsWith("زمانفعلی")
                || compact.startsWith("زمانسیستم")) {
            return timestamp
                    ? Result.of(raw, "CURRENT_TIMESTAMP", "PERSIAN_CURRENT_TIME_NORMALIZED")
                    : Result.dropped(raw, "CURRENT_TIME_INCOMPATIBLE_WITH_" + typeName);
        }
        if (compact.startsWith("تاریخجاری") || compact.startsWith("تاریخفعلی")
                || compact.startsWith("تاریخسیستم")) {
            return date
                    ? Result.of(raw, "SYSDATE", "PERSIAN_CURRENT_DATE_NORMALIZED")
                    : Result.dropped(raw, "CURRENT_DATE_INCOMPATIBLE_WITH_" + typeName);
        }
        return null;
    }

    private String normalizeKeyword(String value, DataType dataType) {
        String upper = value.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String typeName = dataType == null ? "" : dataType.name().normalized();
        if ((upper.equals("CURRENT") || upper.equals("CURRENTTIMESTAMP"))
                && typeName.startsWith("TIMESTAMP")) {
            return "CURRENT_TIMESTAMP";
        }
        return switch (upper) {
            case "صفر" -> "0";
            case "CURRENT TIMESTAMP", "CURRENT_TIME_STAMP", "CURRENTTIME_STAMP" -> "CURRENT_TIMESTAMP";
            case "CURRENT DATE" -> "CURRENT_DATE";
            case "NULL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
                    "LOCALTIMESTAMP", "USER", "CURRENT_USER", "SYS_GUID()" -> upper;
            default -> null;
        };
    }

    private boolean isTrailingAnnotation(String remainder) {
        if (remainder == null || remainder.isBlank()) {
            return false;
        }
        String value = remainder.trim();
        if (value.startsWith("||") || value.startsWith("+") || value.startsWith("*")
                || value.startsWith("/") || value.startsWith("(") || value.startsWith(".")) {
            return false;
        }
        if (value.startsWith("-") && value.matches("-\\s*\\d+(?:\\.\\d+)?")) {
            return false;
        }
        return Character.isWhitespace(remainder.charAt(0))
                || ARABIC_SCRIPT.matcher(value).find()
                || value.matches("^[=:,،;].*")
                || value.matches("^-\\s*[^0-9].*")
                || value.matches("^[A-Za-z_$#].*");
    }

    private int quotedLiteralEnd(String value) {
        int start = 0;
        if (value.length() >= 2 && (value.charAt(0) == 'N' || value.charAt(0) == 'n')
                && value.charAt(1) == '\'') {
            start = 1;
        }
        if (start >= value.length() || value.charAt(start) != '\'') {
            return -1;
        }
        for (int i = start + 1; i < value.length(); i++) {
            if (value.charAt(i) != '\'') {
                continue;
            }
            if (i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            return i + 1;
        }
        return -1;
    }

    private String normalizeQuotes(String value) {
        return value
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201B', '\'')
                .replace('\u02BC', '\'')
                .replace('\u2032', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"');
    }

    private String stripTrailingSemicolon(String value) {
        String normalized = value.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String stripLeadingAssignment(String value) {
        String normalized = value.trim();
        while (normalized.startsWith("=") || normalized.startsWith(":")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private boolean isSafeAsciiExpression(String value) {
        return !ARABIC_SCRIPT.matcher(value).find()
                && SAFE_ASCII_EXPRESSION.matcher(value).matches()
                && !value.contains("--")
                && !value.contains("/*")
                && !value.contains("*/")
                && !value.contains(";");
    }

    private boolean isBalanced(String value) {
        int parentheses = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (ch == '(') {
                parentheses++;
            } else if (ch == ')') {
                parentheses--;
                if (parentheses < 0) {
                    return false;
                }
            }
        }
        return !quoted && parentheses == 0;
    }

    private boolean containsOperator(String value) {
        return value.indexOf('+') >= 0 || value.indexOf('-') >= 0 || value.indexOf('*') >= 0
                || value.indexOf('/') >= 0 || value.contains("||");
    }

    private String unsafeReason(String value) {
        if (ARABIC_SCRIPT.matcher(value).find()) {
            return "UNSAFE_NATURAL_LANGUAGE_DEFAULT";
        }
        if (!isBalanced(value)) {
            return "UNBALANCED_DEFAULT_EXPRESSION";
        }
        if (value.startsWith(")") || value.startsWith(",") || value.startsWith("،")
                || value.startsWith(":")) {
            return "INVALID_LEADING_TOKEN";
        }
        return "UNRECOGNIZED_DEFAULT_EXPRESSION";
    }

    private String reason(String raw, String normalized, String changedReason) {
        return Objects.equals(raw, normalized) ? "UNCHANGED" : changedReason;
    }

    public record Result(String expression, String rawValue, String reason) {
        static Result empty() {
            return new Result(null, null, "EMPTY");
        }

        static Result of(String rawValue, String expression, String reason) {
            return new Result(expression, rawValue, reason);
        }

        static Result dropped(String rawValue, String reason) {
            return new Result(null, rawValue, reason);
        }

        public boolean present() {
            return expression != null && !expression.isBlank();
        }

        public boolean changed() {
            return rawValue != null && !Objects.equals(rawValue, expression);
        }

        public boolean dropped() {
            return rawValue != null && !rawValue.isBlank() && !present();
        }
    }
}
