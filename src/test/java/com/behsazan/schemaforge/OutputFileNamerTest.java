package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the single naming rule used by every generated SQL script. */
class OutputFileNamerTest {

    @Test
    void shouldAppendSameGregorianDateAndTimeToAllOutputFiles() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-25T08:39:45.123Z"),
                ZoneId.of("Asia/Tehran"));
        OutputFileNamer namer = new OutputFileNamer(clock);

        OutputFileNamer.OutputNames names = namer.create(
                Path.of("target/output"),
                "MCB.BIM.TBL.CONTINENTS.V1.0.docx",
                DatabasePlatform.POSTGRESQL);

        assertEquals("20260725_120945_123", names.timestamp());
        assertEquals(
                Path.of("target/output/MCB.BIM.TBL.CONTINENTS.V1.0_20260725_120945_123.json"),
                names.jsonFile());
        assertEquals(
                Path.of("target/output/MCB.BIM.TBL.CONTINENTS.V1.0_20260725_120945_123.postgresql.sql"),
                names.sqlFile());
    }

    @Test
    void shouldUseOnePublicRuleForDdlCrudAndRunAllScripts() {
        OutputFileNamer namer = new OutputFileNamer();
        String timestamp = "20260802_101112_345";

        assertEquals(
                "DPS.DEPOSIT_PRODUCT_20260802_101112_345.oracle.sql",
                namer.scriptFileName(
                        "DPS.DEPOSIT_PRODUCT",
                        DatabasePlatform.ORACLE,
                        OutputFileNamer.ScriptKind.DDL,
                        timestamp));
        assertEquals(
                "DPS.DEPOSIT_PRODUCT_20260802_101112_345.oracle.crud-package.sql",
                namer.scriptFileName(
                        "DPS.DEPOSIT_PRODUCT",
                        DatabasePlatform.ORACLE,
                        OutputFileNamer.ScriptKind.CRUD,
                        timestamp));
        assertEquals(
                "DPS.DEPOSIT_PRODUCT_20260802_101112_345.sqlserver.crud-procedures.sql",
                namer.scriptFileName(
                        "DPS.DEPOSIT_PRODUCT",
                        DatabasePlatform.SQLSERVER,
                        OutputFileNamer.ScriptKind.CRUD,
                        timestamp));
        assertEquals(
                "Deposit2_20260802_101112_345.oracle.run-all.sql",
                namer.scriptFileName(
                        "Deposit2",
                        DatabasePlatform.ORACLE,
                        OutputFileNamer.ScriptKind.RUN_ALL,
                        timestamp));
    }

    @Test
    void shouldRejectCrudNameForUnsupportedPlatform() {
        OutputFileNamer namer = new OutputFileNamer();
        assertThrows(IllegalArgumentException.class, () -> namer.scriptFileName(
                "DPS.DEPOSIT_PRODUCT",
                DatabasePlatform.POSTGRESQL,
                OutputFileNamer.ScriptKind.CRUD,
                "20260802_101112_345"));
    }

    @Test
    void shouldKeepFileNameWithoutExtension() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneId.of("UTC"));
        OutputFileNamer namer = new OutputFileNamer(clock);

        OutputFileNamer.OutputNames names = namer.create(Path.of("out"), "schema", DatabasePlatform.ORACLE);

        assertEquals(Path.of("out/schema_20260102_030405_000.json"), names.jsonFile());
        assertEquals(Path.of("out/schema_20260102_030405_000.oracle.sql"), names.sqlFile());
    }
}
