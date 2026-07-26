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

        for (int i = 0; i < sanitized.length(); i++) {
            char ch = sanitized.charAt(i);
            if (ch == '\'' && singleQuoted && i + 1 < sanitized.length() && sanitized.charAt(i + 1) == '\'') {
                current.append(ch).append(ch);
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
