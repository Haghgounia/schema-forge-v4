package com.behsazan.schemaforge.dialect.db2zos;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders canonical identifiers according to Db2 for z/OS ordinary-identifier rules. */
public final class Db2ZosIdentifierRenderer {
    private static final Pattern SAFE_ORDINARY = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * Conservative subset of Db2 for z/OS reserved words that commonly collide
     * with logical table and column names. Delimiting a non-reserved word is valid,
     * so conservative quoting is safer than emitting ambiguous SQL.
     */
    private static final Set<String> RESERVED = Set.of(
            "ADD", "AFTER", "ALL", "ALLOCATE", "ALLOW", "ALTER", "AND", "ANY", "AS", "AT",
            "AUDIT", "AUX", "AUXILIARY", "BEFORE", "BEGIN", "BETWEEN", "BUFFERPOOL", "BY",
            "CALL", "CASCADE", "CASE", "CAST", "CCSID", "CHAR", "CHARACTER", "CHECK", "CLUSTER",
            "COLUMN", "COMMENT", "COMMIT", "CONSTRAINT", "CREATE", "CURRENT", "CURRENT_DATE",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DATABASE", "DEFAULT", "DELETE",
            "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXCEPT", "EXISTS", "FETCH", "FOR",
            "FOREIGN", "FROM", "FULL", "GENERATED", "GRANT", "GROUP", "HAVING", "IDENTITY",
            "IN", "INCLUDE", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN",
            "KEY", "LEFT", "LIKE", "LOCK", "LONG", "MATERIALIZED", "NEXT", "NEXTVAL", "NO",
            "NOT", "NULL", "NULLS", "OF", "ON", "ONLY", "OR", "ORDER", "OUTER", "PARTITION",
            "PRIMARY", "REFERENCES", "RENAME", "RESTRICT", "REVOKE", "RIGHT", "ROLLBACK", "ROW",
            "SCHEMA", "SELECT", "SEQUENCE", "SESSION_USER", "SET", "SOME", "STOGROUP", "TABLE",
            "TABLESPACE", "THEN", "TO", "TRIGGER", "UNION", "UNIQUE", "UPDATE", "USER", "USING",
            "VALUE", "VALUES", "VIEW", "WHEN", "WHERE", "WITH"
    );

    public String render(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        String normalized = identifier.normalized().toUpperCase(Locale.ROOT);
        if (SAFE_ORDINARY.matcher(normalized).matches() && !RESERVED.contains(normalized)) {
            return normalized;
        }
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    public boolean requiresQuoting(Identifier identifier) {
        String rendered = render(identifier);
        return rendered.length() >= 2 && rendered.charAt(0) == '"';
    }
}
