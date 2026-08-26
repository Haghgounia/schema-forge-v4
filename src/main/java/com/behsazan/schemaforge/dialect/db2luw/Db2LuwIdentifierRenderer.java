package com.behsazan.schemaforge.dialect.db2luw;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders canonical identifiers using a conservative Db2 LUW ordinary-identifier subset. */
public final class Db2LuwIdentifierRenderer {
    private static final Pattern SAFE_ORDINARY = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<String> RESERVED = Set.of(
            "ADD", "ALL", "ALTER", "AND", "AS", "AUTHORIZATION", "BEGIN", "BETWEEN", "BUFFERPOOL", "BY",
            "CASCADE", "CASE", "CHAR", "CHARACTER", "CHECK", "COLUMN", "COMMENT", "COMMIT", "CONSTRAINT",
            "CREATE", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DATABASE",
            "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXCEPT", "EXISTS", "FETCH", "FOR",
            "FOREIGN", "FROM", "FULL", "GENERATED", "GRANT", "GROUP", "HAVING", "IDENTITY", "IN", "INCLUDE",
            "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LONG",
            "NEXT", "NO", "NOT", "NULL", "OF", "ON", "ONLY", "OR", "ORDER", "OUTER", "PARTITION", "PRIMARY",
            "REFERENCES", "RESTRICT", "REVOKE", "RIGHT", "ROLLBACK", "ROW", "SCHEMA", "SELECT", "SEQUENCE",
            "SET", "SOME", "TABLE", "TABLESPACE", "THEN", "TO", "TRIGGER", "UNION", "UNIQUE", "UPDATE", "USER",
            "USING", "VALUE", "VALUES", "VIEW", "WHEN", "WHERE", "WITH"
    );

    public String render(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        String normalized = identifier.normalized().toUpperCase(Locale.ROOT);
        if (SAFE_ORDINARY.matcher(normalized).matches() && !RESERVED.contains(normalized)) {
            return normalized;
        }
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }
}
