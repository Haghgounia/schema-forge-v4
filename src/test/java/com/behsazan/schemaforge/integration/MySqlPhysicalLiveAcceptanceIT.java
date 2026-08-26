package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.JdbcMySqlMetadataRepository;
import com.behsazan.schemaforge.metadata.validation.PhysicalComparisonRow;
import com.behsazan.schemaforge.metadata.validation.PhysicalComparisonStatus;
import com.behsazan.schemaforge.metadata.validation.PhysicalMetadataComparator;
import com.behsazan.schemaforge.validation.SqlScriptStatementParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MySQL 8.x acceptance for persistent table/index physical metadata. */
class MySqlPhysicalLiveAcceptanceIT {
    private static final String TABLE = "SF_PHYSICAL_ACCEPT";
    private final SqlScriptStatementParser splitter = new SqlScriptStatementParser();

    @Test
    void generatedPhysicalOptionsRoundTripThroughLiveMetadata() throws Exception {
        Config c = Config.fromSystemProperties();
        Assumptions.assumeTrue(c.enabled(),
                "MySQL physical live acceptance disabled; provide jdbc.url and jdbc.user");
        c.validate();

        Class.forName("com.mysql.cj.jdbc.Driver");
        DriverManager.setLoginTimeout(15);
        Path out = Path.of(c.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(out);

        try (Connection connection = DriverManager.getConnection(c.url(), c.user(), c.password())) {
            connection.setAutoCommit(true);
            DatabaseMetaData meta = connection.getMetaData();
            assertTrue(meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql"));

            dropTable(connection, c.database());

            Table expected = expected(c.database());
            String ddl = new DdlGenerator(DialectFactory.create(DatabasePlatform.MYSQL)).generate(
                    DatabaseSchema.builder(c.database()).addTable(expected).build());
            Files.writeString(out.resolve("mysql-physical-live.mysql.sql"), ddl, StandardCharsets.UTF_8);

            int executed = 0;
            for (String sql : splitter.parse(ddl, DatabasePlatform.MYSQL)) {
                if (sql == null || sql.isBlank() || commentOnly(sql)) continue;
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                    executed++;
                }
            }
            assertTrue(executed > 0, "generated DDL must execute at least one statement");

            JdbcMySqlMetadataRepository repo = new JdbcMySqlMetadataRepository(
                    new NamedParameterJdbcTemplate(new DriverManagerDataSource(c.url(), c.user(), c.password())));
            Table actual = repo.findTable(c.database(), TABLE).orElseThrow();

            PhysicalMetadataComparator comparator = new PhysicalMetadataComparator();
            List<PhysicalComparisonRow> rows = new java.util.ArrayList<>();
            rows.addAll(comparator.compareTable(expected, actual, "MYSQL"));
            rows.addAll(comparator.compareIndexes(expected, actual, "MYSQL"));
            assertFalse(rows.isEmpty(), "physical comparison must produce rows");

            StringBuilder report = new StringBuilder();
            for (PhysicalComparisonRow row : rows) {
                report.append(row.scope()).append('|')
                        .append(row.objectName()).append('|')
                        .append(row.property()).append('|')
                        .append(row.expectedValue()).append('|')
                        .append(row.actualValue()).append('|')
                        .append(row.status()).append('|')
                        .append(row.note()).append(System.lineSeparator());
                assertTrue(row.status() == PhysicalComparisonStatus.MATCH
                                || row.status() == PhysicalComparisonStatus.NOT_SPECIFIED,
                        () -> "unexpected physical diff: " + row);
            }
            Files.writeString(out.resolve("mysql-physical-live-comparison.txt"), report, StandardCharsets.UTF_8);

            long mismatches = rows.stream().filter(r -> r.status() == PhysicalComparisonStatus.MISMATCH
                    || r.status() == PhysicalComparisonStatus.NOT_AVAILABLE
                    || r.status() == PhysicalComparisonStatus.REVIEW).count();
            assertTrue(mismatches == 0, "live physical metadata must round-trip with zero mismatches");

            System.out.println("============================================================");
            System.out.println("MySQL physical live acceptance");
            System.out.println("Server               : " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
            System.out.println("Pilot database       : " + c.database());
            System.out.println("Table                : " + TABLE);
            System.out.println("Physical rows        : " + rows.size());
            System.out.println("Physical mismatches  : " + mismatches);
            System.out.println("Artifacts            : " + out);
            System.out.println("============================================================");
        } finally {
            if (c.cleanup()) {
                try (Connection cleanup = DriverManager.getConnection(c.url(), c.user(), c.password())) {
                    cleanup.setAutoCommit(true);
                    dropTable(cleanup, c.database());
                }
            }
        }
    }

    private static Table expected(String database) {
        return Table.builder(database, TABLE)
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 64)))
                .addIndex(new Index(Identifier.of("IX_SF_PHYSICAL_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty(), List.of(), null,
                        Map.of("MYSQL_INDEX_TYPE", "BTREE")))
                .physicalOption("MYSQL_ENGINE", "InnoDB")
                .physicalOption("MYSQL_COLLATION", "utf8mb4_0900_ai_ci")
                .physicalOption("MYSQL_ROW_FORMAT", "DYNAMIC")
                .build();
    }

    private static void dropTable(Connection connection, String database) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + database.replace("`", "``") + "`.`" + TABLE + "`");
        }
    }

    private static boolean commentOnly(String sql) {
        String s = sql.strip();
        return s.startsWith("--") && s.lines().allMatch(line -> line.isBlank() || line.stripLeading().startsWith("--"));
    }

    private record Config(String url, String user, String password, String database, String outputDir, boolean cleanup) {
        static Config fromSystemProperties() {
            String url = System.getProperty("schemaforge.mysql.physical.jdbc.url", "");
            String user = System.getProperty("schemaforge.mysql.physical.jdbc.user", "");
            String password = System.getenv().getOrDefault("MYSQL_JDBC_PASSWORD", "");
            String database = System.getProperty("schemaforge.mysql.physical.database", "SCHEMAFORGE_M2_PILOT");
            String outputDir = System.getProperty("schemaforge.mysql.physical.outputDir",
                    "target/mysql-physical-live-acceptance");
            boolean cleanup = Boolean.parseBoolean(System.getProperty("schemaforge.mysql.physical.cleanup", "true"));
            return new Config(url, user, password, database, outputDir, cleanup);
        }

        boolean enabled() { return !url.isBlank() && !user.isBlank(); }

        void validate() {
            if (password.isBlank()) throw new IllegalStateException("MYSQL_JDBC_PASSWORD is required");
            if (database.isBlank()) throw new IllegalStateException("schemaforge.mysql.physical.database is required");
            if (!database.toUpperCase(Locale.ROOT).startsWith("SCHEMAFORGE_")) {
                throw new IllegalStateException("physical live acceptance requires a disposable SCHEMAFORGE_* database");
            }
        }
    }
}
