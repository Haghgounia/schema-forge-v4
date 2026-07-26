package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlValidationReportWriterTest {
    @TempDir
    Path tempDirectory;

    @Test
    void shouldWriteCsvReport() throws Exception {
        Path report = tempDirectory.resolve("report.csv");
        DdlValidationResult result = new DdlValidationResult(
                Path.of("sample.docx"), Path.of("sample.postgresql.sql"),
                DatabasePlatform.POSTGRESQL, DdlValidationStatus.PASSED, 3, "ok");

        new DdlValidationReportWriter().write(report, List.of(result));

        String csv = Files.readString(report);
        assertTrue(csv.contains("source_document,database,sql_file,status,executed_statements,message"));
        assertTrue(csv.contains("postgresql"));
        assertTrue(csv.contains("PASSED"));
    }
}
