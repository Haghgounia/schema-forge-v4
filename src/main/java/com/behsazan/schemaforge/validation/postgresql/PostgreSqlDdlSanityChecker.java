package com.behsazan.schemaforge.validation.postgresql;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs deterministic static checks on SchemaForge-generated PostgreSQL DDL.
 *
 * <p>The checker catches cross-dialect leakage, impossible explicit precisions,
 * excessive character lengths and malformed literals before a generated script
 * is sent to PostgreSQL. It complements, rather than replaces, execution against
 * the target database version.</p>
 */
public final class PostgreSqlDdlSanityChecker {
    private static final int MAX_NUMERIC_PRECISION = 1000;
    private static final int MAX_CHARACTER_LENGTH = 10_485_760;
    private static final int MAX_TEMPORAL_PRECISION = 6;
    private static final int MAX_REPORTED_ISSUES = 50;

    private static final Pattern NUMERIC = Pattern.compile(
            "(?i)\\b(?:NUMERIC|DECIMAL)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*([+-]?\\d+)\\s*)?\\)");
    private static final Pattern CHARACTER = Pattern.compile(
            "(?i)\\b(?:VARCHAR|CHAR|CHARACTER)\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern TEMPORAL = Pattern.compile(
            "(?i)\\b(?:TIMESTAMP|TIME)\\s*\\(\\s*(\\d+)\\s*\\)");

    private static final List<ForbiddenToken> FORBIDDEN = List.of(
            new ForbiddenToken("ORACLE_VARCHAR2", Pattern.compile("\\bN?VARCHAR2\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_NUMBER", Pattern.compile("\\bNUMBER\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_ENABLE", Pattern.compile("\\bENABLE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_NOORDER", Pattern.compile("\\bNOORDER\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_PROMPT", Pattern.compile("(?m)^\\s*PROMPT\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("SQLSERVER_IDENTITY", Pattern.compile("\\bIDENTITY\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("SQLSERVER_EXTENDED_PROPERTY", Pattern.compile("\\bSP_ADDEXTENDEDPROPERTY\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("SQLSERVER_WITH_CHECK_ADD", Pattern.compile("\\bWITH\\s+CHECK\\s+ADD\\s+CONSTRAINT\\b", Pattern.CASE_INSENSITIVE))
    );

    private final SqlScriptStatementParser parser = new SqlScriptStatementParser();

    public List<Issue> inspect(String script) {
        Objects.requireNonNull(script, "script must not be null");
        List<String> statements = parser.parse(script, DatabasePlatform.POSTGRESQL);
        List<Issue> issues = new ArrayList<>();
        if (statements.isEmpty()) {
            issues.add(new Issue(0, "POSTGRESQL_NO_STATEMENTS", "No executable PostgreSQL statement was found.", ""));
            return List.copyOf(issues);
        }

        for (int index = 0; index < statements.size(); index++) {
            int statementNumber = index + 1;
            String sql = stripComments(statements.get(index)).trim();
            if (sql.isEmpty()) {
                continue;
            }
            String structuralSql = maskSingleQuotedLiterals(sql);
            inspectStatementKind(sql, statementNumber, issues);
            inspectBalancedDelimiters(sql, statementNumber, issues);
            inspectNumeric(structuralSql, statementNumber, issues);
            inspectCharacterLength(structuralSql, statementNumber, issues);
            inspectTemporalPrecision(structuralSql, statementNumber, issues);
            inspectForbiddenTokens(structuralSql, statementNumber, issues);
        }
        return List.copyOf(issues);
    }

    public void requireValid(String script, String source) {
        List<Issue> issues = inspect(script);
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("PostgreSQL DDL sanity check failed");
        if (source != null && !source.isBlank()) {
            message.append(" for ").append(source);
        }
        message.append(" with ").append(issues.size()).append(" issue(s):");
        issues.stream().limit(MAX_REPORTED_ISSUES).forEach(issue -> message
                .append(System.lineSeparator())
                .append("statement ").append(issue.statementNumber())
                .append(" | ").append(issue.code())
                .append(" | ").append(issue.message())
                .append(issue.fragment().isBlank() ? "" : " | " + issue.fragment()));
        if (issues.size() > MAX_REPORTED_ISSUES) {
            message.append(System.lineSeparator())
                    .append("... ").append(issues.size() - MAX_REPORTED_ISSUES)
                    .append(" additional issue(s)");
        }
        throw new IllegalStateException(message.toString());
    }

    private void inspectStatementKind(String sql, int statementNumber, List<Issue> issues) {
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        boolean supported = normalized.startsWith("CREATE SCHEMA ")
                || normalized.startsWith("CREATE TABLE ")
                || normalized.startsWith("CREATE SEQUENCE ")
                || normalized.startsWith("CREATE INDEX ")
                || normalized.startsWith("CREATE UNIQUE INDEX ")
                || normalized.startsWith("ALTER TABLE ")
                || normalized.startsWith("COMMENT ON TABLE ")
                || normalized.startsWith("COMMENT ON COLUMN ")
                || normalized.startsWith("GRANT ");
        if (!supported) {
            issues.add(new Issue(statementNumber, "POSTGRESQL_UNEXPECTED_STATEMENT",
                    "Unexpected statement type.", firstLine(sql)));
        }
    }

    private void inspectNumeric(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = NUMERIC.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            if (precision < 1 || precision > MAX_NUMERIC_PRECISION) {
                issues.add(new Issue(statementNumber, "POSTGRESQL_NUMERIC_PRECISION",
                        "NUMERIC precision must be between 1 and 1000 when explicitly declared.", matcher.group()));
            }
            if (matcher.group(2) != null) {
                int scale = Integer.parseInt(matcher.group(2));
                if (scale < -1000 || scale > 1000) {
                    issues.add(new Issue(statementNumber, "POSTGRESQL_NUMERIC_SCALE",
                            "NUMERIC scale is outside the conservative PostgreSQL range -1000..1000.", matcher.group()));
                }
            }
        }
    }

    private void inspectCharacterLength(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = CHARACTER.matcher(sql);
        while (matcher.find()) {
            int length = Integer.parseInt(matcher.group(1));
            if (length < 1 || length > MAX_CHARACTER_LENGTH) {
                issues.add(new Issue(statementNumber, "POSTGRESQL_CHARACTER_LENGTH",
                        "Character length must be between 1 and 10485760 when explicitly declared.", matcher.group()));
            }
        }
    }

    private void inspectTemporalPrecision(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = TEMPORAL.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            if (precision < 0 || precision > MAX_TEMPORAL_PRECISION) {
                issues.add(new Issue(statementNumber, "POSTGRESQL_TEMPORAL_PRECISION",
                        "TIMESTAMP/TIME fractional-seconds precision must be between 0 and 6.", matcher.group()));
            }
        }
    }

    private void inspectForbiddenTokens(String sql, int statementNumber, List<Issue> issues) {
        for (ForbiddenToken forbidden : FORBIDDEN) {
            if (forbidden.pattern().matcher(sql).find()) {
                issues.add(new Issue(statementNumber, forbidden.code(),
                        "Non-PostgreSQL syntax detected.", firstLine(sql)));
            }
        }
    }

    private void inspectBalancedDelimiters(String sql, int statementNumber, List<Issue> issues) {
        int parentheses = 0;
        boolean quoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '\'' && !doubleQuoted) {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (ch == '"' && !quoted) {
                if (doubleQuoted && index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    index++;
                    continue;
                }
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (quoted || doubleQuoted) {
                continue;
            }
            if (ch == '(') parentheses++;
            if (ch == ')') parentheses--;
            if (parentheses < 0) break;
        }
        if (quoted) {
            issues.add(new Issue(statementNumber, "POSTGRESQL_UNBALANCED_STRING_LITERAL",
                    "Single-quoted string literal is not closed.", firstLine(sql)));
        }
        if (doubleQuoted) {
            issues.add(new Issue(statementNumber, "POSTGRESQL_UNBALANCED_IDENTIFIER",
                    "Double-quoted identifier is not closed.", firstLine(sql)));
        }
        if (parentheses != 0) {
            issues.add(new Issue(statementNumber, "POSTGRESQL_UNBALANCED_PARENTHESES",
                    "Parentheses are not balanced.", firstLine(sql)));
        }
    }

    private String maskSingleQuotedLiterals(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            if (ch == '\'' && quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                masked.append(' ').append(' ');
                index++;
                continue;
            }
            if (ch == '\'') {
                quoted = !quoted;
                masked.append(' ');
                continue;
            }
            masked.append(quoted ? ' ' : ch);
        }
        return masked.toString();
    }

    private String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)--.*$", "");
    }

    private String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        String value = newline < 0 ? sql : sql.substring(0, newline);
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    public record Issue(int statementNumber, String code, String message, String fragment) {
        public Issue {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            fragment = fragment == null ? "" : fragment;
        }
    }

    private record ForbiddenToken(String code, Pattern pattern) { }
}
