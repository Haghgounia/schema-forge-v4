package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.GenerationOutput;
import com.behsazan.schemaforge.application.SchemaGenerationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Manual test utility for bulk generation.
 *
 * Usage:
 *   java ... DirectoryDualDatabaseGenerationRunner <input-directory> <output-directory>
 *
 * For every .docx file, this runner generates both Oracle and PostgreSQL SQL scripts.
 */
public final class DirectoryDualDatabaseGenerationRunner {
    private DirectoryDualDatabaseGenerationRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: DirectoryDualDatabaseGenerationRunner <input-directory> <output-directory>");
        }

        BatchResult result = generateAll(Path.of(args[0]), Path.of(args[1]));
        System.out.printf(
                "Documents: %d, Oracle scripts: %d, PostgreSQL scripts: %d, Invalid: %d%n",
                result.documents(), result.oracleScripts(), result.postgreSqlScripts(), result.invalidDocuments());

        if (result.invalidDocuments() > 0) {
            throw new IllegalStateException("One or more specifications were invalid");
        }
    }

    static BatchResult generateAll(Path inputDirectory, Path outputDirectory) throws Exception {
        Path input = inputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Input directory does not exist: " + input);
        }
        Files.createDirectories(output);

        List<Path> documents;
        try (Stream<Path> files = Files.list(input)) {
            documents = files
                    .filter(Files::isRegularFile)
                    .filter(DirectoryDualDatabaseGenerationRunner::isWordDocument)
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No .docx files found in: " + input);
        }

        SchemaGenerationService service = new SchemaGenerationService();
        int oracleScripts = 0;
        int postgreSqlScripts = 0;
        int invalidDocuments = 0;

        for (Path document : documents) {
            GenerationOutput oracle = service.generate(document, output, DatabasePlatform.ORACLE);
            GenerationOutput postgreSql = service.generate(document, output, DatabasePlatform.POSTGRESQL);

            oracleScripts += Files.isRegularFile(oracle.sqlFile()) ? 1 : 0;
            postgreSqlScripts += Files.isRegularFile(postgreSql.sqlFile()) ? 1 : 0;
            if (!oracle.valid() || !postgreSql.valid()) {
                invalidDocuments++;
            }

            System.out.println(document.getFileName());
            System.out.println("  Oracle    : " + oracle.sqlFile());
            System.out.println("  PostgreSQL: " + postgreSql.sqlFile());
        }

        return new BatchResult(documents.size(), oracleScripts, postgreSqlScripts, invalidDocuments);
    }

    private static boolean isWordDocument(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    record BatchResult(int documents, int oracleScripts, int postgreSqlScripts, int invalidDocuments) {
    }
}
