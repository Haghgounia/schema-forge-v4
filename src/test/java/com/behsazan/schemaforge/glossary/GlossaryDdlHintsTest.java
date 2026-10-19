package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.deployment.IntegratedSchemaDeploymentPlanner;
import com.behsazan.schemaforge.deployment.IntegratedSqlRenderer;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
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
        assertEquals("", hints.beforeColumn("CUSTOMER_ID"));
        assertEquals("", hints.beforeColumn("GENERAL_LEDGER"));
        assertEquals("", hints.beforeColumn("SUBSIDIARY_LEDGER"));
        assertEquals("", hints.beforeColumn("BLOCKED_CODE"));
        assertEquals("/* GLOSSARY_HINT: MISSING=ACTIVE */ ", hints.beforeColumn("IS_ACTIVE"));
        assertEquals("/* GLOSSARY_HINT: MISSING=UNKNOWN */ ",
                hints.beforeColumn("UNKNOWN_UNKNOWN_CODE"));
        assertEquals("/* GLOSSARY_HINT: MISSING=EVIL,ESCAPE */ ",
                hints.beforeColumn("evil/*escape*/"), "Untrusted raw values cannot escape a SQL comment");
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
            assertEquals(2, count(hinted, "/* GLOSSARY_HINT: MISSING="), platform.name());
            for (String line : hinted.split("\\R")) {
                if (line.contains("GLOSSARY_HINT: MISSING=ACTIVE")) assertTrue(line.toUpperCase(java.util.Locale.ROOT).contains("IS_ACTIVE"), platform.name());
                if (line.contains("GLOSSARY_HINT: MISSING=UNKNOWN")) assertTrue(line.toUpperCase(java.util.Locale.ROOT).contains("UNKNOWN_UNKNOWN_CODE"), platform.name());
                if (line.toUpperCase(java.util.Locale.ROOT).contains("CUSTOMER_ID") && !line.contains("CREATE ")) {
                    assertFalse(line.contains("GLOSSARY_HINT"), platform.name());
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
        assertEquals(2, count(hinted, "/* GLOSSARY_HINT: MISSING="));
    }

    private static long count(String value, String marker) {
        return value.lines().filter(line -> line.contains(marker)).count();
    }

    private static String removeHints(String sql) {
        return Pattern.compile("/\\* GLOSSARY_HINT: MISSING=[A-Z0-9_,]+ \\*/ ")
                .matcher(sql).replaceAll("");
    }
}
