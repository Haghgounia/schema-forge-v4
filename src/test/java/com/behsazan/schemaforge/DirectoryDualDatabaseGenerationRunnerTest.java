package com.behsazan.schemaforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryDualDatabaseGenerationRunnerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldGenerateOracleAndPostgreSqlScriptsForEveryWordFile() throws Exception {
        Path input = Path.of("src", "test", "resources", "samples");
        Path output = tempDirectory.resolve("generated");

        long expectedDocuments;
        try (Stream<Path> stream = Files.list(input)) {
            expectedDocuments = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase()
                            .endsWith(".docx"))
                    .count();
        }

        DirectoryDualDatabaseGenerationRunner.BatchResult result =
                DirectoryDualDatabaseGenerationRunner.generateAll(input, output);

        assertEquals(expectedDocuments, result.documents());
        assertEquals(expectedDocuments, result.oracleScripts());
        assertEquals(expectedDocuments, result.postgreSqlScripts());
        assertEquals(0, result.invalidDocuments());

        List<Path> files;
        try (Stream<Path> stream = Files.list(output)) {
            files = stream.toList();
        }

        assertEquals(
                expectedDocuments,
                files.stream()
                        .filter(path -> path.toString().endsWith(".oracle.sql"))
                        .count()
        );

        assertEquals(
                expectedDocuments,
                files.stream()
                        .filter(path -> path.toString().endsWith(".postgresql.sql"))
                        .count()
        );

        assertTrue(files.stream().allMatch(Files::isRegularFile));
    }
}
