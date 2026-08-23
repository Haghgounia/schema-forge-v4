package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.json.JsonExporter;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Executes the complete offline Word-to-JSON-and-SQL pipeline. */
public final class SchemaGenerationService {
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final SchemaPreparationService preparationService;

    public SchemaGenerationService() {
        this(new OutputFileNamer(), AuditProperties.defaults());
    }

    public SchemaGenerationService(OutputFileNamer outputFileNamer) {
        this(outputFileNamer, AuditProperties.defaults());
    }

    public SchemaGenerationService(AuditProperties auditProperties) {
        this(new OutputFileNamer(), auditProperties);
    }

    public SchemaGenerationService(OutputFileNamer outputFileNamer, AuditProperties auditProperties) {
        this.artifactNamingPolicy = new ArtifactNamingPolicy(
                Objects.requireNonNull(outputFileNamer, "outputFileNamer must not be null"));
        this.preparationService = new SchemaPreparationService(
                Objects.requireNonNull(auditProperties, "auditProperties must not be null"));
    }

    public GenerationOutput generate(Path input, Path outputDirectory, DatabasePlatform platform) throws IOException {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(platform, "platform must not be null");

        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedInput)) {
            throw new IllegalArgumentException("Input file does not exist: " + normalizedInput);
        }
        Files.createDirectories(normalizedOutput);

        DatabaseSchema parsed;
        try (InputStream stream = Files.newInputStream(normalizedInput)) {
            parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(normalizedInput.getFileName().toString(), stream));
        }

        PreparedSchema prepared = preparationService.prepare(parsed);
        DatabaseSchema enriched = prepared.schema();
        ValidationReport report = prepared.validationReport();
        String timestamp = artifactNamingPolicy.timestamp();
        String sourceBaseName = sourceBaseName(normalizedInput.getFileName().toString());
        Path jsonOutput = normalizedOutput.resolve(
                artifactNamingPolicy.canonicalJsonRelativePath(sourceBaseName, timestamp));
        Path sqlOutput = normalizedOutput.resolve(
                artifactNamingPolicy.ddlRelativePath(sourceBaseName, platform, timestamp));
        Files.createDirectories(jsonOutput.getParent());
        Files.createDirectories(sqlOutput.getParent());

        new JsonExporter().write(jsonOutput, enriched, report);
        Dialect dialect = DialectFactory.create(platform);
        String sql = new DdlGenerator(dialect).generate(enriched, report);
        if (platform == DatabasePlatform.ORACLE) {
            new OracleDdlSanityChecker().requireValid(sql, sqlOutput.getFileName().toString());
        }
        Files.writeString(sqlOutput, sql, StandardCharsets.UTF_8);

        return new GenerationOutput(jsonOutput, sqlOutput, platform, report.valid());
    }

    private static String sourceBaseName(String fileName) {
        String simpleName = Path.of(fileName).getFileName().toString();
        int dot = simpleName.lastIndexOf('.');
        return dot > 0 ? simpleName.substring(0, dot) : simpleName;
    }

}
