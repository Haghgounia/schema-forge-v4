package com.behsazan.schemaforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Directory Dual Database Generation Runner.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class DirectoryDualDatabaseGenerationRunnerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldGenerateScriptsAndBatchDiagnosticReportsForEveryWordFile() throws Exception {
        Path input = TestSamplePaths.WORD_DIRECTORY;
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
        try (Stream<Path> stream = Files.walk(output)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .toList();
        }

        List<Path> oracleFiles = files.stream()
                .filter(path -> path.toString().endsWith(".oracle.sql"))
                .toList();
        assertEquals(result.oracleScripts(), oracleFiles.size());
        assertTrue(oracleFiles.stream().allMatch(path ->
                normalize(output.relativize(path)).startsWith("ddl/oracle/")));

        List<Path> postgreSqlFiles = files.stream()
                .filter(path -> path.toString().endsWith(".postgresql.sql"))
                .toList();
        assertEquals(result.postgreSqlScripts(), postgreSqlFiles.size());
        assertTrue(postgreSqlFiles.stream().allMatch(path ->
                normalize(output.relativize(path)).startsWith("ddl/postgresql/")));
    }
    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

}
