package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonResult;
import com.behsazan.schemaforge.metadata.validation.MetadataComparisonValidator;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.ea.EnterpriseArchitectXmlParser;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class SchemaForgeApiService {
    private final SchemaPreparationService preparationService;
    private final MetadataRepositoryResolver metadataRepositoryResolver;

    public SchemaForgeApiService(
            AuditProperties auditProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver) {
        this.preparationService = new SchemaPreparationService(auditProperties, spellCheckProperties, objectMapper);
        this.metadataRepositoryResolver = metadataRepositoryResolver;
    }

    public byte[] generateFromWord(MultipartFile file) throws IOException {
        requireExtension(file, ".docx");
        Path work = Files.createTempDirectory("schemaforge-word-");
        try {
            Path input = work.resolve(safeName(file.getOriginalFilename(), "input.docx"));
            file.transferTo(input);
            Path output = Files.createDirectories(work.resolve("output"));
            generateWordForBoth(input, output);
            return zipDirectory(output);
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromZip(MultipartFile file) throws IOException {
        requireExtension(file, ".zip");
        Path work = Files.createTempDirectory("schemaforge-zip-");
        try {
            Path inputDir = Files.createDirectories(work.resolve("input"));
            Path outputDir = Files.createDirectories(work.resolve("output"));
            unzipSafely(file, inputDir);
            try (var files = Files.walk(inputDir)) {
                var documents = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
                        .toList();
                if (documents.isEmpty()) throw new IllegalArgumentException("ZIP does not contain any DOCX files");
                for (Path document : documents) generateWordForBoth(document, outputDir);
            }
            return zipDirectory(outputDir);
        } finally {
            deleteRecursively(work);
        }
    }

    public byte[] generateFromEaXml(MultipartFile file) throws IOException {
        String name = safeName(file.getOriginalFilename(), "ea-model.xml");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml") && !lower.endsWith(".xmi")) throw new IllegalArgumentException("EA file must be XML or XMI");
        Path work = Files.createTempDirectory("schemaforge-ea-");
        try {
            DatabaseSchema parsed = new EnterpriseArchitectXmlParser().parse(name, file.getInputStream());
            PreparedSchema prepared = preparationService.prepare(parsed);
            Path output = Files.createDirectories(work.resolve("output"));
            writeAllDatabaseOutputs(prepared, output, stripExtension(name));
            return zipDirectory(output);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Parses and enriches the Word model only once. Oracle and PostgreSQL are generated
     * from the exact same enriched model, so configured audit columns cannot diverge.
     */
    private void generateWordForBoth(Path input, Path output) throws IOException {
        DatabaseSchema parsed;
        try (InputStream stream = Files.newInputStream(input)) {
            parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(input.getFileName().toString(), stream));
        }
        PreparedSchema prepared = preparationService.prepare(parsed);
        writeAllDatabaseOutputs(prepared, output, stripExtension(input.getFileName().toString()));
    }

    private void writeAllDatabaseOutputs(PreparedSchema prepared, Path output, String baseName) throws IOException {
        DatabaseSchema schema = prepared.schema();
        ValidationReport report = prepared.validationReport();
        List<ValidationIssue> jsonIssues = new ArrayList<>(report.issues());

        // Metadata is queried once per database output. The same comparison result is
        // reused by SQL generation and the consolidated JSON validation report.
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            var dialect = DialectFactory.create(platform);
            MetadataComparisonResult metadata = new MetadataComparisonValidator(
                    dialect, metadataRepositoryResolver.resolve(platform)).validate(schema);
            jsonIssues.addAll(metadata.issues());

            String sql = new DdlGenerator(dialect).generate(schema, report, metadata);
            Files.writeString(
                    output.resolve(baseName + "." + platform.commandLineName() + ".sql"),
                    sql,
                    StandardCharsets.UTF_8);
        }

        ValidationReport jsonReport = new ValidationReport(
                jsonIssues.stream().noneMatch(issue -> "ERROR".equalsIgnoreCase(issue.severity())),
                jsonIssues);
        new JsonExporter().write(output.resolve(baseName + ".json"), schema, jsonReport);
    }

    private static void unzipSafely(MultipartFile file, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    private static byte[] zipDirectory(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(directory.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void requireExtension(MultipartFile file, String extension) {
        String name = safeName(file.getOriginalFilename(), "upload");
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) throw new IllegalArgumentException("Expected " + extension + " file");
    }

    private static String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        return Path.of(name).getFileName().toString();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
