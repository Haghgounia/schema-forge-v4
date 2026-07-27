package com.behsazan.schemaforge.validation.db2zos;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs deterministic static checks on SchemaForge-generated Db2 for z/OS DDL.
 * This validator does not claim to replace preparation by a real Db2 subsystem.
 */
public final class Db2ZosOfflineDdlValidator {
    private static final Pattern EXACT_NUMERIC = Pattern.compile(
            "\\b(?:DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+([^\\s(]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "\\bPRIMARY\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_UNIQUE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+([^\\s]+).*?\\bUNIQUE\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNIQUE_INDEX = Pattern.compile(
            "^CREATE\\s+UNIQUE\\s+INDEX\\s+[^\\s]+\\s+ON\\s+([^\\s(]+)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final List<ForbiddenToken> FORBIDDEN = List.of(
            new ForbiddenToken("ORACLE_VARCHAR2", Pattern.compile("\\bN?VARCHAR2\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_NUMBER", Pattern.compile("\\bNUMBER\\s*\\(", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_SEQUENCE_CACHE", Pattern.compile("\\bNOCACHE\\b|\\bNOCYCLE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_TABLESPACE", Pattern.compile("\\bTABLESPACE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_ENABLE", Pattern.compile("\\bENABLE\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("ORACLE_CLIENT_COMMAND", Pattern.compile("\\bPROMPT\\b|\\bSET\\s+DEFINE\\b|\\bWHENEVER\\s+SQLERROR\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("POSTGRES_CLIENT_COMMAND", Pattern.compile("(?m)^\\s*\\\\(?:set|ir)\\b", Pattern.CASE_INSENSITIVE)),
            new ForbiddenToken("POSTGRES_CAST", Pattern.compile("::")),
            new ForbiddenToken("UNSUPPORTED_ON_UPDATE", Pattern.compile("\\bON\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE)));

    private final SqlScriptStatementParser parser;

    public Db2ZosOfflineDdlValidator() {
        this(new SqlScriptStatementParser());
    }

    Db2ZosOfflineDdlValidator(SqlScriptStatementParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
    }

    public Db2ZosOfflineValidationResult validate(String script) {
        Objects.requireNonNull(script, "script must not be null");
        List<String> statements = parser.parse(script, DatabasePlatform.DB2_ZOS);
        List<Db2ZosOfflineValidationIssue> issues = new ArrayList<>();
        Set<String> requiredConstraintIndexes = new LinkedHashSet<>();
        Set<String> actualUniqueIndexes = new LinkedHashSet<>();

        if (statements.isEmpty()) {
            issues.add(error("NO_STATEMENTS", 0, "No executable Db2 statement was found."));
        }

        for (int index = 0; index < statements.size(); index++) {
            int statementNumber = index + 1;
            String sql = stripComments(statements.get(index)).trim();
            if (sql.isEmpty()) continue;

            validateStatementKind(sql, statementNumber, issues);
            validateBalancedDelimiters(sql, statementNumber, issues);
            validateExactNumeric(sql, statementNumber, issues);
            validateForbiddenTokens(sql, statementNumber, issues);
            collectConstraintIndexRequirements(sql, requiredConstraintIndexes, actualUniqueIndexes);
        }

        for (String required : requiredConstraintIndexes) {
            if (!actualUniqueIndexes.contains(required)) {
                issues.add(error("REQUIRED_ENFORCING_INDEX_MISSING", 0,
                        "Primary or unique constraint lacks an explicit matching unique index: " + required));
            }
        }

        boolean valid = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new Db2ZosOfflineValidationResult(valid, statements.size(), issues);
    }

    private void validateStatementKind(
            String sql,
            int statementNumber,
            List<Db2ZosOfflineValidationIssue> issues) {
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        boolean supported = normalized.startsWith("CREATE TABLE ")
                || normalized.startsWith("CREATE SEQUENCE ")
                || normalized.startsWith("CREATE INDEX ")
                || normalized.startsWith("CREATE UNIQUE INDEX ")
                || normalized.startsWith("ALTER TABLE ")
                || normalized.startsWith("COMMENT ON TABLE ")
                || normalized.startsWith("COMMENT ON COLUMN ")
                || normalized.startsWith("GRANT ");
        if (!supported) {
            issues.add(error("UNEXPECTED_STATEMENT", statementNumber,
                    "Unexpected statement type: " + firstLine(sql)));
        }
    }

    private void validateBalancedDelimiters(
            String sql,
            int statementNumber,
            List<Db2ZosOfflineValidationIssue> issues) {
        int parentheses = 0;
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
            if (parentheses < 0) break;
        }
        if (quoted) {
            issues.add(error("UNBALANCED_STRING_LITERAL", statementNumber,
                    "Single-quoted string literal is not closed."));
        }
        if (parentheses != 0) {
            issues.add(error("UNBALANCED_PARENTHESES", statementNumber,
                    "Parentheses are not balanced."));
        }
    }

    private void validateExactNumeric(
            String sql,
            int statementNumber,
            List<Db2ZosOfflineValidationIssue> issues) {
        Matcher matcher = EXACT_NUMERIC.matcher(sql);
        while (matcher.find()) {
            int precision = Integer.parseInt(matcher.group(1));
            int scale = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            if (precision < 1 || precision > 31) {
                issues.add(error("DECIMAL_PRECISION_OUT_OF_RANGE", statementNumber,
                        "Db2 for z/OS DECIMAL precision must be between 1 and 31: " + matcher.group()));
            }
            if (scale < 0 || scale > precision) {
                issues.add(error("DECIMAL_SCALE_OUT_OF_RANGE", statementNumber,
                        "DECIMAL scale must be between 0 and precision: " + matcher.group()));
            }
        }
    }

    private void collectConstraintIndexRequirements(
            String sql,
            Set<String> required,
            Set<String> actual) {
        Matcher tableMatcher = CREATE_TABLE.matcher(sql);
        if (tableMatcher.find()) {
            String table = canonicalIdentifier(tableMatcher.group(1));
            Matcher primary = PRIMARY_KEY.matcher(sql);
            while (primary.find()) {
                required.add(table + "(" + canonicalColumns(primary.group(1)) + ")");
            }
        }

        Matcher uniqueConstraint = ALTER_UNIQUE.matcher(sql);
        if (uniqueConstraint.find()) {
            required.add(canonicalIdentifier(uniqueConstraint.group(1))
                    + "(" + canonicalColumns(uniqueConstraint.group(2)) + ")");
        }

        Matcher uniqueIndex = UNIQUE_INDEX.matcher(sql);
        if (uniqueIndex.find()) {
            actual.add(canonicalIdentifier(uniqueIndex.group(1))
                    + "(" + canonicalColumns(uniqueIndex.group(2)) + ")");
        }
    }

    private String canonicalIdentifier(String value) {
        return value.replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    private String canonicalColumns(String value) {
        return value.replace("\"", "")
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private void validateForbiddenTokens(
            String sql,
            int statementNumber,
            List<Db2ZosOfflineValidationIssue> issues) {
        for (ForbiddenToken forbidden : FORBIDDEN) {
            if (forbidden.pattern().matcher(sql).find()) {
                issues.add(error(forbidden.code(), statementNumber,
                        "Non-Db2 syntax detected in statement: " + firstLine(sql)));
            }
        }
    }

    private String stripComments(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean quoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (lineComment) {
                if (ch == '\n' || ch == '\r') {
                    lineComment = false;
                    result.append(ch);
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!quoted && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!quoted && ch == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (ch == '\'' && quoted && next == '\'') {
                result.append(ch).append(next);
                i++;
                continue;
            }
            if (ch == '\'') quoted = !quoted;
            result.append(ch);
        }
        return result.toString();
    }

    private String firstLine(String sql) {
        String line = sql.lines().findFirst().orElse(sql).trim();
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    private Db2ZosOfflineValidationIssue error(String code, int statementNumber, String message) {
        return new Db2ZosOfflineValidationIssue("ERROR", code, statementNumber, message);
    }

    private record ForbiddenToken(String code, Pattern pattern) {
    }
}
