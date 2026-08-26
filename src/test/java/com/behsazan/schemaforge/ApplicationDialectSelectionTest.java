package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the behavior and regression expectations of Application Dialect Selection.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 *
 * @deprecated Covers the deprecated Phase1Application command-line path.
 * @since 4.1
 */
@Deprecated(since = "4.1", forRemoval = true)
class ApplicationDialectSelectionTest {

    @Test
    void shouldParseSupportedDatabaseNamesAndAliases() {
        assertEquals(DatabasePlatform.ORACLE, DatabasePlatform.parse("oracle"));
        assertEquals(DatabasePlatform.ORACLE, DatabasePlatform.parse("ORA"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("postgresql"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("postgres"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("PG"));
        assertEquals(DatabasePlatform.DB2_ZOS, DatabasePlatform.parse("db2zos"));
        assertEquals(DatabasePlatform.DB2_ZOS, DatabasePlatform.parse("db2-zos"));
        assertEquals(DatabasePlatform.DB2_ZOS, DatabasePlatform.parse("DB2"));
        assertEquals(DatabasePlatform.DB2_LUW, DatabasePlatform.parse("db2luw"));
        assertEquals(DatabasePlatform.DB2_LUW, DatabasePlatform.parse("db2-luw"));
        assertEquals(DatabasePlatform.DB2_LUW, DatabasePlatform.parse("LUW"));
        assertEquals(DatabasePlatform.SQLSERVER, DatabasePlatform.parse("sqlserver"));
        assertEquals(DatabasePlatform.SQLSERVER, DatabasePlatform.parse("sql-server"));
        assertEquals(DatabasePlatform.SQLSERVER, DatabasePlatform.parse("MSSQL"));
        assertEquals(DatabasePlatform.MYSQL, DatabasePlatform.parse("mysql"));
        assertThrows(IllegalArgumentException.class, () -> DatabasePlatform.parse("sqlite"));
    }

    @Test
    void shouldCreateTheRequestedDialect() {
        assertInstanceOf(OracleDialect.class, DialectFactory.create(DatabasePlatform.ORACLE));
        assertInstanceOf(PostgreSqlDialect.class, DialectFactory.create(DatabasePlatform.POSTGRESQL));
        assertInstanceOf(Db2ZosDialect.class, DialectFactory.create(DatabasePlatform.DB2_ZOS));
        assertInstanceOf(Db2LuwDialect.class, DialectFactory.create(DatabasePlatform.DB2_LUW));
        assertInstanceOf(SqlServerDialect.class, DialectFactory.create(DatabasePlatform.SQLSERVER));
        assertInstanceOf(MySqlDialect.class, DialectFactory.create(DatabasePlatform.MYSQL));
    }


    @Test
    void shouldApplyExplicitNumericStrategyToEveryDialect() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertSame(
                    NumericMappingStrategy.OPTIMIZED,
                    DialectFactory.create(platform, NumericMappingStrategy.OPTIMIZED).numericMappingStrategy(),
                    platform.name());
        }
    }

    @Test
    void shouldKeepOracleAsBackwardCompatibleDefault() {
        Path input = Path.of("/tmp/specification.docx");
        Phase1Application.CommandLineOptions options =
                Phase1Application.resolveOptions(input, new String[]{input.toString()});

        assertEquals(DatabasePlatform.ORACLE, options.platform());
        assertEquals(input.getParent(), options.outputDirectory());
    }

    @Test
    void shouldResolvePostgreSqlWithoutRequiringAnOutputDirectory() {
        Path input = Path.of("/tmp/specification.docx");
        Phase1Application.CommandLineOptions options =
                Phase1Application.resolveOptions(input, new String[]{input.toString(), "postgresql"});

        assertEquals(DatabasePlatform.POSTGRESQL, options.platform());
        assertEquals(input.getParent(), options.outputDirectory());
    }

    @Test
    void shouldResolveOutputDirectoryAndDatabasePlatformTogether() {
        Path input = Path.of("/tmp/specification.docx");
        Path output = Path.of("/tmp/generated");
        Phase1Application.CommandLineOptions options = Phase1Application.resolveOptions(
                input, new String[]{input.toString(), output.toString(), "postgresql"});

        assertEquals(DatabasePlatform.POSTGRESQL, options.platform());
        assertEquals(output.toAbsolutePath().normalize(), options.outputDirectory());
    }
}
