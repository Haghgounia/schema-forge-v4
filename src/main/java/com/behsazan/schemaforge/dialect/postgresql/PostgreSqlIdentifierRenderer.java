package com.behsazan.schemaforge.dialect.postgresql;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders canonical identifiers according to PostgreSQL folding and keyword rules.
 * Safe, ordinary identifiers are emitted unquoted in lower case. Reserved words and
 * identifiers containing PostgreSQL-unsafe characters are double quoted.
 */
public final class PostgreSqlIdentifierRenderer {
    private static final Pattern SAFE_UNQUOTED = Pattern.compile("[a-z_][a-z0-9_$]*");

    // PostgreSQL reserved keywords most likely to occur in logical data models.
    // Quoting a non-reserved identifier is valid, therefore this conservative set is safe.
    private static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "collation",
            "column", "concurrently", "constraint", "create", "cross", "current_catalog",
            "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end",
            "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant",
            "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "natural", "not", "notnull", "null", "offset", "on", "only", "or",
            "order", "outer", "overlaps", "placing", "primary", "references", "returning", "right",
            "select", "session_user", "similar", "some", "symmetric", "table", "tablesample", "then",
            "to", "trailing", "true", "union", "unique", "user", "using", "variadic", "verbose",
            "when", "where", "window", "with"
    );

    public String render(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        String normalized = identifier.normalized().toLowerCase(Locale.ROOT);
        if (SAFE_UNQUOTED.matcher(normalized).matches() && !RESERVED.contains(normalized)) {
            return normalized;
        }
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    public boolean requiresQuoting(Identifier identifier) {
        String rendered = render(identifier);
        return rendered.length() >= 2 && rendered.charAt(0) == '"';
    }
}
