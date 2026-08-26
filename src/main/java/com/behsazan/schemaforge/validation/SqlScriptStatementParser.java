package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Splits SchemaForge-generated SQL into JDBC-executable statements. */
public final class SqlScriptStatementParser {

    public List<String> parse(String script, DatabasePlatform platform) {
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(platform, "platform must not be null");

        String sanitized = removeClientCommands(script, platform);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < sanitized.length(); i++) {
            char ch = sanitized.charAt(i);
            char next = i + 1 < sanitized.length() ? sanitized.charAt(i + 1) : '\0';

            if (lineComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    lineComment = false;
                }
                continue;
            }

            if (blockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }

            if (!singleQuoted && ch == '-' && next == '-') {
                current.append(ch).append(next);
                i++;
                lineComment = true;
                continue;
            }

            if (!singleQuoted && ch == '/' && next == '*') {
                current.append(ch).append(next);
                i++;
                blockComment = true;
                continue;
            }

            if (ch == '\'' && singleQuoted && next == '\'') {
                current.append(ch).append(next);
                i++;
                continue;
            }
            if (ch == '\'') {
                singleQuoted = !singleQuoted;
                current.append(ch);
                continue;
            }
            if (ch == ';' && !singleQuoted) {
                addStatement(statements, current);
                continue;
            }
            current.append(ch);
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private String removeClientCommands(String script, DatabasePlatform platform) {
        StringBuilder result = new StringBuilder();
        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            boolean skip = switch (platform) {
                case ORACLE -> trimmed.regionMatches(true, 0, "PROMPT", 0, "PROMPT".length())
                        || trimmed.regionMatches(true, 0, "SET DEFINE", 0, "SET DEFINE".length())
                        || trimmed.regionMatches(true, 0, "WHENEVER SQLERROR", 0, "WHENEVER SQLERROR".length())
                        || trimmed.equals("/");
                case POSTGRESQL -> trimmed.startsWith("\\");
                case DB2_ZOS, DB2_LUW -> false;
                case SQLSERVER -> trimmed.startsWith(":") || trimmed.equalsIgnoreCase("GO");
                case MYSQL -> false;
            };
            if (!skip) {
                result.append(line).append(System.lineSeparator());
            }
        }
        return result.toString();
    }

    private void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isBlank() && !onlyComments(statement)) {
            statements.add(statement);
        }
    }

    private boolean onlyComments(String value) {
        String withoutBlock = value.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder withoutLines = new StringBuilder();
        for (String line : withoutBlock.lines().toList()) {
            if (!line.trim().startsWith("--")) {
                withoutLines.append(line);
            }
        }
        return withoutLines.toString().isBlank();
    }
}
