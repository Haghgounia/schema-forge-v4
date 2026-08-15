package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One-shot end-to-end batch test for the sample table specifications supplied by the project team.
 *
 * <p>The test deliberately goes through {@link SchemaForgeApiService#generateFromZip} rather than
 * calling the Mermaid exporter directly. This verifies the intended production behavior: each
 * successfully processed Word document produces the normal database artifacts and one Mermaid ER
 * file in the same output archive.</p>
 *
 * <p>Comparison Excel workbooks are not required by this test because they depend on live metadata
 * repositories. When metadata repositories are enabled in the real application, the existing Excel
 * generation behavior remains unchanged.</p>
 */
class SchemaDocuments3ZipMermaidOutputIT {
    private static final String INPUT_PROPERTY = "schemaforge.sample.zip";
    private static final String OUTPUT_PROPERTY = "schemaforge.sample.outputDir";

    private static final Path DEFAULT_INPUT =
            Path.of("D:\\Sample-Docs\\Word\\SchemaDocuments_3.zip");
    private static final Path DEFAULT_OUTPUT =
            Path.of("D:\\Sample-Docs\\Output\\SchemaDocuments_3");

    @Test
    void generatesMermaidBesideNormalOutputsForSchemaDocuments3Zip() throws Exception {
        Path input = Path.of(System.getProperty(INPUT_PROPERTY, DEFAULT_INPUT.toString()))
                .toAbsolutePath().normalize();
        Path outputRoot = Path.of(System.getProperty(OUTPUT_PROPERTY, DEFAULT_OUTPUT.toString()))
                .toAbsolutePath().normalize();

        Assumptions.assumeTrue(Files.isRegularFile(input),
                () -> "Sample ZIP does not exist: " + input
                        + ". Override with -D" + INPUT_PROPERTY + "=<zip-path>");

        SourceDocumentCounts sourceCounts = countSourceDocuments(input);

        Files.createDirectories(outputRoot);
        Path outputZip = outputRoot.resolve("SchemaDocuments_3-schemaforge-output.zip");
        Path extracted = outputRoot.resolve("extracted");
        deleteRecursively(extracted);
        Files.createDirectories(extracted);

        SchemaForgeApiService service = serviceWithoutLiveMetadata();
        MockMultipartFile upload = new MockMultipartFile(
                "file",
                input.getFileName().toString(),
                "application/zip",
                Files.readAllBytes(input));

        byte[] generatedArchive = service.generateFromZip(upload);
        Files.write(outputZip, generatedArchive);
        unzip(generatedArchive, extracted);

        Path batchSummary = extracted.resolve("reports").resolve("batch-generation-summary.csv");
        assertTrue(Files.isRegularFile(batchSummary), "Batch summary was not generated");
        long successfulDocuments = Files.readAllLines(batchSummary, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(",\"SUCCESS\","))
                .count();

        long oracle = countFiles(extracted, ".oracle.sql");
        long postgresql = countFiles(extracted, ".postgresql.sql");
        long sqlserver = countFiles(extracted, ".sqlserver.sql");
        long db2zos = countFiles(extracted, ".db2zos.sql");
        long json = countFiles(extracted, ".json");
        long mermaid = countFiles(extracted, ".mermaid.mmd");
        long excel = countFiles(extracted, ".xlsx");

        assertTrue(Files.isDirectory(extracted.resolve("oracle")), "oracle directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("postgresql")), "postgresql directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("sqlserver")), "sqlserver directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("db2zos")), "db2zos directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("json")), "json directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("mermaid").resolve("tables")), "mermaid/tables directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("mermaid").resolve("batch")), "mermaid/batch directory missing");
        assertTrue(Files.isDirectory(extracted.resolve("reports")), "reports directory missing");

        Path batchEr = extracted.resolve("mermaid").resolve("batch").resolve("schema-er.mmd");
        Path batchDependency = extracted.resolve("mermaid").resolve("batch").resolve("schema-dependency.mmd");
        Path batchMermaidIssues = extracted.resolve("mermaid").resolve("batch").resolve("issues.csv");
        Path batchMermaidSummary = extracted.resolve("mermaid").resolve("batch").resolve("summary.txt");
        assertTrue(Files.isRegularFile(batchEr), "Batch Mermaid ER diagram was not generated");
        assertTrue(Files.isRegularFile(batchDependency), "Batch Mermaid dependency diagram was not generated");
        assertTrue(Files.isRegularFile(batchMermaidIssues), "Batch Mermaid issues report was not generated");
        assertTrue(Files.isRegularFile(batchMermaidSummary), "Batch Mermaid summary was not generated");
        assertTrue(Files.readString(batchEr, StandardCharsets.UTF_8).startsWith("erDiagram"));
        assertTrue(Files.readString(batchDependency, StandardCharsets.UTF_8).startsWith("flowchart LR"));
        String batchMermaidSummaryText = Files.readString(batchMermaidSummary, StandardCharsets.UTF_8);
        assertTrue(batchMermaidSummaryText.contains("Duplicate policy         : EXCLUDE_ALL_DUPLICATE_DEFINITIONS_NO_AUTO_SELECTION"));

        assertTrue(successfulDocuments > 0,
                "No document was successfully processed. Inspect reports/batch-generation-errors.log under " + extracted);
        assertEquals(successfulDocuments, oracle,
                "Every successful document must have one Oracle DDL file");
        assertEquals(successfulDocuments, postgresql,
                "Every successful document must have one PostgreSQL DDL file");
        assertEquals(successfulDocuments, sqlserver,
                "Every successful document must have one SQL Server DDL file");
        assertEquals(successfulDocuments, db2zos,
                "Every successful document must have one Db2 z/OS DDL file");
        assertEquals(successfulDocuments, json,
                "Every successful document must have one canonical JSON output");
        assertEquals(successfulDocuments, mermaid,
                "Every successful document must have one Mermaid ER output beside the normal artifacts");

        try (var paths = Files.walk(extracted)) {
            for (Path diagram : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".mermaid.mmd"))
                    .sorted()
                    .toList()) {
                String content = Files.readString(diagram, StandardCharsets.UTF_8);
                assertTrue(content.startsWith("erDiagram"),
                        "Invalid Mermaid ER artifact: " + diagram);
            }
        }

        System.out.println("SchemaDocuments_3 ZIP output smoke test");
        System.out.println("=======================================");
        System.out.println("Input ZIP            : " + input);
        System.out.println("Output ZIP           : " + outputZip);
        System.out.println("Extracted output     : " + extracted);
        System.out.println("Source DOCX          : " + sourceCounts.docx());
        System.out.println("Source legacy DOC    : " + sourceCounts.doc());
        if (sourceCounts.doc() > 0) {
            System.out.println("NOTE                 : Current generateFromZip production path processes DOCX only; legacy DOC files are not included in this smoke test.");
        }
        System.out.println("Successful documents : " + successfulDocuments);
        System.out.println("Oracle SQL           : " + oracle);
        System.out.println("PostgreSQL SQL       : " + postgresql);
        System.out.println("SQL Server SQL       : " + sqlserver);
        System.out.println("Db2 z/OS SQL         : " + db2zos);
        System.out.println("Canonical JSON       : " + json);
        System.out.println("Mermaid ER           : " + mermaid);
        System.out.println("Comparison Excel     : " + excel + " (live metadata disabled in this test)");
        System.out.println("Batch Mermaid ER     : " + batchEr);
        System.out.println("Batch dependency     : " + batchDependency);
        System.out.println("Batch Mermaid summary:");
        for (String line : batchMermaidSummaryText.lines().toList()) {
            System.out.println("  " + line);
        }
        System.out.println("Result               : PASS");
    }

    private static SchemaForgeApiService serviceWithoutLiveMetadata() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);

        return new SchemaForgeApiService(
                AuditProperties.defaults(),
                GrantProperties.defaults(),
                spellCheck,
                new ObjectMapper(),
                resolver);
    }

    private static SourceDocumentCounts countSourceDocuments(Path input) throws IOException {
        long docx = 0;
        long doc = 0;
        try (ZipFile zip = new ZipFile(input.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".docx") && !name.contains("/__macosx/")
                        && !Path.of(entry.getName()).getFileName().toString().startsWith("~$")) {
                    docx++;
                } else if (name.endsWith(".doc")) {
                    doc++;
                }
            }
        }
        return new SourceDocumentCounts(docx, doc);
    }

    private static long countFiles(Path root, String suffix) throws IOException {
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(normalizedSuffix))
                    .count();
        }
    }

    private static void unzip(byte[] archive, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IllegalArgumentException("Unsafe generated ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, zip.readAllBytes());
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record SourceDocumentCounts(long docx, long doc) { }

}
