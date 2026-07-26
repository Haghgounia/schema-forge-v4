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
    void shouldGenerateScriptsAndBatchDiagnosticReportsForEveryWordFile() throws Exception {
        Path input = Path.of("src", "test", "resources", "samples");
        Path output = tempDirectory.resolve("generated");

        long expectedDocuments;
        try (Stream<Path> stream = Files.walk(input)) {
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
        assertEquals(expectedDocuments, result.passedDocuments() + result.failedDocuments());
        assertEquals(result.passedDocuments(), result.oracleScripts());
        assertEquals(result.passedDocuments(), result.postgreSqlScripts());

        assertTrue(Files.isRegularFile(result.summaryFile()));
        assertTrue(Files.isRegularFile(result.indexDetailFile()));
        assertTrue(Files.isRegularFile(result.recoveryWarningFile()));
        assertTrue(Files.isRegularFile(result.errorFile()));

        List<String> summaryLines = Files.readAllLines(result.summaryFile());
        assertEquals(expectedDocuments + 1, summaryLines.size());
        assertTrue(summaryLines.getFirst().contains("unique_indexes"));
        assertTrue(summaryLines.getFirst().contains("elapsed_ms"));

        String indexDetails = Files.readString(result.indexDetailFile());
        assertTrue(indexDetails.startsWith("document,table,object_type"));

        String recoveryWarnings = Files.readString(result.recoveryWarningFile());
        assertTrue(recoveryWarnings.startsWith("document,warning_type,details"));

        List<Path> files;
        try (Stream<Path> stream = Files.list(output)) {
            files = stream.toList();
        }

        assertEquals(
                result.oracleScripts(),
                files.stream()
                        .filter(path -> path.toString().endsWith(".oracle.sql"))
                        .count()
        );

        assertEquals(
                result.postgreSqlScripts(),
                files.stream()
                        .filter(path -> path.toString().endsWith(".postgresql.sql"))
                        .count()
        );

        assertTrue(files.stream().allMatch(Files::isRegularFile));
    }
}
