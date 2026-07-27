package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.SchemaGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Schema Generation Service Integration.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class SchemaGenerationServiceIntegrationTest {

    @TempDir
    Path outputDirectory;

    @Test
    void shouldGeneratePostgreSqlAndJsonFromWordSpecification() throws Exception {
        Path input = TestSamplePaths.CONTINENTS_V1_0;

        Clock clock = Clock.fixed(Instant.parse("2026-07-25T01:09:38Z"), ZoneId.of("UTC"));
        GenerationOutput output = new SchemaGenerationService(new OutputFileNamer(clock))
                .generate(input, outputDirectory, DatabasePlatform.POSTGRESQL);

        assertTrue(Files.isRegularFile(output.jsonFile()));
        assertTrue(Files.isRegularFile(output.sqlFile()));
        assertTrue(output.valid());
        assertTrue(output.jsonFile().getFileName().toString()
                .endsWith("_20260725_010938_000.json"));
        assertTrue(output.sqlFile().getFileName().toString()
                .endsWith("_20260725_010938_000.postgresql.sql"));

        String sql = Files.readString(output.sqlFile(), StandardCharsets.UTF_8);
        assertTrue(sql.contains("SchemaForge Offline PostgreSQL DDL"));
        assertTrue(sql.contains("\\set ON_ERROR_STOP on"));
        assertTrue(sql.contains("CREATE TABLE"));
        assertTrue(sql.contains("Dialect      : PostgreSql"));
        assertFalse(sql.contains("PROMPT "));
        assertFalse(sql.contains(" ENABLE;"));
        assertFalse(sql.contains(" NOORDER"));
    }

}
