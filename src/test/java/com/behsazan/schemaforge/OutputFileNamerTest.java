package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void shouldKeepFileNameWithoutExtension() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneId.of("UTC"));
        OutputFileNamer namer = new OutputFileNamer(clock);

        OutputFileNamer.OutputNames names = namer.create(Path.of("out"), "schema", DatabasePlatform.ORACLE);

        assertEquals(Path.of("out/schema_20260102_030405_000.json"), names.jsonFile());
        assertEquals(Path.of("out/schema_20260102_030405_000.oracle.sql"), names.sqlFile());
    }
}
