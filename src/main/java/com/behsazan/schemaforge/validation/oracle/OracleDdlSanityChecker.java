package com.behsazan.schemaforge.validation.oracle;

import com.behsazan.schemaforge.dialect.oracle.OracleIdentifierPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Final defensive validation executed immediately before an Oracle SQL artifact is written.
 *
 * <p>The checker targets defects observed in the Legacy Word corpus: invalid precision,
 * Oracle reserved identifiers, over-sized character types, type-incompatible defaults,
 * leaked natural-language annotations and malformed expressions. It is intentionally
 * conservative and remains complementary to executing the generated script on Oracle.</p>
 */
public final class OracleDdlSanityChecker {
    private static final int MAX_NUMBER_PRECISION = 38;
    private static final int MAX_TIMESTAMP_PRECISION = 9;
    private static final int MAX_REPORTED_ISSUES = 50;

    private static final Pattern NUMBER = Pattern.compile(
            "(?i)\\bNUMBER\\s*\\(\\s*(\\d+)\\s*(?:,\\s*([+-]?\\d+)\\s*)?\\)");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?i)\\bTIMESTAMP\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern CREATE_TABLE_NAME = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+"
                    + "(?:(?:[A-Z][A-Z0-9_$#]*)\\.)?([A-Z][A-Z0-9_$#]*)");
    private static final Pattern COLUMN_NAME = Pattern.compile(
            "(?i)^\\s*(?:/\\*.*?\\*/\\s*)?([A-Z][A-Z0-9_$#]*)\\s+");
    private static final Pattern VARCHAR2_LENGTH = Pattern.compile(
            "(?i)\\bVARCHAR2?\\s*\\(\\s*(\\d+)");
    private static final Pattern NVARCHAR2_LENGTH = Pattern.compile(
            "(?i)\\bNVARCHAR2?\\s*\\(\\s*(\\d+)");
    private static final Pattern TIMESTAMP_NUMERIC_DEFAULT = Pattern.compile(
            "(?i)\\b(?:DATE|TIMESTAMP(?:\\s*\\(\\s*\\d+\\s*\\))?"
                    + "(?:\\s+WITH(?:\\s+LOCAL)?\\s+TIME\\s+ZONE)?)"
                    + "\\s+DEFAULT\\s+([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\b");
    private static final Pattern NUMBER_TEMPORAL_DEFAULT = Pattern.compile(
            "(?i)\\b(?:NUMBER|NUMERIC|DECIMAL)(?:\\s*\\([^)]*\\))?\\s+DEFAULT\\s+"
                    + "(SYSDATE|SYSTIMESTAMP|CURRENT_DATE|CURRENT_TIMESTAMP|LOCALTIMESTAMP)\\b");
    private static final Pattern NUMBER_LITERAL_DEFAULT = Pattern.compile(
            "(?i)\\b(?:NUMBER|NUMERIC|DECIMAL)\\s*\\(\\s*(\\d+)\\s*"
                    + "(?:,\\s*(\\d+)\\s*)?\\)\\s+DEFAULT\\s+"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?)\\b");
    private static final Pattern CHARACTER_LITERAL_DEFAULT = Pattern.compile(
            "(?i)\\b(?:VARCHAR2?|NVARCHAR2?|CHAR|NCHAR)\\s*\\(\\s*(\\d+)"
                    + "(?:\\s+(?:CHAR|BYTE))?\\s*\\)\\s+DEFAULT\\s+(N?'(?:''|[^'])*')");
    private static final Pattern MALFORMED_SIGNED_STRING_DEFAULT = Pattern.compile(
            "(?i)\\bDEFAULT\\s+[+-]\\s*N?'");
    private static final Pattern DATATYPE_DECLARATION_DEFAULT = Pattern.compile(
            "(?i)\\bDEFAULT\\s+(?:NUMBER|NUMERIC|DECIMAL|VARCHAR2?|NVARCHAR2?|CHAR|NCHAR|DATE|TIMESTAMP|RAW)\\s*\\(");
    private static final Pattern DEFAULT = Pattern.compile("(?i)\\bDEFAULT\\b");
    private static final Pattern ARABIC_SCRIPT = Pattern.compile("\\p{InArabic}");
    private static final Pattern NUMERIC_PREFIX_WITH_REMAINDER = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?\\s+.+$");
    private static final Pattern NUMERIC_REMAINDER = Pattern.compile(
            "^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[Ee][+-]?\\d+)?(\\s+.+)$");
    private static final Pattern BARE_IDENTIFIER = Pattern.compile(
            "(?i)^[A-Z_$#][A-Z0-9_$#]*(?:\\.[A-Z_$#][A-Z0-9_$#]*)*$");
    private static final Pattern SEQUENCE_NEXTVAL = Pattern.compile(
            "(?i)^[A-Z_$#][A-Z0-9_$#]*(?:\\.[A-Z_$#][A-Z0-9_$#]*){0,1}\\.NEXTVAL$");
    private static final Set<String> ALLOWED_BARE_DEFAULTS = Set.of(
            "NULL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "LOCALTIMESTAMP", "USER", "CURRENT_USER");

    public List<Issue> inspect(String sql) {
        Objects.requireNonNull(sql, "sql must not be null");
        List<Issue> issues = new ArrayList<>();

        // Phase-1 physical candidates are intentionally emitted inside /* ... */ blocks.
        // The safety gate must validate executable Oracle DDL only, while preserving line
        // numbers so diagnostics still point to the original generated artifact.
        String executableSql = stripBlockCommentsPreservingLines(sql);
        String[] lines = executableSql.split("\\R", -1);

        boolean inCreateTable = false;
        boolean tableBodyStarted = false;
        int parenthesisDepth = 0;

        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String codeLine = stripLineComment(lines[index]);
            String trimmed = codeLine.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);

            boolean createTableStart = upper.startsWith("CREATE TABLE ")
                    || upper.startsWith("CREATE GLOBAL TEMPORARY TABLE ");
            if (createTableStart) {
                inCreateTable = true;
                tableBodyStarted = false;
                parenthesisDepth = 0;
                inspectReservedIdentifier(codeLine, lineNumber, issues);
            }

            if (!inCreateTable) {
                continue;
            }

            int depthBefore = parenthesisDepth;
            boolean opensParenthesis = containsUnquoted(codeLine, '(');

            // Only the top-level CREATE TABLE body contains column declarations.
            // This avoids interpreting TABLESPACE, ALTER, COMMENT, GRANT, physical
            // options, or continuation lines of nested constraints as column names.
            if (!createTableStart && tableBodyStarted && depthBefore == 1 && !trimmed.isEmpty()) {
                inspectReservedIdentifier(codeLine, lineNumber, issues);
                inspectPrecision(codeLine, lineNumber, issues);
                inspectTypeAndDefaultCompatibility(codeLine, lineNumber, issues);
                inspectDefault(codeLine, lineNumber, issues);
            }

            if (opensParenthesis) {
                tableBodyStarted = true;
            }
            parenthesisDepth += parenthesisDelta(codeLine);

            if (tableBodyStarted && parenthesisDepth <= 0) {
                inCreateTable = false;
                tableBodyStarted = false;
                parenthesisDepth = 0;
            }
        }
        return List.copyOf(issues);
    }

    public void requireValid(String sql, String source) {
        List<Issue> issues = inspect(sql);
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Oracle DDL sanity check failed");
        if (source != null && !source.isBlank()) {
            message.append(" for ").append(source);
        }
        message.append(" with ").append(issues.size()).append(" issue(s):");
        issues.stream().limit(MAX_REPORTED_ISSUES).forEach(issue -> message
                .append(System.lineSeparator())
                .append("line ").append(issue.lineNumber())
                .append(" | ").append(issue.code())
                .append(" | ").append(issue.message())
                .append(" | ").append(issue.fragment()));
        if (issues.size() > MAX_REPORTED_ISSUES) {
            message.append(System.lineSeparator())
                    .append("... ").append(issues.size() - MAX_REPORTED_ISSUES)
                    .append(" additional issue(s)");
        }
        throw new IllegalStateException(message.toString());
    }

    private void inspectReservedIdentifier(String line, int lineNumber, List<Issue> issues) {
        Matcher table = CREATE_TABLE_NAME.matcher(line);
        if (table.find() && OracleIdentifierPolicy.isReserved(table.group(1))) {
            issues.add(new Issue(lineNumber, "ORACLE_RESERVED_TABLE_NAME",
                    "Oracle reserved word cannot be emitted as an unquoted table name", table.group(1)));
            return;
        }
        String trimmed = line.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || upper.startsWith("CONSTRAINT ") || upper.startsWith("CREATE TABLE")
                || upper.startsWith("CREATE GLOBAL TEMPORARY TABLE")
                || upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE")
                || upper.startsWith("FOREIGN KEY") || upper.startsWith("CHECK")
                || trimmed.startsWith("(") || trimmed.startsWith(")")) {
            return;
        }
        Matcher column = COLUMN_NAME.matcher(line);
        if (column.find() && OracleIdentifierPolicy.isReserved(column.group(1))) {
            issues.add(new Issue(lineNumber, "ORACLE_RESERVED_COLUMN_NAME",
                    "Oracle reserved word cannot be emitted as an unquoted column name", column.group(1)));
        }
    }

    private void inspectPrecision(String line, int lineNumber, List<Issue> issues) {
        Matcher number = NUMBER.matcher(line);
        while (number.find()) {
            int precision = Integer.parseInt(number.group(1));
            Integer scale = number.group(2) == null ? null : Integer.valueOf(number.group(2));
            if (precision < 1 || precision > MAX_NUMBER_PRECISION) {
                issues.add(new Issue(lineNumber, "ORACLE_NUMBER_PRECISION",
                        "NUMBER precision must be between 1 and 38", number.group()));
            }
            if (scale != null && (scale < -84 || scale > 127)) {
                issues.add(new Issue(lineNumber, "ORACLE_NUMBER_SCALE",
                        "NUMBER scale must be between -84 and 127", number.group()));
            }
        }

        Matcher timestamp = TIMESTAMP.matcher(line);
        while (timestamp.find()) {
            int precision = Integer.parseInt(timestamp.group(1));
            if (precision < 0 || precision > MAX_TIMESTAMP_PRECISION) {
                issues.add(new Issue(lineNumber, "ORACLE_TIMESTAMP_PRECISION",
                        "TIMESTAMP fractional-seconds precision must be between 0 and 9", timestamp.group()));
            }
        }
    }

    private void inspectTypeAndDefaultCompatibility(String line, int lineNumber, List<Issue> issues) {
        Matcher varchar = VARCHAR2_LENGTH.matcher(line);
        while (varchar.find()) {
            int length = Integer.parseInt(varchar.group(1));
            if (length > 4000) {
                issues.add(new Issue(lineNumber, "ORACLE_VARCHAR2_LENGTH",
                        "VARCHAR2 length exceeds the STANDARD 4000-byte limit; use CLOB or EXTENDED mode",
                        varchar.group()));
            }
        }
        Matcher nvarchar = NVARCHAR2_LENGTH.matcher(line);
        while (nvarchar.find()) {
            int length = Integer.parseInt(nvarchar.group(1));
            if (length > 2000) {
                issues.add(new Issue(lineNumber, "ORACLE_NVARCHAR2_LENGTH",
                        "NVARCHAR2 length exceeds the conservative 2000-character limit; use NCLOB",
                        nvarchar.group()));
            }
        }
        if (TIMESTAMP_NUMERIC_DEFAULT.matcher(line).find()) {
            issues.add(new Issue(lineNumber, "ORACLE_TEMPORAL_NUMERIC_DEFAULT",
                    "DATE/TIMESTAMP column cannot use an untyped numeric default", compact(line)));
        }
        if (NUMBER_TEMPORAL_DEFAULT.matcher(line).find()) {
            issues.add(new Issue(lineNumber, "ORACLE_NUMBER_TEMPORAL_DEFAULT",
                    "NUMBER column cannot use a date/timestamp keyword as default", compact(line)));
        }
        if (MALFORMED_SIGNED_STRING_DEFAULT.matcher(line).find()) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_SIGNED_STRING",
                    "DEFAULT contains an invalid sign before a quoted literal", compact(line)));
        }
        if (DATATYPE_DECLARATION_DEFAULT.matcher(line).find()) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_DATATYPE_DECLARATION",
                    "A datatype declaration was extracted into the DEFAULT cell", compact(line)));
        }

        Matcher number = NUMBER_LITERAL_DEFAULT.matcher(line);
        while (number.find()) {
            int precision = Integer.parseInt(number.group(1));
            int scale = number.group(2) == null ? 0 : Integer.parseInt(number.group(2));
            if (!fitsNumber(number.group(3), precision, scale)) {
                issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_EXCEEDS_NUMBER",
                        "Numeric default exceeds the declared NUMBER precision/scale", number.group()));
            }
        }

        Matcher character = CHARACTER_LITERAL_DEFAULT.matcher(line);
        while (character.find()) {
            int length = Integer.parseInt(character.group(1));
            String literal = character.group(2);
            int offset = literal.startsWith("N'") || literal.startsWith("n'") ? 2 : 1;
            String value = literal.substring(offset, literal.length() - 1).replace("''", "'");
            if (value.length() > length) {
                issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_EXCEEDS_LENGTH",
                        "String default exceeds the declared character length", character.group()));
            }
        }
    }

    private boolean fitsNumber(String expression, int precision, int scale) {
        try {
            BigDecimal value = new BigDecimal(expression).stripTrailingZeros();
            int actualPrecision = Math.max(1, value.precision());
            int rawScale = value.scale();
            int fractionalDigits = Math.max(0, rawScale);
            int integerDigits = Math.max(0, actualPrecision - rawScale);
            return integerDigits <= Math.max(0, precision - scale) && fractionalDigits <= scale;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void inspectDefault(String line, int lineNumber, List<Issue> issues) {
        Matcher matcher = DEFAULT.matcher(line);
        if (!matcher.find()) {
            return;
        }
        String expression = line.substring(matcher.end()).trim();
        if (expression.endsWith(",")) {
            expression = expression.substring(0, expression.length() - 1).trim();
        }
        String upper = expression.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" NOT NULL")) {
            expression = expression.substring(0, expression.length() - " NOT NULL".length()).trim();
            upper = expression.toUpperCase(Locale.ROOT);
        }

        if (expression.isBlank()) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_EMPTY",
                    "DEFAULT clause has no expression", compact(line)));
            return;
        }
        if (containsArabicOutsideQuotedLiteral(expression)) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_NATURAL_LANGUAGE",
                    "DEFAULT clause contains Persian/Arabic natural-language text", compact(expression)));
        }
        if (containsCurlyQuote(expression)) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_SMART_QUOTE",
                    "DEFAULT clause contains typographic quotes", compact(expression)));
        }
        if (expression.startsWith(")") || expression.startsWith(":")
                || expression.startsWith(",") || expression.startsWith("،")) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_INVALID_START",
                    "DEFAULT expression starts with an invalid token", compact(expression)));
        }
        if (upper.startsWith("WITH DEFAULT") || upper.equals("CURRENT TIMESTAMP")
                || upper.equals("CURRENT DATE")) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_NON_ORACLE_SYNTAX",
                    "DEFAULT expression contains non-Oracle legacy syntax", compact(expression)));
        }
        if (BARE_IDENTIFIER.matcher(expression).matches()
                && !ALLOWED_BARE_DEFAULTS.contains(upper)
                && !SEQUENCE_NEXTVAL.matcher(expression).matches()) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_UNKNOWN_IDENTIFIER",
                    "DEFAULT expression is an unknown bare identifier", compact(expression)));
        }
        if (NUMERIC_PREFIX_WITH_REMAINDER.matcher(expression).matches()
                && !isArithmeticRemainder(expression)) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_TRAILING_TEXT",
                    "Numeric DEFAULT contains trailing tokens", compact(expression)));
        }

        int quotedEnd = quotedLiteralEnd(expression);
        if (quotedEnd > 0 && !expression.substring(quotedEnd).trim().isEmpty()) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_TRAILING_TEXT",
                    "Quoted DEFAULT contains trailing tokens", compact(expression)));
        }
        if (expression.contains("--") || expression.contains("/*") || expression.contains("*/")
                || expression.contains(";")) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_FORBIDDEN_TOKEN",
                    "DEFAULT expression contains a comment or statement terminator", compact(expression)));
        }
        if (!balanced(expression)) {
            issues.add(new Issue(lineNumber, "ORACLE_DEFAULT_UNBALANCED",
                    "DEFAULT expression has unbalanced quotes or parentheses", compact(expression)));
        }
    }

    private boolean isArithmeticRemainder(String expression) {
        Matcher prefix = NUMERIC_REMAINDER.matcher(expression);
        if (!prefix.matches()) {
            return false;
        }
        String remainder = prefix.group(1).trim();
        if (remainder.startsWith("+") || remainder.startsWith("*")
                || remainder.startsWith("/") || remainder.startsWith("||")) {
            return true;
        }
        return remainder.matches("-\\s*\\d+(?:\\.\\d+)?(?:\\s*.*)?");
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
        for (int index = start + 1; index < value.length(); index++) {
            if (value.charAt(index) != '\'') {
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            return index + 1;
        }
        return -1;
    }

    private boolean balanced(String value) {
        boolean quoted = false;
        int parentheses = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '(') {
                parentheses++;
            } else if (character == ')') {
                parentheses--;
                if (parentheses < 0) {
                    return false;
                }
            }
        }
        return !quoted && parentheses == 0;
    }

    private String stripBlockCommentsPreservingLines(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean blockComment = false;
        boolean quoted = false;

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (blockComment) {
                if (character == '*' && index + 1 < value.length() && value.charAt(index + 1) == '/') {
                    result.append(' ').append(' ');
                    index++;
                    blockComment = false;
                } else if (character == '\r' || character == '\n') {
                    result.append(character);
                } else {
                    result.append(' ');
                }
                continue;
            }

            if (character == '\'' && quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                result.append(character).append(character);
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
                result.append(character);
                continue;
            }
            if (!quoted && character == '/' && index + 1 < value.length() && value.charAt(index + 1) == '*') {
                result.append(' ').append(' ');
                index++;
                blockComment = true;
                continue;
            }
            result.append(character);
        }
        return result.toString();
    }

    private int parenthesisDelta(String value) {
        boolean quoted = false;
        int delta = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'' && quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '(') {
                delta++;
            } else if (character == ')') {
                delta--;
            }
        }
        return delta;
    }

    private boolean containsUnquoted(String value, char expected) {
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'' && quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && character == expected) {
                return true;
            }
        }
        return false;
    }

    private String stripLineComment(String value) {
        boolean quoted = false;
        for (int index = 0; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (!quoted && character == '-' && value.charAt(index + 1) == '-') {
                return value.substring(0, index);
            }
        }
        return value;
    }

    private boolean containsArabicOutsideQuotedLiteral(String value) {
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (!quoted && ARABIC_SCRIPT.matcher(Character.toString(character)).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCurlyQuote(String value) {
        return value.indexOf('\u2018') >= 0 || value.indexOf('\u2019') >= 0
                || value.indexOf('\u201C') >= 0 || value.indexOf('\u201D') >= 0;
    }

    private String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    public record Issue(int lineNumber, String code, String message, String fragment) {
    }
}
