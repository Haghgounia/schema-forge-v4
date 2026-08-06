package com.behsazan.schemaforge.dialect.oracle;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Set;

/**
 * Produces deterministic Oracle-safe physical identifiers.
 *
 * <p>Legacy specifications contain column and table names such as {@code ROWID},
 * {@code USER}, {@code DESC} and {@code GROUP}. Oracle rejects these names when
 * emitted as ordinary unquoted identifiers. Quoting is not used because it makes
 * every reference case-sensitive and uppercase {@code "ROWID"} is still special.
 * The policy therefore applies the stable {@code SF_} prefix to an exact reserved
 * token. All DDL references pass through the same dialect renderer, so PK, FK,
 * index and comment statements remain consistent.</p>
 */
public final class OracleIdentifierPolicy {
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final Set<String> RESERVED = Set.of(
            "ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUDIT",
            "BETWEEN", "BY", "CHAR", "CHECK", "CLUSTER", "COLUMN", "COMMENT",
            "COMPRESS", "CONNECT", "CREATE", "CURRENT", "DATE", "DECIMAL", "DEFAULT",
            "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "EXCLUSIVE", "EXISTS",
            "FILE", "FLOAT", "FOR", "FROM", "GRANT", "GROUP", "HAVING", "IDENTIFIED",
            "IMMEDIATE", "IN", "INCREMENT", "INDEX", "INITIAL", "INSERT", "INTEGER",
            "INTERSECT", "INTO", "IS", "LEVEL", "LIKE", "LOCK", "LONG", "MAXEXTENTS",
            "MINUS", "MLSLABEL", "MODE", "MODIFY", "NOAUDIT", "NOCOMPRESS", "NOT",
            "NOWAIT", "NULL", "NUMBER", "OF", "OFFLINE", "ON", "ONLINE", "OPTION",
            "OR", "ORDER", "PCTFREE", "PRIOR", "PRIVILEGES", "PUBLIC", "RAW", "RENAME",
            "RESOURCE", "REVOKE", "ROW", "ROWID", "ROWNUM", "ROWS", "SELECT", "SESSION",
            "SET", "SHARE", "SIZE", "SMALLINT", "START", "SUCCESSFUL", "SYNONYM",
            "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER", "UID", "UNION", "UNIQUE",
            "UPDATE", "USER", "VALIDATE", "VALUES", "VARCHAR", "VARCHAR2", "VIEW",
            "WHENEVER", "WHERE", "WITH");

    private OracleIdentifierPolicy() {
    }

    public static String render(Identifier identifier) {
        String normalized = identifier.normalized();
        if (!isReserved(normalized)) {
            return normalized;
        }
        String safe = "SF_" + normalized;
        return safe.length() <= MAX_IDENTIFIER_LENGTH
                ? safe
                : safe.substring(0, MAX_IDENTIFIER_LENGTH);
    }

    public static boolean isReserved(String value) {
        return value != null && RESERVED.contains(value.trim().toUpperCase(Locale.ROOT));
    }
}
