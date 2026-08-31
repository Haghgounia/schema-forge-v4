package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentGenerationOrchestratorTest {

    private static final String TIMESTAMP = "20260823_043000_000";

    @TempDir
    Path temp;

    @Test
    void standardWordGenerationPreservesAllDdlAndCanonicalJsonArtifacts() throws Exception {
        Fixture fixture = fixture();
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD,
                TestSamplePaths.PROVINCES_V1_2.getFileName().toString(),
                TIMESTAMP);

        PreparedSchema prepared = fixture.orchestrator().generateStandardWord(
                TestSamplePaths.PROVINCES_V1_2, temp, context);

        assertEquals(1, prepared.schema().tables().size());
        String baseName = "MCB.BIM.TBL.PROVINCES.V1.2";
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Path ddl = temp.resolve(fixture.naming().ddlRelativePath(baseName, platform, TIMESTAMP));
            assertTrue(Files.isRegularFile(ddl), () -> "Missing DDL: " + ddl);
        }
        Path canonical = temp.resolve(fixture.naming().canonicalJsonRelativePath(baseName, TIMESTAMP));
        assertTrue(Files.isRegularFile(canonical));

        long generatedDdl = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.DDL)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.GENERATED)
                .count();
        long generatedJson = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.CANONICAL_JSON)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.GENERATED)
                .count();
        assertEquals(DatabasePlatform.values().length, generatedDdl);
        assertEquals(1, generatedJson);
    }

    @Test
    void unavailableMetadataStillInvokesMigrationAndComparisonSkipContractForEveryPlatform() throws Exception {
        Fixture fixture = fixture();
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD,
                TestSamplePaths.PROVINCES_V1_2.getFileName().toString(),
                TIMESTAMP);

        fixture.orchestrator().generateStandardWord(TestSamplePaths.PROVINCES_V1_2, temp, context);

        List<com.behsazan.schemaforge.artifact.ArtifactDescriptor> artifacts = context.ledger().snapshot();
        long migrationSkips = artifacts.stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.MIGRATION)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.SKIPPED)
                .count();
        long comparisonSkips = artifacts.stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.COMPARISON_WORKBOOK)
                .filter(descriptor -> descriptor.status() == ArtifactStatus.SKIPPED)
                .count();
        assertEquals(DatabasePlatform.values().length, migrationSkips);
        assertEquals(DatabasePlatform.values().length, comparisonSkips);
    }

    @Test
    void legacyWordGenerationPreservesRequestedSchemaAndOracleDdl() throws Exception {
        Fixture fixture = fixture();
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.LEGACY_WORD, source.getFileName().toString(), TIMESTAMP);

        PreparedSchema prepared = fixture.orchestrator().generateLegacyWord(
                source, temp, "DPS", context);

        assertEquals("DPS", prepared.schema().name().value());
        String baseName = "13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog";
        Path oraclePath = temp.resolve(
                fixture.naming().ddlRelativePath(baseName, DatabasePlatform.ORACLE, TIMESTAMP));
        String oracleSql = Files.readString(oraclePath, StandardCharsets.UTF_8);
        assertTrue(oracleSql.contains("CREATE TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
    }

    private static Fixture fixture() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());

        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        GrantProperties grantProperties = GrantProperties.defaults();
        SchemaPreparationService preparation = new SchemaPreparationService(
                AuditProperties.defaults(), grantProperties, spellCheck, new ObjectMapper());
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        DiagramArtifactProducer diagrams = new DiagramArtifactProducer(naming);
        MigrationArtifactProducer migrations = new MigrationArtifactProducer(naming);
        ComparisonArtifactProducer comparisons = new ComparisonArtifactProducer(naming);
        CrudArtifactProducer crud = new CrudArtifactProducer(naming, resolver, grantProperties);

        return new Fixture(
                new DocumentGenerationOrchestrator(
                        preparation, resolver, naming, diagrams, migrations, comparisons,
                        crud, new OracleDdlSanityChecker()),
                naming);
    }

    private record Fixture(
            DocumentGenerationOrchestrator orchestrator,
            ArtifactNamingPolicy naming) {
    }
}
