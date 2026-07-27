package com.behsazan.schemaforge.validation.db2zos;

import com.behsazan.schemaforge.validation.JdbcConnectionSettings;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

/** Performs read-only Db2 for z/OS connection, special-register and catalog checks. */
public final class Db2ZosConnectionProbeService {

    public Db2ZosConnectionProbeResult probe(
            JdbcConnectionSettings settings,
            String driverClassName) {
        Objects.requireNonNull(settings, "settings must not be null");

        String productName = "";
        String productVersion = "";
        String driverName = "";
        String driverVersion = "";
        String currentServer = "";
        String currentSchema = "";
        String currentSqlId = "";
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
                    // Some JCC/server combinations do not expose JDBC read-only mode.
                    // The probe itself still executes SELECT/VALUES statements only.
                }
                DatabaseMetaData metadata = connection.getMetaData();
                productName = safe(metadata.getDatabaseProductName());
                productVersion = safe(metadata.getDatabaseProductVersion());
                driverName = safe(metadata.getDriverName());
                driverVersion = safe(metadata.getDriverVersion());

                currentServer = scalar(connection, "VALUES CURRENT SERVER");
                currentSchema = scalar(connection, "VALUES CURRENT SCHEMA");
                currentSqlId = scalar(connection, "VALUES CURRENT SQLID");
                catalogAccessible = catalogProbe(connection);
            }
            String message = catalogAccessible
                    ? "Connection, special registers and SYSIBM catalog access succeeded."
                    : "Connection succeeded, but SYSIBM.SYSTABLES was not readable.";
            return new Db2ZosConnectionProbeResult(
                    catalogAccessible,
                    productName,
                    productVersion,
                    driverName,
                    driverVersion,
                    currentServer,
                    currentSchema,
                    currentSqlId,
                    catalogAccessible,
                    message);
        } catch (Exception exception) {
            return new Db2ZosConnectionProbeResult(
                    false,
                    productName,
                    productVersion,
                    driverName,
                    driverVersion,
                    currentServer,
                    currentSchema,
                    currentSqlId,
                    catalogAccessible,
                    rootMessage(exception));
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
                "SELECT NAME, CREATOR, TYPE, DBNAME, TSNAME, REMARKS "
                        + "FROM SYSIBM.SYSTABLES FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT TBCREATOR, TBNAME, NAME, COLTYPE, LENGTH, LENGTH2, SCALE, NULLS, "
                        + "DEFAULT, DEFAULTVALUE, GENERATED_ATTR, TYPESCHEMA, TYPENAME, "
                        + "HIDDEN, COLNO, REMARKS "
                        + "FROM SYSIBM.SYSCOLUMNS FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT TBCREATOR, TBNAME, CONSTNAME, TYPE, IXOWNER, IXNAME "
                        + "FROM SYSIBM.SYSTABCONST FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT IXCREATOR, IXNAME, COLNAME, COLSEQ, ORDERING "
                        + "FROM SYSIBM.SYSKEYS FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT CREATOR, TBNAME, RELNAME, REFTBCREATOR, REFTBNAME, "
                        + "IXOWNER, IXNAME, DELETERULE "
                        + "FROM SYSIBM.SYSRELS FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT CREATOR, TBNAME, RELNAME, COLSEQ, COLNAME "
                        + "FROM SYSIBM.SYSFOREIGNKEYS FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT TBOWNER, TBNAME, CHECKNAME, CHECKCONDITION "
                        + "FROM SYSIBM.SYSCHECKS FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT CREATOR, NAME, TBCREATOR, TBNAME, UNIQUERULE "
                        + "FROM SYSIBM.SYSINDEXES FETCH FIRST 1 ROW ONLY WITH UR",
                "SELECT SCHEMA, NAME, SEQTYPE "
                        + "FROM SYSIBM.SYSSEQUENCES FETCH FIRST 1 ROW ONLY WITH UR"
        };
        for (String sql : probes) {
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery(sql)) {
                // Preparing/executing the projection proves catalog visibility and column compatibility.
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
