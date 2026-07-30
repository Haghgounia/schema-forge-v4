package com.behsazan.schemaforge;

import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudGenerationOptions;
import com.behsazan.schemaforge.generation.procedure.sqlserver.SqlServerCrudProcedureGenerator;
import com.behsazan.schemaforge.metadata.repository.JdbcSqlServerMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit live SQL Server metadata-to-CRUD integration test for a disposable database. */
class SqlServerCrudLiveIT {
    private static final String CONFIRMATION = "I_UNDERSTAND_SQLSERVER_DDL_WILL_EXECUTE";

    @Test
    void readsMetadataGeneratesExecutesAndSmokeTestsCrudProcedures() throws Exception {
        assertEquals(CONFIRMATION, required("schemaforge.sqlserver.execution.confirm"));
        String url = required("schemaforge.sqlserver.url");
        String user = System.getProperty("schemaforge.sqlserver.user", "");
        String password = System.getProperty("schemaforge.sqlserver.password", "");
        String driver = System.getProperty(
                "schemaforge.sqlserver.driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Class.forName(driver);

        String suffix = Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase(Locale.ROOT);
        String schema = safeIdentifier(
                System.getProperty("schemaforge.sqlserver.test.schema-prefix", "SFV") + "_CRUD_" + suffix);
        String table = "CRUD_RECORDS";
        String sequence = "SEQ_CRUD_RECORDS";

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                createFixture(connection, schema, table, sequence);
                String script = generate(url, user, password, driver, schema, table);
                assertTrue(script.contains("CREATE OR ALTER PROCEDURE [" + schema + "].[CRUD_RECORDS_CREATE]"));
                executeBatches(connection, script);
                assertEquals(5, procedureCount(connection, schema, table));
                smokeCrud(connection, schema, table);
                verifyCallerOwnsTransaction(connection, schema, table);
            } finally {
                cleanup(connection, schema, table, sequence);
            }
        }
    }

    private String generate(String url, String user, String password, String driver,
                            String schema, String table) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName(driver);
        JdbcSqlServerMetadataRepository repository = new JdbcSqlServerMetadataRepository(
                new NamedParameterJdbcTemplate(dataSource));
        var metadata = repository.findTable(schema, table).orElseThrow();
        return new SqlServerCrudProcedureGenerator().generate(
                metadata, SqlServerCrudGenerationOptions.defaults());
    }

    private void createFixture(Connection connection, String schema, String table, String sequence)
            throws Exception {
        execute(connection, "IF SCHEMA_ID(N'" + schema + "') IS NULL EXEC(N'CREATE SCHEMA "
                + quote(schema) + " AUTHORIZATION [dbo]')");
        execute(connection, "CREATE SEQUENCE " + qualify(schema, sequence)
                + " AS DECIMAL(9,0) START WITH 1 INCREMENT BY 1 NO CYCLE NO CACHE");
        execute(connection, "CREATE TABLE " + qualify(schema, table) + " ("
                + "[ID] DECIMAL(9,0) DEFAULT NEXT VALUE FOR " + qualify(schema, sequence) + " NOT NULL,"
                + "[CODE] VARCHAR(30) NOT NULL,"
                + "[NAME] NVARCHAR(100) NOT NULL,"
                + "[IS_ACTIVE] BIT CONSTRAINT [DF_" + table + "_ACTIVE] DEFAULT 1 NOT NULL,"
                + "[CREATED_BY] VARCHAR(50) NOT NULL,"
                + "[CREATED_DATE] DATETIME2(6) NOT NULL,"
                + "[LAST_MODIFIED_BY] VARCHAR(50) NOT NULL,"
                + "[LAST_MODIFIED_DATE] DATETIME2(6) NOT NULL,"
                + "CONSTRAINT [PK_" + table + "] PRIMARY KEY ([ID]),"
                + "CONSTRAINT [UK_" + table + "_CODE] UNIQUE ([CODE]))");
    }

    private void smokeCrud(Connection connection, String schema, String table) throws Exception {
        long id = createRow(connection, schema, table, "C001", "Initial", "crud-live");
        assertTrue(id > 0);

        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC " + qualify(schema, table + "_UPDATE")
                        + " @P_ID=?, @P_CODE=?, @P_NAME=?, @P_IS_ACTIVE=?, @P_LAST_MODIFIED_BY=?")) {
            statement.setLong(1, id);
            statement.setString(2, "C001");
            statement.setString(3, "Updated");
            statement.setBoolean(4, true);
            statement.setString(5, "crud-live-update");
            statement.execute();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC " + qualify(schema, table + "_GET_BY_ID") + " @P_ID=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("Updated", rows.getString("NAME"));
                assertFalse(rows.next());
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC " + qualify(schema, table + "_SEARCH")
                        + " @P_CODE=?, @P_OFFSET=0, @P_LIMIT=10")) {
            statement.setString(1, "C001");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(id, rows.getLong("ID"));
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC " + qualify(schema, table + "_DELETE") + " @P_ID=?")) {
            statement.setLong(1, id);
            statement.execute();
        }
        assertEquals(0, countByCode(connection, schema, table, "C001"));
    }

    private void verifyCallerOwnsTransaction(Connection connection, String schema, String table)
            throws Exception {
        connection.setAutoCommit(false);
        try {
            createRow(connection, schema, table, "ROLLBACK", "Rollback", "crud-live");
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
        assertEquals(0, countByCode(connection, schema, table, "ROLLBACK"));
    }

    private long createRow(Connection connection, String schema, String table,
                           String code, String name, String actor) throws Exception {
        String sql = "DECLARE @ID DECIMAL(9,0); "
                + "EXEC " + qualify(schema, table + "_CREATE")
                + " @P_CODE=?, @P_NAME=?, @P_CREATED_BY=?, @O_ID=@ID OUTPUT; SELECT @ID;";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, actor);
            boolean result = statement.execute();
            while (!result && statement.getUpdateCount() != -1) {
                result = statement.getMoreResults();
            }
            try (ResultSet rows = statement.getResultSet()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private int countByCode(Connection connection, String schema, String table, String code)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + qualify(schema, table) + " WHERE [CODE]=?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int procedureCount(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sys.procedures P JOIN sys.schemas S ON S.schema_id=P.schema_id "
                        + "WHERE S.name=? AND P.name IN (?,?,?,?,?)")) {
            statement.setString(1, schema);
            statement.setString(2, table + "_CREATE");
            statement.setString(3, table + "_UPDATE");
            statement.setString(4, table + "_DELETE");
            statement.setString(5, table + "_GET_BY_ID");
            statement.setString(6, table + "_SEARCH");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void executeBatches(Connection connection, String script) throws Exception {
        for (String batch : script.split("(?im)^\\s*GO\\s*$")) {
            String value = batch.trim();
            if (!value.isEmpty()) {
                execute(connection, value);
            }
        }
    }

    private void cleanup(Connection connection, String schema, String table, String sequence) {
        for (String suffix : new String[]{"_CREATE", "_UPDATE", "_DELETE", "_GET_BY_ID", "_SEARCH"}) {
            executeIgnoringFailure(connection, "DROP PROCEDURE " + qualify(schema, table + suffix));
        }
        executeIgnoringFailure(connection, "DROP TABLE " + qualify(schema, table));
        executeIgnoringFailure(connection, "DROP SEQUENCE " + qualify(schema, sequence));
        executeIgnoringFailure(connection, "DROP SCHEMA " + quote(schema));
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeIgnoringFailure(Connection connection, String sql) {
        try {
            execute(connection, sql);
        } catch (Exception ignored) {
            // Best-effort cleanup for disposable integration-test objects.
        }
    }

    private String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + property);
        }
        return value.trim();
    }

    private String safeIdentifier(String value) {
        String safe = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (safe.isBlank() || !Character.isLetter(safe.charAt(0))) safe = "SFV_" + safe;
        return safe.length() <= 128 ? safe : safe.substring(0, 128);
    }

    private String qualify(String schema, String object) {
        return quote(schema) + "." + quote(object);
    }

    private String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }
}
