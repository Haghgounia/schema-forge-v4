package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationDialectSelectionTest {

    @Test
    void shouldParseSupportedDatabaseNamesAndAliases() {
        assertEquals(DatabasePlatform.ORACLE, DatabasePlatform.parse("oracle"));
        assertEquals(DatabasePlatform.ORACLE, DatabasePlatform.parse("ORA"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("postgresql"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("postgres"));
        assertEquals(DatabasePlatform.POSTGRESQL, DatabasePlatform.parse("PG"));
        assertThrows(IllegalArgumentException.class, () -> DatabasePlatform.parse("mysql"));
    }

    @Test
    void shouldCreateTheRequestedDialect() {
        assertInstanceOf(OracleDialect.class, DialectFactory.create(DatabasePlatform.ORACLE));
        assertInstanceOf(PostgreSqlDialect.class, DialectFactory.create(DatabasePlatform.POSTGRESQL));
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
