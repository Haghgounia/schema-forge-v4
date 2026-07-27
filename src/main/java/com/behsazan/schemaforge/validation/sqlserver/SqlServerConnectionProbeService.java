package com.behsazan.schemaforge.validation.sqlserver;

import com.behsazan.schemaforge.validation.JdbcConnectionSettings;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

/** Executes a read-only SQL Server connection and catalog compatibility probe. */
public final class SqlServerConnectionProbeService {

    public SqlServerConnectionProbeResult probe(
            JdbcConnectionSettings settings,
            String driverClassName) {
        Objects.requireNonNull(settings, "settings must not be null");
        String productName = "";
        String productVersion = "";
        String driverName = "";
        String driverVersion = "";
        String serverName = "";
        String databaseName = "";
        String defaultSchema = "";
        boolean catalogAccessible = false;

        try {
            if (driverClassName != null && !driverClassName.isBlank()) {
                Class.forName(driverClassName.trim());
            }
            try (Connection connection = DriverManager.getConnection(
                    settings.url(), settings.username(), settings.password())) {
                try {
                    connection.setReadOnly(true);
                } catch (Exception ignored) {
                    // The probe executes SELECT statements only even when the driver ignores read-only mode.
                }
                DatabaseMetaData metadata = connection.getMetaData();
                productName = safe(metadata.getDatabaseProductName());
                productVersion = safe(metadata.getDatabaseProductVersion());
                driverName = safe(metadata.getDriverName());
                driverVersion = safe(metadata.getDriverVersion());

                serverName = scalar(connection, "SELECT CAST(SERVERPROPERTY('ServerName') AS nvarchar(256))");
                databaseName = scalar(connection, "SELECT DB_NAME()");
                defaultSchema = scalar(connection,
                        "SELECT COALESCE(DEFAULT_SCHEMA_NAME, 'dbo') FROM sys.database_principals WHERE name = USER_NAME()");
                catalogAccessible = catalogProbe(connection);
            }
            String message = catalogAccessible
                    ? "Connection and SQL Server catalog access succeeded."
                    : "Connection succeeded, but required sys catalog views were not readable.";
            return new SqlServerConnectionProbeResult(
                    catalogAccessible, productName, productVersion, driverName, driverVersion,
                    serverName, databaseName, defaultSchema, catalogAccessible, message);
        } catch (Exception exception) {
            return new SqlServerConnectionProbeResult(
                    false, productName, productVersion, driverName, driverVersion,
                    serverName, databaseName, defaultSchema, catalogAccessible, rootMessage(exception));
        }
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? safe(resultSet.getString(1)).trim() : "";
        }
    }

    private boolean catalogProbe(Connection connection) throws Exception {
        String[] probes = {
                "SELECT TOP (1) object_id, name, schema_id FROM sys.tables",
                "SELECT TOP (1) object_id, column_id, name, user_type_id, max_length, precision, scale, is_nullable FROM sys.columns",
                "SELECT TOP (1) object_id, parent_object_id, unique_index_id, type FROM sys.key_constraints",
                "SELECT TOP (1) object_id, parent_object_id, referenced_object_id, delete_referential_action_desc, update_referential_action_desc FROM sys.foreign_keys",
                "SELECT TOP (1) constraint_object_id, constraint_column_id, parent_column_id, referenced_column_id FROM sys.foreign_key_columns",
                "SELECT TOP (1) object_id, parent_object_id, definition FROM sys.check_constraints",
                "SELECT TOP (1) object_id, index_id, name, is_unique, filter_definition FROM sys.indexes",
                "SELECT TOP (1) object_id, index_id, index_column_id, key_ordinal, is_included_column, is_descending_key FROM sys.index_columns",
                "SELECT TOP (1) class, major_id, minor_id, name, value FROM sys.extended_properties",
                "SELECT TOP (1) object_id, name, start_value, increment, minimum_value, maximum_value, is_cycling, cache_size FROM sys.sequences"
        };
        for (String sql : probes) {
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery(sql)) {
                // Successful execution proves catalog visibility and projection compatibility.
            }
        }
        return true;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
