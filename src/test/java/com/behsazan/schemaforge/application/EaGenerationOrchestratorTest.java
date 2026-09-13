package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.TestSamplePaths;
import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestWriter;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.EaImportProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.metadata.repository.MetadataRepository;
import com.behsazan.schemaforge.metadata.repository.MetadataRepositoryResolver;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Focused regression tests for the C8.9 Enterprise Architect orchestration boundary. */
class EaGenerationOrchestratorTest {
    private static final String TIMESTAMP = "20260823_053000_000";

    @Test
    void generatesPerTableDdlDependencyOrderedRunAllAndEaManifest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MetadataRepositoryResolver resolver = emptyResolver();
        EaGenerationOrchestrator orchestrator = orchestrator(objectMapper, resolver, "FEE");
        MockMultipartFile file = sampleFile();

        PreparedSchema prepared = orchestrator.prepare(file, "ea-sample.xml", null);
        ArtifactGenerationContext context = context("ea-sample.xml");
        Map<String, byte[]> entries = unzip(orchestrator.generate(prepared, "ea-sample", context));

        String oracleFeeVersion = "ddl/oracle/FEE.FEE_VERSION_" + TIMESTAMP + ".oracle.sql";
        String oracleRegulatory = "ddl/oracle/FEE.REGULATORY_RULE_" + TIMESTAMP + ".oracle.sql";
        String postgresFeeVersion = "ddl/postgresql/fee.fee_version_" + TIMESTAMP + ".postgresql.sql";
        assertTrue(entries.containsKey(oracleFeeVersion));
        assertTrue(entries.containsKey(oracleRegulatory));
        assertTrue(entries.containsKey(postgresFeeVersion));

        String runAllName = "scripts/oracle/ea-sample_" + TIMESTAMP + ".oracle.run-all.sql";
        String runAll = new String(entries.get(runAllName), StandardCharsets.UTF_8);
        assertTrue(runAll.indexOf("FEE.FEE_VERSION_" + TIMESTAMP + ".oracle.sql")
                < runAll.indexOf("FEE.REGULATORY_RULE_" + TIMESTAMP + ".oracle.sql"));
        assertTrue(runAll.contains("PHASE 1 - TABLES"));
        assertTrue(runAll.contains("PHASE 3 - FOREIGN KEYS"));
        assertTrue(runAll.lastIndexOf("CREATE TABLE") < runAll.indexOf("FOREIGN KEY"));

        long runAllCount = entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".run-all.sql"))
                .peek(entry -> {
                    String sql = new String(entry.getValue(), StandardCharsets.UTF_8);
                    assertTrue(sql.contains("CREATE TABLE"), entry.getKey());
                    assertTrue(sql.contains("PHASE 3 - FOREIGN KEYS"), entry.getKey());
                    assertTrue(sql.lastIndexOf("CREATE TABLE") < sql.indexOf("FOREIGN KEY"), entry.getKey());
                })
                .count();
        assertEquals(DatabasePlatform.values().length, runAllCount);

        String summary = new String(
                entries.get("reports/schemaforge-generation-summary.txt"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("Request Type         : ENTERPRISE_ARCHITECT"));
        assertTrue(summary.contains("Extracted Tables     : 2"));
        assertTrue(summary.contains("Metadata Status      : UNAVAILABLE"));
        assertTrue(summary.contains("FEE.FEE_VERSION"));
        assertTrue(summary.contains("FEE.REGULATORY_RULE"));

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        assertEquals("schemaforge-manifest/v1", manifest.path("manifestContract").asText());
        assertEquals(2, manifest.path("extensions").path("enterpriseArchitect")
                .path("dependencyOrder").size());
    }


    @Test
    void reportsOnlyTrueCycleMembersAndKeepsDownstreamTablesOutOfCycleList() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EaGenerationOrchestrator orchestrator = orchestrator(objectMapper, emptyResolver(), "TST");
        PreparedSchema prepared = orchestrator.prepare(cycleFile(), "ea-cycle.xml", null);
        Map<String, byte[]> entries = unzip(orchestrator.generate(
                prepared,
                "ea-cycle",
                context("ea-cycle.xml"),
                null,
                java.util.Set.of(DatabasePlatform.ORACLE)));

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        JsonNode cyclic = manifest.path("extensions").path("enterpriseArchitect").path("cyclicTables");
        assertEquals(2, cyclic.size());
        String cyclicText = cyclic.toString();
        assertTrue(cyclicText.contains("TST.A"));
        assertTrue(cyclicText.contains("TST.B"));
        assertFalse(cyclicText.contains("TST.C"));
    }

    @Test
    void preservesApiSchemaOverrideAndPostgreSqlEaLowercaseNaming() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        EaGenerationOrchestrator orchestrator = orchestrator(objectMapper, emptyResolver(), "FEE");
        MockMultipartFile file = sampleFile();

        PreparedSchema prepared = orchestrator.prepare(file, "ea-sample.xml", "API_SCHEMA");
        Map<String, byte[]> entries = unzip(orchestrator.generate(
                prepared, "ea-sample", context("ea-sample.xml")));

        assertTrue(entries.containsKey(
                "ddl/oracle/API_SCHEMA.REGULATORY_RULE_" + TIMESTAMP + ".oracle.sql"));
        assertTrue(entries.containsKey(
                "ddl/postgresql/api_schema.regulatory_rule_" + TIMESTAMP + ".postgresql.sql"));
        assertFalse(entries.containsKey(
                "ddl/postgresql/API_SCHEMA.REGULATORY_RULE_" + TIMESTAMP + ".postgresql.sql"));
    }

    @Test
    void preservesLegacyRunScriptLedgerProducerAcrossAllRegisteredPlatforms() throws Exception {
        EaGenerationOrchestrator orchestrator = orchestrator(
                new ObjectMapper(), emptyResolver(), "FEE");
        MockMultipartFile file = sampleFile();
        PreparedSchema prepared = orchestrator.prepare(file, "ea-sample.xml", null);
        ArtifactGenerationContext context = context("ea-sample.xml");

        orchestrator.generate(prepared, "ea-sample", context);

        List<ArtifactDescriptor> runScripts = context.ledger().snapshot().stream()
                .filter(descriptor -> descriptor.type() == ArtifactType.RUN_SCRIPT)
                .toList();
        assertEquals(DatabasePlatform.values().length, runScripts.size());
        assertTrue(runScripts.stream().allMatch(descriptor ->
                "SchemaForgeApiService".equals(descriptor.provenance().producer())));
        assertEquals(DatabasePlatform.values().length,
                runScripts.stream().map(ArtifactDescriptor::platform).distinct().count());
    }

    private static EaGenerationOrchestrator orchestrator(
            ObjectMapper objectMapper,
            MetadataRepositoryResolver resolver,
            String defaultSchema) {
        SpellCheckProperties spellCheck = SpellCheckProperties.defaults();
        spellCheck.setEnabled(false);
        GrantProperties grantProperties = GrantProperties.defaults();
        EaImportProperties ea = EaImportProperties.defaults();
        ea.setDefaultSchema(defaultSchema);
        ArtifactNamingPolicy namingPolicy = new ArtifactNamingPolicy();
        DiagramArtifactProducer diagramProducer = new DiagramArtifactProducer(namingPolicy);
        MigrationArtifactProducer migrationProducer = new MigrationArtifactProducer(namingPolicy);
        ComparisonArtifactProducer comparisonProducer = new ComparisonArtifactProducer(namingPolicy);
        CrudArtifactProducer crudProducer = new CrudArtifactProducer(
                namingPolicy, resolver, grantProperties);
        return new EaGenerationOrchestrator(
                new SchemaPreparationService(
                        AuditProperties.defaults(), grantProperties, spellCheck, objectMapper),
                resolver,
                ea,
                new ArtifactManifestWriter(objectMapper),
                namingPolicy,
                new ArtifactPackageBuilder(),
                diagramProducer,
                migrationProducer,
                comparisonProducer,
                crudProducer,
                new OracleDdlSanityChecker());
    }

    private static MetadataRepositoryResolver emptyResolver() {
        MetadataRepositoryResolver resolver = mock(MetadataRepositoryResolver.class);
        when(resolver.resolve(any(DatabasePlatform.class))).thenReturn(MetadataRepository.empty());
        return resolver;
    }

    private static MockMultipartFile sampleFile() throws Exception {
        return new MockMultipartFile(
                "file",
                TestSamplePaths.EA_SAMPLE.getFileName().toString(),
                "application/xml",
                Files.readAllBytes(TestSamplePaths.EA_SAMPLE));
    }


    private static MockMultipartFile cycleFile() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content><UML:Model name="EA Model" xmi.id="MODEL"><UML:Namespace.ownedElement>
                    <UML:Class name="A" xmi.id="A"><UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype><UML:Classifier.feature>
                      <UML:Attribute name="ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Attribute name="B_ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="1"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Operation name="PK_A"><UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                      <UML:Operation name="FK_A_B"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="B_ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                    </UML:Classifier.feature></UML:Class>
                    <UML:Class name="B" xmi.id="B"><UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype><UML:Classifier.feature>
                      <UML:Attribute name="ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Attribute name="A_ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="1"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Operation name="PK_B"><UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                      <UML:Operation name="FK_B_A"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="A_ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                    </UML:Classifier.feature></UML:Class>
                    <UML:Class name="C" xmi.id="C"><UML:ModelElement.stereotype><UML:Stereotype name="table"/></UML:ModelElement.stereotype><UML:Classifier.feature>
                      <UML:Attribute name="ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="0"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Attribute name="A_ID"><UML:ModelElement.stereotype><UML:Stereotype name="column"/></UML:ModelElement.stereotype><UML:ModelElement.taggedValue><UML:TaggedValue tag="type" value="NUMBER"/><UML:TaggedValue tag="precision" value="19"/><UML:TaggedValue tag="scale" value="0"/><UML:TaggedValue tag="position" value="1"/><UML:TaggedValue tag="lowerBound" value="1"/></UML:ModelElement.taggedValue></UML:Attribute>
                      <UML:Operation name="PK_C"><UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                      <UML:Operation name="FK_C_A"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:BehavioralFeature.parameter><UML:Parameter name="A_ID" kind="in"><UML:ModelElement.taggedValue><UML:TaggedValue tag="pos" value="0"/></UML:ModelElement.taggedValue></UML:Parameter></UML:BehavioralFeature.parameter></UML:Operation>
                    </UML:Classifier.feature></UML:Class>
                    <UML:Association name="(B_ID = ID)" xmi.id="AB"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:Association.connection><UML:AssociationEnd name="FK_A_B" type="A"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue></UML:AssociationEnd><UML:AssociationEnd name="PK_B" type="B"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue></UML:AssociationEnd></UML:Association.connection></UML:Association>
                    <UML:Association name="(A_ID = ID)" xmi.id="BA"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:Association.connection><UML:AssociationEnd name="FK_B_A" type="B"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue></UML:AssociationEnd><UML:AssociationEnd name="PK_A" type="A"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue></UML:AssociationEnd></UML:Association.connection></UML:Association>
                    <UML:Association name="(A_ID = ID)" xmi.id="CA"><UML:ModelElement.stereotype><UML:Stereotype name="FK"/></UML:ModelElement.stereotype><UML:Association.connection><UML:AssociationEnd name="FK_C_A" type="C"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="source"/></UML:ModelElement.taggedValue></UML:AssociationEnd><UML:AssociationEnd name="PK_A" type="A"><UML:ModelElement.taggedValue><UML:TaggedValue tag="ea_end" value="target"/></UML:ModelElement.taggedValue></UML:AssociationEnd></UML:Association.connection></UML:Association>
                  </UML:Namespace.ownedElement></UML:Model></XMI.content>
                </XMI>
                """;
        return new MockMultipartFile("file", "ea-cycle.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static ArtifactGenerationContext context(String sourceName) {
        return ArtifactGenerationContext.create(
                ArtifactOrigin.ENTERPRISE_ARCHITECT,
                sourceName,
                TIMESTAMP,
                OffsetDateTime.parse("2026-08-23T05:30:00-07:00"));
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }
}
