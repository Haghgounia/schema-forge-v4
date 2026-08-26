package com.behsazan.schemaforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Directory Dual Database Validation Runner.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class DirectoryDualDatabaseValidationRunnerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void shouldGenerateBothDialectsAndWriteReportWithoutDatabaseExecution() throws Exception {
        Path input = TestSamplePaths.WORD_DIRECTORY;
        Path output = tempDirectory.resolve("validation-output");

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

        DirectoryDualDatabaseValidationRunner.BatchValidationResult result =
                DirectoryDualDatabaseValidationRunner.run(input, output, false);

        assertEquals(expectedDocuments, result.documents());
        assertEquals(expectedDocuments * 2, result.generated());
        assertEquals(0, result.passed());
        assertEquals(2, result.failed(),
                "PROVINCES.V1.1 must be blocked once for Oracle and once for PostgreSQL");
        assertTrue(Files.isRegularFile(result.reportFile()));
        String report = Files.readString(result.reportFile());
        assertTrue(report.contains("GENERATED_ONLY"));
        assertTrue(report.contains("Unresolved canonical datatype for BIM.PROVINCES.POPULATION"));
    }
}
