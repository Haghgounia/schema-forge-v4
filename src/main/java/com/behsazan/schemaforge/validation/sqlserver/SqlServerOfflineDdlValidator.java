package com.behsazan.schemaforge.validation.sqlserver;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs deterministic static checks on SchemaForge-generated Microsoft SQL Server DDL.
 * This is a safety net, not a replacement for compilation on a real SQL Server instance.
 *
 * @since 4.3
 */
public final class SqlServerOfflineDdlValidator {
    private static final Pattern EXACT_NUMERIC = Pattern.compile(
            "\\b(?:DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARACTER_LENGTH = Pattern.compile(
            "\\b(N?VARCHAR|N?CHAR|VARBINARY|BINARY)\\s*\\(\\s*(\\d+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPORAL_PRECISION = Pattern.compile(
            "\\b(?:DATETIME2|DATETIMEOFFSET|TIME)\\s*\\(\\s*(\\d+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final List<ForbiddenToken> FORBIDDEN = List.of(
            new ForbiddenToken("ORACLE_VARCHAR2", Pattern.compile("\\bN?VARCHAR2\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_NUMBER", Pattern.compile("\\bNUMBER\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_TABLESPACE", Pattern.compile("\\bTABLESPACE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_ENABLE", Pattern.compile("\\bENABLE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_CLIENT_COMMAND", Pattern.compile("\\bPROMPT\\b|\\bSET\\s+DEFINE\\b|\\bWHENEVER\\s+SQLERROR\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("POSTGRES_CLIENT_COMMAND", Pattern.compile("(?m)^\\s*\\\\(?:set|ir)\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("POSTGRES_CAST", Pattern.compile("::")),
            new ForbiddenToken("DB2_UNCOMMITTED_READ", Pattern.compile("\\bWITH\\s+UR\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("DB2_COMMENT", Pattern.compile("\\bCOMMENT\\s+ON\\s+(?:TABLE|COLUMN)\\b", Pattern.CASE_INSENSITIVE)));

    private final SqlScriptStatementParser parser;

    public SqlServerOfflineDdlValidator() {
        this(new SqlScriptStatementParser());
    }

    SqlServerOfflineDdlValidator(SqlScriptStatementParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
    }

    public SqlServerOfflineValidationResult validate(String script) {
        Objects.requireNonNull(script, "script must not be null");
        List<String> statements = parser.parse(script, DatabasePlatform.SQLSERVER);
        List<SqlServerOfflineValidationIssue> issues = new ArrayList<>();
        if (statements.isEmpty()) {
            issues.add(error("NO_STATEMENTS", 0, "No executable SQL Server statement was found."));
        }

        for (int index = 0; index < statements.size(); index++) {
            int statementNumber = index + 1;
            String sql = stripComments(statements.get(index)).trim();
            if (sql.isEmpty()) continue;
            validateStatementKind(sql, statementNumber, issues);
            validateBalancedDelimiters(sql, statementNumber, issues);
            validateExactNumeric(sql, statementNumber, issues);
            validateLengthLimits(sql, statementNumber, issues);
            validateTemporalPrecision(sql, statementNumber, issues);
            validateForbiddenTokens(sql, statementNumber, issues);
        }

        boolean valid = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new SqlServerOfflineValidationResult(valid, statements.size(), issues);
    }

    private void validateStatementKind(String sql, int statementNumber,
                                       List<SqlServerOfflineValidationIssue> issues) {
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        boolean supported = normalized.startsWith("SET XACT_ABORT ")
                || normalized.startsWith("SET NOCOUNT ")
                || normalized.startsWith("CREATE TABLE ")
                || normalized.startsWith("CREATE SEQUENCE ")
                || normalized.startsWith("CREATE INDEX ")
                || normalized.startsWith("CREATE UNIQUE INDEX ")
                || normalized.startsWith("ALTER TABLE ")
                || normalized.startsWith("EXEC SYS.SP_ADDEXTENDEDPROPERTY ")
                || normalized.startsWith("EXECUTE SYS.SP_ADDEXTENDEDPROPERTY ")
                || normalized.startsWith("GRANT ");
        if (!supported) {
            issues.add(error("UNEXPECTED_STATEMENT", statementNumber,
                    "Unexpected statement type: " + firstLine(sql)));
        }
    }

    private void validateExactNumeric(String sql, int statementNumber,
                                      List<SqlServerOfflineValidationIssue> issues) {
        Matcher matcher = EXACT_NUMERIC.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            int scale = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            if (precision < 1 || precision > 38) {
                issues.add(error("DECIMAL_PRECISION_OUT_OF_RANGE", statementNumber,
                        "SQL Server DECIMAL precision must be between 1 and 38: " + matcher.group()));
            }
            if (scale < 0 || scale > precision) {
                issues.add(error("DECIMAL_SCALE_OUT_OF_RANGE", statementNumber,
                        "DECIMAL scale must be between 0 and precision: " + matcher.group()));
            }
        }
    }

    private void validateLengthLimits(String sql, int statementNumber,
                                      List<SqlServerOfflineValidationIssue> issues) {
        Matcher matcher = CHARACTER_LENGTH.matcher(sql);
        while (matcher.find()) {
            String type = matcher.group(1).toUpperCase(Locale.ROOT);
            int length = Integer.parseInt(matcher.group(2));
            int maximum = type.startsWith("N") ? 4000 : 8000;
            if (length < 1 || length > maximum) {
                issues.add(error("TYPE_LENGTH_OUT_OF_RANGE", statementNumber,
                        type + " length must be between 1 and " + maximum
                                + " or use MAX: " + matcher.group()));
            }
        }
    }

    private void validateTemporalPrecision(String sql, int statementNumber,
                                           List<SqlServerOfflineValidationIssue> issues) {
        Matcher matcher = TEMPORAL_PRECISION.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            if (precision < 0 || precision > 7) {
                issues.add(error("TEMPORAL_PRECISION_OUT_OF_RANGE", statementNumber,
                        "SQL Server temporal precision must be between 0 and 7: " + matcher.group()));
            }
        }
    }

    private void validateForbiddenTokens(String sql, int statementNumber,
                                         List<SqlServerOfflineValidationIssue> issues) {
        for (ForbiddenToken forbidden : FORBIDDEN) {
            if (forbidden.pattern().matcher(sql).find()) {
                issues.add(error(forbidden.code(), statementNumber,
                        "Non-SQL Server syntax detected in statement: " + firstLine(sql)));
            }
        }
    }

    private void validateBalancedDelimiters(String sql, int statementNumber,
                                            List<SqlServerOfflineValidationIssue> issues) {
        int parentheses = 0;
        int brackets = 0;
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' && quoted && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                i++;
                continue;
            }
            if (ch == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) continue;
            if (ch == '(') parentheses++;
            if (ch == ')') parentheses--;
            if (ch == '[') brackets++;
            if (ch == ']') brackets--;
        }
        if (quoted) issues.add(error("UNBALANCED_STRING_LITERAL", statementNumber,
                "Single-quoted string literal is not closed."));
        if (parentheses != 0) issues.add(error("UNBALANCED_PARENTHESES", statementNumber,
                "Parentheses are not balanced."));
        if (brackets != 0) issues.add(error("UNBALANCED_BRACKETS", statementNumber,
                "Bracket-delimited identifier is not balanced."));
    }

    private String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)--.*$", "");
    }

    private String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        String value = newline < 0 ? sql : sql.substring(0, newline);
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private SqlServerOfflineValidationIssue error(String code, int statementNumber, String message) {
        return new SqlServerOfflineValidationIssue("ERROR", code, statementNumber, message);
    }

    private record ForbiddenToken(String code, Pattern pattern) { }
}
