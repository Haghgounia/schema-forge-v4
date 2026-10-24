package com.behsazan.schemaforge.api;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Bounded, read-only schema discovery for an authenticated JDBC connection.
 *
 * <p>JDBC schema/catalog visibility is a navigation hint, <em>not</em> proof of
 * permission to read every table. The vendor-specific MetadataRepository is
 * still responsible for checking explicit schema/table operations.</p>
 */
final class JdbcSchemaDiscovery {
    static final int MAX_NAMES = 200;

    private JdbcSchemaDiscovery() { }

    static Result inspect(Connection connection, DatabaseMetaData metadata, String platform) {
        boolean catalogBacked = "mysql".equals(platform) || "mariadb".equals(platform);
        String preferred = currentNamespace(connection, catalogBacked);
        LinkedHashMap<String, String> found = new LinkedHashMap<>();
        boolean read = false;
        boolean truncated = false;
        try (ResultSet rows = catalogBacked ? metadata.getCatalogs() : metadata.getSchemas()) {
            if (rows != null) {
                while (rows.next()) {
                    String candidate = clean(rows.getString(catalogBacked ? "TABLE_CAT" : "TABLE_SCHEM"));
                    if (candidate.isEmpty()) continue;
                    String key = candidate.toUpperCase(Locale.ROOT);
                    if (found.containsKey(key)) continue;
                    if (found.size() == MAX_NAMES) {
                        truncated = true;
                        break;
                    }
                    found.put(key, candidate);
                }
                read = true;
            }
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignored) {
            // A driver may not implement catalog/schema enumeration. Do not guess.
        }
        if (!preferred.isEmpty()) {
            String key = preferred.toUpperCase(Locale.ROOT);
            if (!found.containsKey(key)) {
                if (found.size() == MAX_NAMES) {
                    String last = new ArrayList<>(found.keySet()).get(MAX_NAMES - 1);
                    found.remove(last);
                    truncated = true;
                }
                found.put(key, preferred);
            }
        }
        List<String> names = new ArrayList<>(found.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return new Result(preferred, List.copyOf(names), truncated, read);
    }

    private static String currentNamespace(Connection connection, boolean catalogBacked) {
        try {
            return clean(catalogBacked ? connection.getCatalog() : connection.getSchema());
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignored) {
            return "";
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.isEmpty() || result.length() > 128 || result.chars().anyMatch(c -> c < 32 || c == 127)) {
            return "";
        }
        return result;
    }

    record Result(String defaultSchema, List<String> availableSchemas,
                  boolean truncated, boolean metadataReadable) { }
}
