package com.behsazan.schemaforge.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Writes a compact CSV report for generated and optionally executed scripts. */
public final class DdlValidationReportWriter {

    public Path write(Path outputFile, List<DdlValidationResult> results) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        Objects.requireNonNull(results, "results must not be null");
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        StringBuilder csv = new StringBuilder(
                "source_document,database,sql_file,status,executed_statements,message\n");
        for (DdlValidationResult result : results) {
            csv.append(csv(result.sourceDocument().toString())).append(',')
                    .append(result.platform().commandLineName()).append(',')
                    .append(csv(result.sqlFile().toString())).append(',')
                    .append(result.status()).append(',')
                    .append(result.executedStatements()).append(',')
                    .append(csv(result.message())).append('\n');
        }
        Files.writeString(outputFile, csv, StandardCharsets.UTF_8);
        return outputFile;
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
