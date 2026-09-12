package com.behsazan.schemaforge.validation.mariadb;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.mariadb.MariaDbTypeMapper;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline sanity checker for the SchemaForge-generated MariaDB DDL subset.
 *
 * <p>This is intentionally not a SQL parser and does not claim server execution coverage.
 * It blocks known cross-DBMS leakage, target-limit violations and statement shapes that the
 * MariaDB dialect must never emit before M8 live validation is introduced.</p>
 */
public final class MariaDbDdlSanityChecker {
    private static final int MAX_REPORTED_ISSUES = 20;
    private static final int MAX_IDENTIFIER_LENGTH = 64;
    private static final int MAX_UTF8MB4_VARCHAR_CHARACTERS = (65_535 - 2) / 4;

    private static final Pattern DECIMAL = Pattern.compile(
            "(?i)\\b(?:DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(-?\\d+)\\s*)?\\)");
    private static final Pattern TEMPORAL = Pattern.compile(
            "(?i)\\b(?:DATETIME|TIMESTAMP|TIME)\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern VARCHAR = Pattern.compile(
            "(?i)\\bVARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern FIXED_CHAR = Pattern.compile(
            "(?i)\\b(?:NCHAR|CHAR|CHARACTER)\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern BACKTICK_IDENTIFIER = Pattern.compile("`((?:``|[^`])*)`");
    private static final Pattern SCHEMA_QUALIFIED_INDEX_NAME = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+"
                    + "(?:`(?:``|[^`])+`|[A-Za-z][A-Za-z0-9_$#]*)\\s*\\.");
    private static final Pattern PARTIAL_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*\\bWHERE\\b");
    private static final Pattern EXPRESSION_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*\\bON\\b[^;]*\\(\\s*\\(");

    private static final List<ForbiddenToken> FORBIDDEN = List.of(
            forbidden("MARIADB_CREATE_TABLESPACE", "\\bCREATE\\s+TABLESPACE\\b"),
            forbidden("MARIADB_ORACLE_VARCHAR2", "\\bVARCHAR2\\b"),
            forbidden("MARIADB_ORACLE_NVARCHAR2", "\\bNVARCHAR2\\b"),
            forbidden("MARIADB_ORACLE_NUMBER", "\\bNUMBER\\s*\\("),
            forbidden("MARIADB_ORACLE_CLOB", "\\bNC?LOB\\b"),
            forbidden("MARIADB_ORACLE_SYSDATE", "\\bSYSDATE\\b"),
            forbidden("MARIADB_ORACLE_SYSTIMESTAMP", "\\bSYSTIMESTAMP\\b"),
            forbidden("MARIADB_ORACLE_NEXTVAL", "\\.\\s*NEXTVAL\\b"),
            forbidden("MARIADB_ORACLE_USING_INDEX", "\\bUSING\\s+INDEX\\b"),
            forbidden("MARIADB_POSTGRES_SERIAL", "\\b(?:BIGSERIAL|SMALLSERIAL|SERIAL)\\b"),
            forbidden("MARIADB_POSTGRES_TIMESTAMPTZ", "\\bTIMESTAMPTZ\\b"),
            forbidden("MARIADB_POSTGRES_CONCURRENTLY", "\\bCONCURRENTLY\\b"),
            forbidden("MARIADB_INDEX_INCLUDE", "\\bINCLUDE\\s*\\("),
            forbidden("MARIADB_DEFERRABLE", "\\bDEFERRABLE\\b"),
            forbidden("MARIADB_INITIALLY_DEFERRED", "\\bINITIALLY\\s+(?:DEFERRED|IMMEDIATE)\\b"),
            forbidden("MARIADB_REFERENTIAL_SET_DEFAULT", "\\bSET\\s+DEFAULT\\b"),
            forbidden("MARIADB_SQLSERVER_WITH_CHECK_ADD", "\\bWITH\\s+CHECK\\s+ADD\\s+CONSTRAINT\\b"),
            forbidden("MARIADB_SQLSERVER_EXTENDED_PROPERTY", "\\bSP_ADDEXTENDEDPROPERTY\\b"),
            forbidden("MARIADB_SQLSERVER_BRACKET_OBJECT", "\\b(?:CREATE|ALTER|GRANT)\\b[^;]*\\[[^]]+\\]"),
            forbidden("MARIADB_SEQUENCE_NO_CACHE", "\\bNO\\s+CACHE\\b"),
            forbidden("MARIADB_SEQUENCE_NO_CYCLE", "\\bNO\\s+CYCLE\\b"),
            forbidden("MARIADB_INDEX_NULLS_ORDER", "\\bNULLS\\s+(?:FIRST|LAST)\\b")
    );

    private final SqlScriptStatementParser parser = new SqlScriptStatementParser();

    public List<Issue> inspect(String script) {
        Objects.requireNonNull(script, "script must not be null");
        List<String> statements = parser.parse(script, DatabasePlatform.MARIADB);
        List<Issue> issues = new ArrayList<>();
        if (statements.isEmpty()) {
            issues.add(new Issue(0, "MARIADB_NO_STATEMENTS",
                    "No executable MariaDB statement was found.", ""));
            return List.copyOf(issues);
        }

        for (int index = 0; index < statements.size(); index++) {
            int statementNumber = index + 1;
            String sql = stripComments(statements.get(index)).trim();
            if (sql.isEmpty()) {
                continue;
            }
            String structuralSql = maskSingleQuotedLiterals(sql);
            String tokenScanSql = maskBacktickIdentifiers(structuralSql);
            inspectStatementKind(sql, statementNumber, issues);
            inspectBalancedDelimiters(sql, statementNumber, issues);
            inspectIdentifiers(structuralSql, statementNumber, issues);
            inspectDecimal(structuralSql, statementNumber, issues);
            inspectTemporalPrecision(structuralSql, statementNumber, issues);
            inspectVarcharLength(structuralSql, statementNumber, issues);
            inspectFixedCharLength(structuralSql, statementNumber, issues);
            inspectIndexShape(structuralSql, statementNumber, issues);
            inspectForbiddenTokens(tokenScanSql, statementNumber, issues);
        }
        return List.copyOf(issues);
    }

    public void requireValid(String script, String source) {
        List<Issue> issues = inspect(script);
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("MariaDB DDL sanity check failed");
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


    private String maskBacktickIdentifiers(String sql) {
        StringBuilder masked = new StringBuilder(sql);
        Matcher matcher = BACKTICK_IDENTIFIER.matcher(sql);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                masked.setCharAt(i, ' ');
            }
        }
        return masked.toString();
    }

    private void inspectStatementKind(String sql, int statementNumber, List<Issue> issues) {
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        boolean supported = normalized.startsWith("CREATE DATABASE ")
                || normalized.startsWith("CREATE TABLE ")
                || normalized.startsWith("CREATE SEQUENCE ")
                || normalized.startsWith("CREATE INDEX ")
                || normalized.startsWith("CREATE UNIQUE INDEX ")
                || normalized.startsWith("ALTER TABLE ")
                || normalized.startsWith("GRANT ");
        if (!supported) {
            issues.add(new Issue(statementNumber, "MARIADB_UNEXPECTED_STATEMENT",
                    "Unexpected statement type in the SchemaForge MariaDB DDL subset.", firstLine(sql)));
        }
    }

    private void inspectIdentifiers(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = BACKTICK_IDENTIFIER.matcher(sql);
        while (matcher.find()) {
            String identifier = matcher.group(1).replace("``", "`");
            if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
                issues.add(new Issue(statementNumber, "MARIADB_IDENTIFIER_LENGTH",
                        "MariaDB identifier exceeds 64 characters.", identifier));
            }
        }
    }

    private void inspectDecimal(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = DECIMAL.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            if (precision < 1 || precision > MariaDbTypeMapper.MAX_DECIMAL_PRECISION) {
                issues.add(new Issue(statementNumber, "MARIADB_DECIMAL_PRECISION",
                        "DECIMAL precision must be between 1 and "
                                + MariaDbTypeMapper.MAX_DECIMAL_PRECISION + ".", matcher.group()));
            }
            if (matcher.group(2) != null) {
                int scale = Integer.parseInt(matcher.group(2));
                if (scale < 0 || scale > MariaDbTypeMapper.MAX_DECIMAL_SCALE || scale > precision) {
                    issues.add(new Issue(statementNumber, "MARIADB_DECIMAL_SCALE",
                            "DECIMAL scale must be between 0 and min(38, precision).", matcher.group()));
                }
            }
        }
    }

    private void inspectTemporalPrecision(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = TEMPORAL.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            if (precision < 0 || precision > MariaDbTypeMapper.MAX_TEMPORAL_PRECISION) {
                issues.add(new Issue(statementNumber, "MARIADB_TEMPORAL_PRECISION",
                        "DATETIME/TIMESTAMP/TIME fractional-seconds precision must be between 0 and 6.",
                        matcher.group()));
            }
        }
    }

    private void inspectVarcharLength(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = VARCHAR.matcher(sql);
        while (matcher.find()) {
            int length = Integer.parseInt(matcher.group(1));
            if (length < 1 || length > MAX_UTF8MB4_VARCHAR_CHARACTERS) {
                issues.add(new Issue(statementNumber, "MARIADB_UTF8MB4_VARCHAR_LENGTH",
                        "SchemaForge MariaDB utf8mb4 VARCHAR length must be between 1 and "
                                + MAX_UTF8MB4_VARCHAR_CHARACTERS
                                + "; larger logical strings require the dialect's TEXT promotion policy.",
                        matcher.group()));
            }
        }
    }

    private void inspectFixedCharLength(String sql, int statementNumber, List<Issue> issues) {
        Matcher matcher = FIXED_CHAR.matcher(sql);
        while (matcher.find()) {
            int length = Integer.parseInt(matcher.group(1));
            if (length < 1 || length > MariaDbTypeMapper.MAX_FIXED_CHAR_LENGTH) {
                issues.add(new Issue(statementNumber, "MARIADB_FIXED_CHAR_LENGTH",
                        "MariaDB CHAR/NCHAR length must be between 1 and "
                                + MariaDbTypeMapper.MAX_FIXED_CHAR_LENGTH
                                + "; a VARCHAR/TEXT replacement requires an explicit portability policy "
                                + "because fixed-width padding semantics would change.",
                        matcher.group()));
            }
        }
    }

    private void inspectIndexShape(String sql, int statementNumber, List<Issue> issues) {
        if (SCHEMA_QUALIFIED_INDEX_NAME.matcher(sql).find()) {
            issues.add(new Issue(statementNumber, "MARIADB_SCHEMA_QUALIFIED_INDEX_NAME",
                    "MariaDB CREATE INDEX uses a table-scoped index name; qualify the table, not the index name.",
                    firstLine(sql)));
        }
        if (PARTIAL_INDEX.matcher(sql).find()) {
            issues.add(new Issue(statementNumber, "MARIADB_PARTIAL_INDEX",
                    "MariaDB does not support PostgreSQL-style partial-index WHERE predicates.", firstLine(sql)));
        }
        if (EXPRESSION_INDEX.matcher(sql).find()) {
            issues.add(new Issue(statementNumber, "MARIADB_DIRECT_EXPRESSION_INDEX",
                    "SchemaForge M4 does not emit direct expression indexes for MariaDB; use an explicit generated column.",
                    firstLine(sql)));
        }
    }

    private void inspectForbiddenTokens(String sql, int statementNumber, List<Issue> issues) {
        for (ForbiddenToken forbidden : FORBIDDEN) {
            if (forbidden.pattern().matcher(sql).find()) {
                issues.add(new Issue(statementNumber, forbidden.code(),
                        "Non-MariaDB or unsupported SchemaForge M4 syntax detected.", firstLine(sql)));
            }
        }
    }

    private void inspectBalancedDelimiters(String sql, int statementNumber, List<Issue> issues) {
        int parentheses = 0;
        boolean singleQuoted = false;
        boolean backtickQuoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char ch = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (singleQuoted) {
                if (ch == '\'' && next == '\'') {
                    index++;
                    continue;
                }
                if (ch == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (backtickQuoted) {
                if (ch == '`' && next == '`') {
                    index++;
                    continue;
                }
                if (ch == '`') {
                    backtickQuoted = false;
                }
                continue;
            }
            if (ch == '\'') {
                singleQuoted = true;
                continue;
            }
            if (ch == '`') {
                backtickQuoted = true;
                continue;
            }
            if (ch == '(') parentheses++;
            if (ch == ')') parentheses--;
            if (parentheses < 0) break;
        }
        if (singleQuoted) {
            issues.add(new Issue(statementNumber, "MARIADB_UNBALANCED_STRING_LITERAL",
                    "Single-quoted string literal is not closed.", firstLine(sql)));
        }
        if (backtickQuoted) {
            issues.add(new Issue(statementNumber, "MARIADB_UNBALANCED_IDENTIFIER",
                    "Backtick-quoted identifier is not closed.", firstLine(sql)));
        }
        if (parentheses != 0) {
            issues.add(new Issue(statementNumber, "MARIADB_UNBALANCED_PARENTHESES",
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

    private static ForbiddenToken forbidden(String code, String regex) {
        return new ForbiddenToken(code, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
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
