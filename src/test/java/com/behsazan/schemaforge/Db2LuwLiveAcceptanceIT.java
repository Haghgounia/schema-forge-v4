package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
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
import com.behsazan.schemaforge.metadata.repository.JdbcDb2LuwMetadataRepository;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7.10 P4 live acceptance against a disposable Db2 LUW 12.1 database.
 * The IT suffix and explicit confirmation keep it out of normal regression execution.
 */
class Db2LuwLiveAcceptanceIT {
    private static final String CONFIRMATION = "I_UNDERSTAND_DB2_LUW_DDL_MAY_COMMIT";

    @Test
    void executesGeneratedDdlAndReadsItBackThroughSyscatMetadata() throws Exception {
        assertEquals(CONFIRMATION, required("schemaforge.db2luw.execution.confirm"),
                "Explicit DDL confirmation is required because Db2 LUW DDL can commit.");

        String url = required("schemaforge.db2luw.url");
        String user = required("schemaforge.db2luw.user");
        String password = required("schemaforge.db2luw.password");
        String driver = System.getProperty("schemaforge.db2luw.driver", "com.ibm.db2.jcc.DB2Driver");
        String schema = required("schemaforge.db2luw.test.schema").toUpperCase(Locale.ROOT);

        Class.forName(driver);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName(driver);
        JdbcDb2LuwMetadataRepository repository = new JdbcDb2LuwMetadataRepository(
                new NamedParameterJdbcTemplate(dataSource));

        boolean schemaCreatedByTest = false;
        try (Connection setup = DriverManager.getConnection(url, user, password)) {
            if (!repository.schemaExists(schema)) {
                String authorizationId = currentAuthorizationId(setup);
                try (Statement statement = setup.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + authorizationId);
                }
                schemaCreatedByTest = true;
            }
        }
        assertTrue(repository.schemaExists(schema),
                () -> "Db2 LUW live acceptance schema was not created/discovered: " + schema);

        String suffix = Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase(Locale.ROOT);
        String tableName = "SFV_" + suffix;
        String sequenceName = "SFS_" + suffix;
        String indexName = "IX_" + suffix;
        DatabaseSchema model = smokeModel(schema, tableName, sequenceName, indexName);

        String sql = new DdlGenerator(new Db2LuwDialect()).generate(
                model, new ValidationReport(true, List.of()), repository);

        // R7.10 infrastructure-existence contract: an existing document schema is not provisioned again.
        assertFalse(sql.contains("[SCHEMA BOOTSTRAP][DB2/LUW]"), sql);
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("CREATE SCHEMA " + schema), sql);
        assertTrue(sql.contains("[DB2/LUW DEFAULT REVIEW][DB2LUW-SEQ-DEFAULT-001]"), sql);
        assertFalse(sql.contains("WITH DEFAULT NEXT VALUE FOR " + schema + "." + sequenceName), sql);

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try {
                for (String statement : new SqlScriptStatementParser().parse(sql, DatabasePlatform.DB2_LUW)) {
                    try (Statement jdbc = connection.createStatement()) {
                        jdbc.execute(statement);
                    }
                }

                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TABNAME = ? AND TYPE IN ('T','U') WITH UR",
                        schema, tableName));
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM SYSCAT.SEQUENCES WHERE SEQSCHEMA = ? AND SEQNAME = ? WITH UR",
                        schema, sequenceName));
                assertTrue(nextSequenceValue(connection, schema, sequenceName) >= 1L,
                        "Generated Db2 LUW sequence must be executable through NEXT VALUE FOR.");

                Table live = repository.findTable(schema, tableName).orElseThrow();
                assertEquals(3, live.columns().size());
                assertNotNull(live.primaryKey().orElse(null));
                assertTrue(live.uniqueKeys().stream().anyMatch(key -> key.name() != null
                        && key.name().value().equals("UK_" + tableName)));
                assertTrue(live.checkConstraints().stream().anyMatch(check -> check.name() != null
                        && check.name().value().equals("CK_" + tableName)));
                assertTrue(live.indexes().stream().anyMatch(index -> index.name().value().equals(indexName)));
                assertTrue(live.physicalOptions().containsKey("TABLESPACE"),
                        "Db2 LUW physical metadata must expose the catalog tablespace.");
            } finally {
                executeIgnoringFailure(connection, "DROP TABLE " + schema + "." + tableName);
                executeIgnoringFailure(connection, "DROP SEQUENCE " + schema + "." + sequenceName);
            }
        } finally {
            if (schemaCreatedByTest) {
                try (Connection cleanup = DriverManager.getConnection(url, user, password)) {
                    executeIgnoringFailure(cleanup, "DROP SCHEMA " + schema + " RESTRICT");
                }
            }
        }
    }

    private DatabaseSchema smokeModel(String schema, String tableName, String sequenceName, String indexName) {
        Column id = new Column(
                Identifier.of("ID"),
                DataType.numeric("NUMBER", 9, 0),
                false,
                new DefaultValue(schema + "." + sequenceName + ".NEXTVAL"),
                new Description("SchemaForge live identifier"),
                false,
                1);
        Column code = new Column(
                Identifier.of("CODE"),
                DataType.varchar("VARCHAR2", 20),
                false,
                null,
                new Description("SchemaForge live code"),
                false,
                2);
        Column active = new Column(
                Identifier.of("ACTIVE"),
                DataType.numeric("NUMBER", 1, 0),
                false,
                new DefaultValue("1"),
                new Description("SchemaForge live flag"),
                false,
                3);

        Table table = Table.builder(schema, tableName)
                .description("SchemaForge disposable Db2 LUW live acceptance table")
                .addColumn(id)
                .addColumn(code)
                .addColumn(active)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_" + tableName), List.of(Identifier.of("ID"))))
                .addUniqueKey(new UniqueKey(
                        Identifier.of("UK_" + tableName), List.of(Identifier.of("CODE"))))
                .addCheck(new CheckConstraint(
                        Identifier.of("CK_" + tableName), "ACTIVE IN (0, 1)"))
                .addIndex(new Index(
                        Identifier.of(indexName),
                        List.of(new IndexColumn(Identifier.of("ACTIVE"), SortDirection.ASC)),
                        IndexType.NORMAL,
                        Description.empty()))
                .build();

        Sequence sequence = new Sequence(
                QualifiedName.of(schema, sequenceName),
                1, 1, null, null, false, 0, Description.empty());
        return DatabaseSchema.builder(schema).addSequence(sequence).addTable(table).build();
    }

    private long nextSequenceValue(Connection connection, String schema, String sequenceName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "VALUES NEXT VALUE FOR " + schema + "." + sequenceName)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
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

    private String currentAuthorizationId(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("VALUES CURRENT USER")) {
            resultSet.next();
            return resultSet.getString(1).trim();
        }
    }

    private void executeIgnoringFailure(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
}
