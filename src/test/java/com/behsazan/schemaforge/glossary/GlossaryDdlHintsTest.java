package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.validation.mariadb.MariaDbDdlSanityChecker;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for non-executable, same-row hints in SQL CREATE TABLE. */
class GlossaryDdlHintsTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    private static final GlossaryLexicon LEXICON = new GlossaryLexicon(
            Set.of("CUSTOMER", "ID", "CODE"), Set.of("GENERAL_LEDGER"),
            Set.of("IS"), Set.of("SUBSIDIARY_LEDGER"),
            Set.of("BLOCKED"), Set.of());

    private static DatabaseSchema schema() {
        return DatabaseSchema.builder("FIN")
                .addTable(Table.builder("FIN", "CUSTOMER_RECORD")
                        .addColumn(Column.required("CUSTOMER_ID", DataType.simple("INTEGER")))
                        .addColumn(Column.required("IS_ACTIVE", DataType.simple("INTEGER")))
                        .addColumn(Column.required("GENERAL_LEDGER", DataType.simple("INTEGER")))
                        .addColumn(Column.required("SUBSIDIARY_LEDGER", DataType.simple("INTEGER")))
                        .addColumn(Column.required("BLOCKED_CODE", DataType.simple("INTEGER")))
                        .addColumn(Column.required("UNKNOWN_UNKNOWN_CODE", DataType.simple("INTEGER")))
                        .build()).build();
    }

    @Test
    void aHintMeansReallyMissingAndIsSafeToRenderAsBlockComment() {
        var hints = new GlossaryDdlHints(LEXICON);
        assertEquals("", hints.afterColumn("CUSTOMER_ID"));
        assertEquals("", hints.afterColumn("GENERAL_LEDGER"));
        assertEquals("", hints.afterColumn("SUBSIDIARY_LEDGER"));
        assertEquals("", hints.afterColumn("BLOCKED_CODE"));
        assertEquals(" /* GH: ACTIVE */", hints.afterColumn("IS_ACTIVE"));
        assertEquals(" /* GH: UNKNOWN */",
                hints.afterColumn("UNKNOWN_UNKNOWN_CODE"));
        assertEquals(" /* GH: EVIL,ESCAPE */",
                hints.afterColumn("evil/*escape*/"), "Untrusted raw values cannot escape a SQL comment");
    }

    @Test
    void ddlOfEverySupportedPlatformKeepsExactlyTheSameExecutableSql() {
        var hints = new GlossaryDdlHints(LEXICON);
        DatabaseSchema schema = schema();
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            var dialect = DialectFactory.create(platform);
            String original = new DdlGenerator(dialect, FIXED).generate(schema);
            String hinted = new DdlGenerator(dialect, FIXED).withGlossaryHints(hints).generate(schema);
            assertEquals(original, removeHints(hinted), "Executable SQL changed for " + platform);
            assertEquals(2, count(hinted, "/* GH: "), platform.name());
            // Verify the physical identifier emitted by the dialect, not the
            // uppercase canonical identifier. PostgreSQL and some other
            // dialects intentionally fold unquoted names to lower case.
            String activeColumn = dialect.quote(Identifier.of("IS_ACTIVE"));
            String unknownColumn = dialect.quote(Identifier.of("UNKNOWN_UNKNOWN_CODE"));
            String knownColumn = dialect.quote(Identifier.of("CUSTOMER_ID"));
            for (String line : hinted.split("\\R")) {
                if (line.contains("/* GH: ACTIVE */")) {
                    assertTrailingColumnHint(line, activeColumn, "/* GH: ACTIVE */", platform);
                }
                if (line.contains("/* GH: UNKNOWN */")) {
                    assertTrailingColumnHint(line, unknownColumn, "/* GH: UNKNOWN */", platform);
                }
                if (line.contains(knownColumn) && !line.contains("CREATE ")) {
                    assertFalse(line.contains("/* GH:"), platform.name() + ": " + line);
                }
            }
            if (platform == DatabasePlatform.ORACLE) {
                assertTrue(new OracleDdlSanityChecker().inspect(hinted).isEmpty());
            }
            if (platform == DatabasePlatform.MARIADB) {
                assertTrue(new MariaDbDdlSanityChecker().inspect(hinted).isEmpty());
            }
        }
    }

    @Test
    void integratedCreateTableUsesTheSameHintsAndLegacyRendererRemainsUnchanged() {
        DatabaseSchema schema = schema();
        var plan = new IntegratedSchemaDeploymentPlanner().plan(schema);
        var dialect = DialectFactory.create(DatabasePlatform.POSTGRESQL);
        String normal = new IntegratedSqlRenderer(dialect).render(schema, plan).combinedSql();
        String hinted = new IntegratedSqlRenderer(dialect, new GlossaryDdlHints(LEXICON))
                .render(schema, plan).combinedSql();
        assertEquals(normal, removeHints(hinted));
        assertEquals(2, count(hinted, "/* GH: "));
    }

    private static void assertTrailingColumnHint(
            String line, String renderedColumn, String hint, DatabasePlatform platform) {
        int columnAt = line.indexOf(renderedColumn);
        int nullabilityAt = line.indexOf("NOT NULL", columnAt + renderedColumn.length());
        int hintAt = line.indexOf(hint);
        assertTrue(columnAt >= 0 && nullabilityAt > columnAt && hintAt > nullabilityAt,
                platform + " must place the hint after its rendered column and nullability: " + line);
    }

    private static long count(String value, String marker) {
        return value.lines().filter(line -> line.contains(marker)).count();
    }

    private static String removeHints(String sql) {
        return Pattern.compile(" /\\* GH: [A-Z0-9_,]+ \\*/")
                .matcher(sql).replaceAll("");
    }
}
