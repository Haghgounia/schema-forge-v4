package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.validation.JdbcConnectionSettings;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosConnectionProbeResult;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosConnectionProbeService;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineDdlValidator;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit Db2 for z/OS integration test. The *IT suffix keeps it out of the normal test suite.
 * Run only against an approved disposable validation qualifier.
 */
class Db2ZosLiveIT {
    private static final String CONFIRMATION = "I_UNDERSTAND_DB2_DDL_MAY_COMMIT";

    @Test
    void probesConnectionAndExecutesDisposableDdl() throws Exception {
        String confirmation = required("schemaforge.db2zos.execution.confirm");
        assertEquals(CONFIRMATION, confirmation,
                "Explicit DDL confirmation is required because Db2 DDL can commit.");

        String url = required("schemaforge.db2zos.url");
        String user = System.getProperty("schemaforge.db2zos.user", "");
        String password = System.getProperty("schemaforge.db2zos.password", "");
        String driver = System.getProperty(
                "schemaforge.db2zos.driver", "com.ibm.db2.jcc.DB2Driver");
        String schema = required("schemaforge.db2zos.test.schema").toUpperCase(Locale.ROOT);
        JdbcConnectionSettings settings = new JdbcConnectionSettings(url, user, password);

        Db2ZosConnectionProbeResult probe = new Db2ZosConnectionProbeService().probe(settings, driver);
        assertTrue(probe.successful(), probe.message());

        String suffix = Long.toString(Instant.now().toEpochMilli(), 36)
                .toUpperCase(Locale.ROOT);
        String tableName = "SFV_" + suffix;
        String sequenceName = "SFS_" + suffix;
        DatabaseSchema model = smokeModel(schema, tableName, sequenceName);
        String sql = new DdlGenerator(new Db2ZosDialect()).generate(model);
        assertTrue(new Db2ZosOfflineDdlValidator().validate(sql).valid());

        Class.forName(driver);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                for (String statement : new SqlScriptStatementParser().parse(sql, DatabasePlatform.DB2_ZOS)) {
                    try (Statement jdbc = connection.createStatement()) {
                        jdbc.execute(statement);
                    }
                }
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM SYSIBM.SYSTABLES WHERE CREATOR = ? AND NAME = ? WITH UR",
                        schema, tableName));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM SYSIBM.SYSSEQUENCES "
                                + "WHERE SCHEMA = ? AND NAME = ? AND SEQTYPE = 'S' WITH UR",
                        schema, sequenceName));
            } finally {
                executeIgnoringFailure(connection, "DROP TABLE " + schema + "." + tableName);
                executeIgnoringFailure(connection, "DROP SEQUENCE " + schema + "." + sequenceName);
            }
        }
    }

    private DatabaseSchema smokeModel(String schema, String tableName, String sequenceName) {
        Column id = new Column(
                Identifier.of("ID"),
                DataType.numeric("NUMBER", 9, 0),
                false,
                new DefaultValue(schema + "." + sequenceName + ".NEXTVAL"),
                new Description("Smoke-test identifier"),
                false,
                1);
        Column code = new Column(
                Identifier.of("CODE"),
                DataType.varchar("VARCHAR2", 20),
                false,
                null,
                new Description("Smoke-test code"),
                false,
                2);
        Column active = new Column(
                Identifier.of("ACTIVE"),
                DataType.numeric("NUMBER", 1, 0),
                false,
                new DefaultValue("1"),
                new Description("Smoke-test flag"),
                false,
                3);
        Table table = Table.builder(schema, tableName)
                .description("SchemaForge disposable Db2 validation table")
                .addColumn(id)
                .addColumn(code)
                .addColumn(active)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_" + tableName), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_" + tableName), List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(
                        Identifier.of("CHK_" + tableName), "ACTIVE IN (0, 1)"))
                .build();
        Sequence sequence = new Sequence(
                QualifiedName.of(schema, sequenceName),
                1, 1, null, null, false, 0, Description.empty());
        return DatabaseSchema.builder(schema).addSequence(sequence).addTable(table).build();
    }

    private int count(Connection connection, String sql, String first, String second) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void executeIgnoringFailure(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception ignored) {
            // Best-effort cleanup for a disposable integration-test object.
        }
    }

    private String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property: " + property);
        }
        return value.trim();
    }
}
