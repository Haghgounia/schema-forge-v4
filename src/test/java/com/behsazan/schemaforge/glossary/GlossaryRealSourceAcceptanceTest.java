package com.behsazan.schemaforge.glossary;

import com.behsazan.schemaforge.api.SchemaForgeApiService;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.NamingValidationProperties;
import com.behsazan.schemaforge.config.NumericMappingProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestAssembler;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.specification.parser.ea.EnterpriseArchitectXmlParser;
import com.behsazan.schemaforge.specification.validation.ValidationReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Full Word, legacy DOC and EA XML production pipelines with manifest-tracked glossary reports. */
class GlossaryRealSourceAcceptanceTest {
    @TempDir Path temp;

    @Test
    void realWordLegacyAndEaPackagesContainGovernedReportsThatCanEnterReview() throws Exception {
        var datasource = new DriverManagerDataSource();
        datasource.setDriverClassName("org.h2.Driver");
        datasource.setUrl("jdbc:h2:file:" + temp.resolve("glossary-real-sources")
                .toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
        datasource.setUsername("sa");
        datasource.setPassword("");
        var repo = new GlossaryRepository(datasource);
        var reviews = new GlossaryReviewService(datasource);
        var mapper = new ObjectMapper();
        var resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());
        var spelling = SpellCheckProperties.defaults();
        spelling.setEnabled(false);
        var service = new SchemaForgeApiService(AuditProperties.defaults(), GrantProperties.defaults(),
                spelling, mapper, resolver, EaImportProperties.defaults(), NumericMappingProperties.defaults(),
                NamingValidationProperties.defaults(), Optional.of(new GlossaryReportWriter(repo, mapper)));

        Path word = Path.of("src/test/resources/physical-real-corpus/MCB.BIM.TBL.COUNTRIES.V1.1.docx");
        assertArtifactAndReview("STANDARD_WORD", service.generateFromWord(new MockMultipartFile(
                "file", word.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.readAllBytes(word))), reviews, mapper);

        Path legacy = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        assertArtifactAndReview("LEGACY_WORD", service.generateFromLegacyWord(new MockMultipartFile(
                "file", legacy.getFileName().toString(), "application/msword", Files.readAllBytes(legacy)),
                "DPS"), reviews, mapper);

        // The checked-in real Party EA/XMI corpus has 41 tables and does not need
        // live DBMS connections just to check its vocabulary.
        Path ea = Path.of("src/test/resources/Party_14050514.xml");
        com.behsazan.schemaforge.domain.model.DatabaseSchema eaSchema;
        try (var input = Files.newInputStream(ea)) {
            eaSchema = new EnterpriseArchitectXmlParser("DPS").parse(ea.getFileName().toString(), input);
        }
        assertTrue(eaSchema.tables().size() > 0);
        var eaContext = ArtifactGenerationContext.create(
                ArtifactOrigin.ENTERPRISE_ARCHITECT, ea.getFileName().toString());
        var eaRoot = Files.createDirectories(temp.resolve("ea-artifacts"));
        var models = List.of(new ArtifactManifestAssembler.ModelInput(
                ea.getFileName().toString(), eaSchema, new ValidationReport(true, List.of())));
        new GlossaryReportWriter(repo, mapper).write(eaRoot, eaContext, models);
        new ArtifactManifestWriter(mapper).write(eaRoot, eaContext,
                ea.getFileName().toString(), models, Map.of());
        assertEquals(2, eaContext.ledger().snapshot().stream()
                .filter(a -> a.type() == ArtifactType.GLOSSARY_REPORT).count());
        var eaImported = reviews.importReport("unknown-terms.csv", Files.readAllBytes(
                eaRoot.resolve("reports/glossary/unknown-terms.csv")));
        assertTrue(eaImported.sourceRows() > 0);
        assertTrue(reviews.queue(false, 0, 100).total() > 0,
                "Real document pipelines must produce reviewable candidates");
    }

    private static void assertArtifactAndReview(String origin, byte[] zip,
                                                GlossaryReviewService reviews, ObjectMapper mapper) throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (!entry.isDirectory()) contents.put(entry.getName(), stream.readAllBytes());
            }
        }
        String report = "reports/glossary/unknown-terms.csv";
        String summary = "reports/glossary/summary.json";
        assertNotNull(contents.get(report), origin + ": missing glossary report");
        assertNotNull(contents.get(summary), origin + ": missing glossary summary");
        assertNotNull(contents.get("manifest.json"), origin + ": missing manifest");
        var manifest = mapper.readTree(contents.get("manifest.json"));
        assertEquals(origin, manifest.path("source").path("origin").asText());
        int tracked = 0;
        for (var artifact : manifest.path("artifacts")) {
            if ("GLOSSARY_REPORT".equals(artifact.path("type").asText())) {
                assertTrue(contents.containsKey(artifact.path("path").asText()));
                tracked++;
            }
        }
        assertEquals(2, tracked, "Each real Word package must track two glossary reports");
        assertEquals("01_Atomic_Terms", mapper.readTree(contents.get(summary))
                .path("referenceWorksheet").asText());
        var imported = reviews.importReport("unknown-terms.csv", contents.get(report));
        assertTrue(imported.sourceRows() >= imported.newOccurrences());
    }
}
