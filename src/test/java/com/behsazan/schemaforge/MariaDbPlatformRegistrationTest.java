package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.PhysicalObjectNamePolicy;
import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 gate: MariaDB becomes a selectable DDL platform without claiming M5-M7 services. */
class MariaDbPlatformRegistrationTest {

    @Test
    void registersMariaDbAsASelectablePlatformAndFactoryDialect() {
        assertEquals(DatabasePlatform.MARIADB, DatabasePlatform.parse("mariadb"));
        assertEquals(DatabasePlatform.MARIADB, DatabasePlatform.parse("maria-db"));
        assertEquals("mariadb", DatabasePlatform.MARIADB.commandLineName());
        assertInstanceOf(MariaDbDialect.class, DialectFactory.create(DatabasePlatform.MARIADB));
        assertSame(NumericMappingStrategy.OPTIMIZED,
                DialectFactory.create(DatabasePlatform.MARIADB, NumericMappingStrategy.OPTIMIZED)
                        .numericMappingStrategy());
    }

    @Test
    void selectionGrammarIncludesMariaDbInExplicitAndAllSelections() {
        assertEquals(Set.of(DatabasePlatform.MARIADB),
                DatabasePlatform.parseSelection(List.of("mariadb")));
        assertTrue(DatabasePlatform.parseSelection(List.of("all")).contains(DatabasePlatform.MARIADB));
        assertTrue(DatabasePlatform.parseSelection(null).contains(DatabasePlatform.MARIADB));
    }

    @Test
    void publicArtifactNamingUsesMariaDbPlatformToken() {
        String name = new OutputFileNamer().scriptFileName(
                "PDL.PRODUCT", DatabasePlatform.MARIADB, OutputFileNamer.ScriptKind.DDL,
                "20260910_160000_000");

        assertEquals("PDL.PRODUCT_20260910_160000_000.mariadb.sql", name);
        assertEquals(64, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.MARIADB));
    }
}
