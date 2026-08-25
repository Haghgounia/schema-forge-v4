package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.application.ArtifactGenerationService;
import com.behsazan.schemaforge.application.AuditGenerationOptions;
import com.behsazan.schemaforge.application.ArtifactPackageBuilder;
import com.behsazan.schemaforge.application.BatchGenerationOrchestrator;
import com.behsazan.schemaforge.application.ComparisonArtifactProducer;
import com.behsazan.schemaforge.application.CrudArtifactProducer;
import com.behsazan.schemaforge.application.DiagramArtifactProducer;
import com.behsazan.schemaforge.application.DocumentGenerationOrchestrator;
import com.behsazan.schemaforge.application.EaGenerationOrchestrator;
import com.behsazan.schemaforge.application.MigrationArtifactProducer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NumericMappingProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Coordinates schema forge api operations.
 *
 * @since 4.1
 */
@Service
public class SchemaForgeApiService {
    private final SchemaPreparationService preparationService;
    private final AuditProperties auditProperties;
    private final MetadataRepositoryResolver metadataRepositoryResolver;
    private final EaImportProperties eaImportProperties;
    private final ArtifactManifestWriter artifactManifestWriter;
    private final ArtifactNamingPolicy artifactNamingPolicy = new ArtifactNamingPolicy();
    private final ArtifactPackageBuilder artifactPackageBuilder = new ArtifactPackageBuilder();
    private final DiagramArtifactProducer diagramArtifactProducer =
            new DiagramArtifactProducer(artifactNamingPolicy);
    private final MigrationArtifactProducer migrationArtifactProducer;
    private final ComparisonArtifactProducer comparisonArtifactProducer =
            new ComparisonArtifactProducer(artifactNamingPolicy);
    private final CrudArtifactProducer crudArtifactProducer;
    private final OracleDdlSanityChecker oracleDdlSanityChecker = new OracleDdlSanityChecker();
    private final DocumentGenerationOrchestrator documentGenerationOrchestrator;
    private final ArtifactGenerationService artifactGenerationService;
    private final BatchGenerationOrchestrator batchGenerationOrchestrator;
    private final EaGenerationOrchestrator eaGenerationOrchestrator;

    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver) {
        this(auditProperties, grantProperties, spellCheckProperties, objectMapper,
                metadataRepositoryResolver, EaImportProperties.defaults(), NumericMappingProperties.defaults());
    }

    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties) {
        this(auditProperties, grantProperties, spellCheckProperties, objectMapper,
                metadataRepositoryResolver, eaImportProperties, NumericMappingProperties.defaults());
    }

    @Autowired
    public SchemaForgeApiService(
            AuditProperties auditProperties,
            GrantProperties grantProperties,
            SpellCheckProperties spellCheckProperties,
            ObjectMapper objectMapper,
            MetadataRepositoryResolver metadataRepositoryResolver,
            EaImportProperties eaImportProperties,
            NumericMappingProperties numericMappingProperties) {
        this.auditProperties = auditProperties;
        this.preparationService = new SchemaPreparationService(
                auditProperties, grantProperties, spellCheckProperties, objectMapper);
        this.metadataRepositoryResolver = metadataRepositoryResolver;
        this.eaImportProperties = eaImportProperties;
        this.artifactManifestWriter = new ArtifactManifestWriter(objectMapper);
        var numericMappingStrategy = numericMappingProperties.getStrategy();
        this.migrationArtifactProducer = new MigrationArtifactProducer(artifactNamingPolicy, numericMappingStrategy);
        this.crudArtifactProducer = new CrudArtifactProducer(
                artifactNamingPolicy, metadataRepositoryResolver, grantProperties);
        this.documentGenerationOrchestrator = new DocumentGenerationOrchestrator(
                preparationService, metadataRepositoryResolver, artifactNamingPolicy,
                diagramArtifactProducer, migrationArtifactProducer, comparisonArtifactProducer,
                crudArtifactProducer, oracleDdlSanityChecker, numericMappingStrategy);
        this.artifactGenerationService = new ArtifactGenerationService(
                documentGenerationOrchestrator, artifactPackageBuilder, artifactManifestWriter, numericMappingStrategy);
        this.batchGenerationOrchestrator = new BatchGenerationOrchestrator(
                documentGenerationOrchestrator, diagramArtifactProducer, artifactNamingPolicy,
                artifactPackageBuilder, artifactManifestWriter, numericMappingStrategy);
        this.eaGenerationOrchestrator = new EaGenerationOrchestrator(
                preparationService, metadataRepositoryResolver, eaImportProperties, artifactManifestWriter,
                artifactNamingPolicy, artifactPackageBuilder, diagramArtifactProducer, migrationArtifactProducer,
                comparisonArtifactProducer, crudArtifactProducer, oracleDdlSanityChecker, numericMappingStrategy);
    }

    public byte[] generateFromWord(MultipartFile file) throws IOException {
        return generateFromWord(file, null, "AUTO");
    }

    public byte[] generateFromWord(
            MultipartFile file, Boolean includeAuditFields, String auditProfile) throws IOException {
        return generateFromWordTracked(file, includeAuditFields, auditProfile).content();
    }

    GenerationArchive generateFromWordTracked(MultipartFile file) throws IOException {
        return generateFromWordTracked(file, null, "AUTO");
    }

    GenerationArchive generateFromWordTracked(
            MultipartFile file, Boolean includeAuditFields, String auditProfile) throws IOException {
        requireExtension(file, ".docx");
        String sourceName = safeName(file.getOriginalFilename(), "input.docx");
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD, sourceName);
        AuditGenerationOptions auditOptions = AuditGenerationOptions.resolve(
                auditProperties, includeAuditFields, auditProfile);
        byte[] content = artifactGenerationService.generateStandardWord(
                file, sourceName, context, auditOptions);
        return new GenerationArchive(content, context.ledger().snapshot());
    }

    public byte[] generateFromLegacyWord(MultipartFile file, String schemaName) throws IOException {
        return generateFromLegacyWord(file, schemaName, null, "AUTO");
    }

    public byte[] generateFromLegacyWord(
            MultipartFile file,
            String schemaName,
            Boolean includeAuditFields,
            String auditProfile) throws IOException {
        return generateFromLegacyWordTracked(file, schemaName, includeAuditFields, auditProfile).content();
    }

    GenerationArchive generateFromLegacyWordTracked(MultipartFile file, String schemaName) throws IOException {
        return generateFromLegacyWordTracked(file, schemaName, null, "AUTO");
    }

    GenerationArchive generateFromLegacyWordTracked(
            MultipartFile file,
            String schemaName,
            Boolean includeAuditFields,
            String auditProfile) throws IOException {
        requireWordExtension(file);
        String schema = requireText(schemaName, "Legacy Word schema parameter is required");
        String fallback = file.getOriginalFilename() != null
                && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".docx")
                ? "input.docx"
                : "input.doc";
        String sourceName = safeName(file.getOriginalFilename(), fallback);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.LEGACY_WORD, sourceName);
        AuditGenerationOptions auditOptions = AuditGenerationOptions.resolve(
                auditProperties, includeAuditFields, auditProfile);
        byte[] content = artifactGenerationService.generateLegacyWord(
                file, sourceName, schema, context, auditOptions);
        return new GenerationArchive(content, context.ledger().snapshot());
    }

    public byte[] generateFromZip(MultipartFile file) throws IOException {
        return generateFromZip(file, null, "AUTO");
    }

    public byte[] generateFromZip(
            MultipartFile file, Boolean includeAuditFields, String auditProfile) throws IOException {
        return generateFromZipTracked(file, includeAuditFields, auditProfile).content();
    }

    GenerationArchive generateFromZipTracked(MultipartFile file) throws IOException {
        return generateFromZipTracked(file, null, "AUTO");
    }

    GenerationArchive generateFromZipTracked(
            MultipartFile file, Boolean includeAuditFields, String auditProfile) throws IOException {
        requireExtension(file, ".zip");
        String sourceName = safeName(file.getOriginalFilename(), "input.zip");
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, sourceName);
        AuditGenerationOptions auditOptions = AuditGenerationOptions.resolve(
                auditProperties, includeAuditFields, auditProfile);
        byte[] content = batchGenerationOrchestrator.generate(
                file, stripExtension(sourceName), context, auditOptions);
        return new GenerationArchive(content, context.ledger().snapshot());
    }

    public byte[] generateFromEaXml(MultipartFile file) throws IOException {
        return generateFromEaXml(file, null, null, "AUTO");
    }

    public byte[] generateFromEaXml(MultipartFile file, String schemaName) throws IOException {
        return generateFromEaXml(file, schemaName, null, "AUTO");
    }

    public byte[] generateFromEaXml(
            MultipartFile file,
            String schemaName,
            Boolean includeAuditFields,
            String auditProfile) throws IOException {
        return generateFromEaXmlTracked(file, schemaName, includeAuditFields, auditProfile).content();
    }

    GenerationArchive generateFromEaXmlTracked(MultipartFile file, String schemaName) throws IOException {
        return generateFromEaXmlTracked(file, schemaName, null, "AUTO");
    }

    GenerationArchive generateFromEaXmlTracked(
            MultipartFile file,
            String schemaName,
            Boolean includeAuditFields,
            String auditProfile) throws IOException {
        String name = safeName(file.getOriginalFilename(), "ea-model.xml");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml") && !lower.endsWith(".xmi")) {
            throw new IllegalArgumentException("EA file must be XML or XMI");
        }
        AuditGenerationOptions auditOptions = AuditGenerationOptions.resolve(
                auditProperties, includeAuditFields, auditProfile);
        PreparedSchema prepared = eaGenerationOrchestrator.prepare(
                file, name, schemaName, auditOptions);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ENTERPRISE_ARCHITECT, name);
        byte[] content = eaGenerationOrchestrator.generate(
                prepared, stripExtension(name), context, auditOptions);
        return new GenerationArchive(content, context.ledger().snapshot());
    }

    private static void requireWordExtension(MultipartFile file) {
        String name = safeName(file.getOriginalFilename(), "upload");
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".doc") && !lower.endsWith(".docx")) {
            throw new IllegalArgumentException("Expected .doc or .docx file");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
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

    record GenerationArchive(byte[] content, List<ArtifactDescriptor> artifacts) {
        GenerationArchive {
            content = content.clone();
            artifacts = List.copyOf(artifacts);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

}
