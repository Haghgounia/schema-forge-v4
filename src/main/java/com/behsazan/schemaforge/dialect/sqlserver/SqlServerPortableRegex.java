package com.behsazan.schemaforge.dialect.sqlserver;

import com.behsazan.schemaforge.dialect.PortableRegexLikeAdapter;

/** SQL Server 2019/2022-compatible, binary-collated ASCII identifier checks. */
final class SqlServerPortableRegex {
    private SqlServerPortableRegex() { }

    static String rewrite(String sql) {
        return PortableRegexLikeAdapter.rewrite(sql, SqlServerPortableRegex::render);
    }

    private static String render(String column, String regex) {
        if (!PortableRegexLikeAdapter.isApprovedAsciiPattern(regex)) {
            throw new IllegalArgumentException("SQL Server REGEXP_LIKE not portable without "
                    + "SQL Server 2025 compatibility level 170: " + regex);
        }
        // CONVERT(NVARCHAR(MAX)) ensures DATALENGTH includes trailing spaces,
        // and Latin1_General_100_BIN2 ensures ASCII-case-sensitive classes.
        String unicode = "CONVERT(NVARCHAR(MAX), " + column + ")";
        String length = "DATALENGTH(" + unicode + ")";
        String binary = unicode + " COLLATE Latin1_General_100_BIN2";
        String allowed;
        String count;
        switch (regex) {
            case "^[A-Z]{3}$" -> {
                allowed = "A-Z";
                count = length + " = 6";
            }
            case "^[0-9]{3}$" -> {
                allowed = "0-9";
                count = length + " = 6";
            }
            case "^[A-Z][A-Z0-9_]*$" -> {
                allowed = "A-Z0-9_";
                count = length + " >= 2 AND LEFT(" + unicode
                        + ", 1) COLLATE Latin1_General_100_BIN2 LIKE '[A-Z]'";
            }
            case "^[A-Z0-9_\\-]{1,30}$", "^[A-Z0-9_-]{1,30}$" -> {
                allowed = "A-Z0-9_-"; // hyphen at end of [] is literal in T-SQL LIKE
                count = length + " BETWEEN 2 AND 60";
            }
            default -> throw new IllegalArgumentException("Unreviewed regex: " + regex);
        }
        // PATINDEX finds invalid characters in the *entire* string, including
        // invalid trailing whitespace; the binary collation avoids case folding.
        return "(" + count + " AND PATINDEX('%[^" + allowed + "]%', "
                + binary + ") = 0)";
    }
}
