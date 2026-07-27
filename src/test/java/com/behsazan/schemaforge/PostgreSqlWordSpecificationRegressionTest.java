package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage that generates a real PostgreSQL SQL file for every Word sample. */
class PostgreSqlWordSpecificationRegressionTest {

    private static final Path INPUT_DIRECTORY = TestSamplePaths.WORD_DIRECTORY;
    private static final Path OUTPUT_DIRECTORY = Path.of("target/test-output/postgresql");

    @Test
    void shouldGeneratePostgreSqlForAllWordSpecifications() throws Exception {
        assertTrue(Files.isDirectory(INPUT_DIRECTORY),
                "Input directory does not exist: " + INPUT_DIRECTORY.toAbsolutePath());

        List<Path> inputFiles;
        try (Stream<Path> files = Files.list(INPUT_DIRECTORY)) {
            inputFiles = files
                    .filter(Files::isRegularFile)
                    .filter(this::isWordDocument)
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        assertFalse(inputFiles.isEmpty(),
                "No Word documents were found in: " + INPUT_DIRECTORY.toAbsolutePath());

        Files.createDirectories(OUTPUT_DIRECTORY);
        SchemaGenerationService service = new SchemaGenerationService();

        for (Path inputFile : inputFiles) {
            GenerationOutput output = service.generate(
                    inputFile,
                    OUTPUT_DIRECTORY,
                    DatabasePlatform.POSTGRESQL);

            assertTrue(output.valid(), "Validation failed for " + inputFile.getFileName());
            assertTrue(Files.isRegularFile(output.sqlFile()),
                    "PostgreSQL SQL file was not created: " + output.sqlFile());
            assertTrue(output.sqlFile().getFileName().toString().endsWith(".postgresql.sql"),
                    "Unexpected PostgreSQL output name: " + output.sqlFile());

            String sql = Files.readString(output.sqlFile(), StandardCharsets.UTF_8);
            assertFalse(sql.isBlank(), "PostgreSQL SQL file is empty: " + output.sqlFile());
            assertTrue(sql.contains("CREATE TABLE"),
                    "PostgreSQL SQL does not contain CREATE TABLE: " + output.sqlFile());
            assertFalse(sql.contains("PROMPT "),
                    "Oracle PROMPT syntax found in PostgreSQL SQL: " + output.sqlFile());
            assertFalse(sql.contains(" ENABLE"),
                    "Oracle ENABLE syntax found in PostgreSQL SQL: " + output.sqlFile());
            assertFalse(sql.contains(" NOORDER"),
                    "Oracle NOORDER syntax found in PostgreSQL SQL: " + output.sqlFile());
        }
    }

    private boolean isWordDocument(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx");
    }
}
