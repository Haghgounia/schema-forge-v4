package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataColumnProfile;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Acceptance coverage for optional metadata connection failure isolation in aggregate generation. */
class MetadataConnectionFailureIsolationAcceptanceTest {
    private static final String TIMESTAMP = "20260827_103000_000";

    @TempDir
    Path temp;

    @Test
    void eaGenerationContinuesWhenMySqlMetadataAuthenticationFails() throws Exception {
        AtomicInteger mysqlCalls = new AtomicInteger();
        MetadataRepositoryResolver resolver = resolverWithFailure(DatabasePlatform.MYSQL, mysqlCalls);
        EaGenerationOrchestrator orchestrator = eaOrchestrator(resolver);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                TestSamplePaths.EA_SAMPLE.getFileName().toString(),
                "application/xml",
                Files.readAllBytes(TestSamplePaths.EA_SAMPLE));

        PreparedSchema prepared = orchestrator.prepare(file, "ea-sample.xml", null);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.ENTERPRISE_ARCHITECT,
                "ea-sample.xml",
                TIMESTAMP,
                OffsetDateTime.parse("2026-08-27T10:30:00+03:30"));
        Map<String, byte[]> entries = unzip(orchestrator.generate(prepared, "ea-sample", context));

        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("ddl/oracle/")));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("ddl/mysql/")));
        assertTrue(entries.containsKey("manifest.json"));
        assertEquals(1, mysqlCalls.get(), "failed MySQL connection must be attempted only once per request");
        assertTrue(context.ledger().snapshot().stream().anyMatch(descriptor ->
                descriptor.platform() == DatabasePlatform.MYSQL
                        && descriptor.type() == ArtifactType.DDL
                        && descriptor.status() == ArtifactStatus.GENERATED));
        assertTrue(context.ledger().snapshot().stream().anyMatch(descriptor ->
                descriptor.platform() == DatabasePlatform.MYSQL
                        && descriptor.type() == ArtifactType.COMPARISON_WORKBOOK
                        && descriptor.status() == ArtifactStatus.SKIPPED));
    }

    @Test
    void standardWordGenerationContinuesWhenPostgreSqlMetadataConnectionFails() throws Exception {
        AtomicInteger postgresqlCalls = new AtomicInteger();
        MetadataRepositoryResolver resolver = resolverWithFailure(DatabasePlatform.POSTGRESQL, postgresqlCalls);
        DocumentGenerationOrchestrator orchestrator = documentOrchestrator(resolver);
        ArtifactGenerationContext context = ArtifactGenerationContext.create(
                ArtifactOrigin.STANDARD_WORD,
                TestSamplePaths.PROVINCES_V1_2.getFileName().toString(),
                TIMESTAMP);

        orchestrator.generateStandardWord(TestSamplePaths.PROVINCES_V1_2, temp, context);

        String baseName = "MCB.BIM.TBL.PROVINCES.V1.2";
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        assertTrue(Files.isRegularFile(temp.resolve(
                naming.ddlRelativePath(baseName, DatabasePlatform.ORACLE, TIMESTAMP))));
        assertTrue(Files.isRegularFile(temp.resolve(
                naming.ddlRelativePath(baseName, DatabasePlatform.POSTGRESQL, TIMESTAMP))));
        assertEquals(1, postgresqlCalls.get(), "failed PostgreSQL connection must be attempted only once per request");
    }

    private static MetadataRepositoryResolver resolverWithFailure(
            DatabasePlatform failingPlatform,
            AtomicInteger calls) {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        MetadataRepository failing = new MetadataRepository() {
            @Override
            public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
                calls.incrementAndGet();
                throw new CannotGetJdbcConnectionException(
                        "metadata connection failed",
                        new SQLException("Access denied / connection unavailable", "28000"));
            }
        };
        when(resolver.resolve(any(DatabasePlatform.class))).thenAnswer(invocation ->
                invocation.getArgument(0) == failingPlatform ? failing : MetadataRepository.empty());
        return resolver;
    }

    private static EaGenerationOrchestrator eaOrchestrator(MetadataRepositoryResolver resolver) {
        ObjectMapper objectMapper = new ObjectMapper();
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        GrantProperties grants = GrantProperties.defaults();
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema("FEE");
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        return new EaGenerationOrchestrator(
                new SchemaPreparationService(AuditProperties.defaults(), grants, spellCheck, objectMapper),
                resolver,
                ea,
                new ArtifactManifestWriter(objectMapper),
                naming,
                new ArtifactPackageBuilder(),
                new DiagramArtifactProducer(naming),
                new MigrationArtifactProducer(naming),
                new ComparisonArtifactProducer(naming),
                new CrudArtifactProducer(naming, resolver, grants),
                new OracleDdlSanityChecker());
    }

    private static DocumentGenerationOrchestrator documentOrchestrator(MetadataRepositoryResolver resolver) {
        ObjectMapper objectMapper = new ObjectMapper();
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        GrantProperties grants = GrantProperties.defaults();
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        return new DocumentGenerationOrchestrator(
                new SchemaPreparationService(AuditProperties.defaults(), grants, spellCheck, objectMapper),
                resolver,
                naming,
                new DiagramArtifactProducer(naming),
                new MigrationArtifactProducer(naming),
                new ComparisonArtifactProducer(naming),
                new CrudArtifactProducer(naming, resolver, grants),
                new OracleDdlSanityChecker());
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }
}
