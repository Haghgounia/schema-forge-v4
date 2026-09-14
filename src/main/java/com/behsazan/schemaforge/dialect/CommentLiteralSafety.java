package com.behsazan.schemaforge.dialect;

import java.util.Objects;

/**
 * Central safety contract for schema documentation rendered into executable SQL literals.
 *
 * <p>The canonical text is preserved exactly. Unsupported control characters are rejected
 * instead of being normalized or silently removed.</p>
 */
public final class CommentLiteralSafety {
    private CommentLiteralSafety() {
    }

    /** Validates text that will be persisted as a database table/column comment. */
    public static String validate(String value) {
        Objects.requireNonNull(value, "comment value must not be null");
        for (int offset = 0; offset < value.length();) {
            char unit = value.charAt(offset);
            if (Character.isSurrogate(unit)) {
                if (!Character.isHighSurrogate(unit)
                        || offset + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    throw new IllegalArgumentException(
                            "COMMENT_LITERAL_UNPAIRED_SURROGATE_UNSUPPORTED: U+"
                                    + String.format("%04X", (int) unit));
                }
            }
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0) {
                throw new IllegalArgumentException("COMMENT_LITERAL_NUL_UNSUPPORTED: database comments cannot contain NUL");
            }
            if (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r') {
                throw new IllegalArgumentException(
                        "COMMENT_LITERAL_CONTROL_CHAR_UNSUPPORTED: U+" + String.format("%04X", codePoint));
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    /** SQL-standard single-quoted character literal; single quotes are doubled. */
    public static String standardLiteral(String value) {
        String checked = validate(value);
        return "'" + checked.replace("'", "''") + "'";
    }

    /** PostgreSQL escape-string literal with deterministic backslash preservation. */
    public static String postgreSqlEscapeLiteral(String value) {
        String checked = validate(value);
        return "E'" + checked.replace("\\", "\\\\").replace("'", "''") + "'";
    }


    /** MySQL/MariaDB literal when NO_BACKSLASH_ESCAPES is disabled for the statement. */
    public static String mySqlBackslashLiteral(String value) {
        String checked = validate(value);
        return "'" + checked.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** SQL Server Unicode character literal. */
    public static String sqlServerUnicodeLiteral(String value) {
        return "N" + standardLiteral(value);
    }
}
