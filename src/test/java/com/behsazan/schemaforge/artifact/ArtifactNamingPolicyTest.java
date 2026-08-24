package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactNamingPolicyTest {
    private static final String TS = "20260823_010203_456";

    @Test
    void fixedClockProducesOneCanonicalGenerationTimestamp() {
        ArtifactNamingPolicy policy = new ArtifactNamingPolicy(
                Clock.fixed(Instant.parse("2026-08-23T01:02:03.456Z"), ZoneOffset.UTC));

        assertEquals(TS, policy.timestamp());
    }

    @Test
    void artifactFirstLayoutCoversCoreArtifactFamilies() {
        ArtifactNamingPolicy policy = new ArtifactNamingPolicy();

        assertEquals(Path.of("ddl", "oracle", "BIM.PROVINCES_" + TS + ".oracle.sql"),
                policy.ddlRelativePath("BIM.PROVINCES", DatabasePlatform.ORACLE, TS));
        assertEquals(Path.of("migration", "postgresql"),
                policy.migrationDirectory(DatabasePlatform.POSTGRESQL));
        assertEquals(Path.of("crud", "sqlserver",
                        "BIM.PROVINCES_" + TS + ".sqlserver.crud-procedures.sql"),
                policy.crudRelativePath("BIM.PROVINCES", DatabasePlatform.SQLSERVER, TS));
        assertEquals(Path.of("model", "provinces_" + TS + ".schema.json"),
                policy.canonicalJsonRelativePath("provinces", TS));
        assertEquals(Path.of("comparison", "mysql",
                        "BIM.PROVINCES_" + TS + ".mysql.compare.xlsx"),
                policy.comparisonRelativePath("BIM.PROVINCES", DatabasePlatform.MYSQL, TS));
        assertEquals(Path.of("scripts", "db2zos",
                        "ea-sample_" + TS + ".db2zos.run-all.sql"),
                policy.runAllRelativePath("ea-sample", DatabasePlatform.DB2_ZOS, TS));
        assertEquals(Path.of("reports", "provinces_" + TS + ".metadata-crud-summary.csv"),
                policy.metadataCrudSummaryRelativePath("provinces", TS));
        assertEquals(Path.of("reports", "batch-generation-summary.csv"),
                policy.batchGenerationSummaryRelativePath());
        assertEquals(Path.of("diagram", "mermaid", "batch", "schema-er.mmd"),
                policy.batchMermaidRelativePath(ArtifactNamingPolicy.BatchMermaidArtifact.ER));
        assertEquals(Path.of("diagram", "graphviz", "batch", "schema-overview.dot"),
                policy.batchGraphvizRelativePath(ArtifactNamingPolicy.BatchGraphvizArtifact.OVERVIEW));
    }

    @Test
    void perSourceDiagramLayoutUsesStableSemanticSuffixes() {
        ArtifactNamingPolicy policy = new ArtifactNamingPolicy();

        assertEquals(Path.of("diagram", "mermaid", "tables", "provinces_" + TS + ".er.mmd"),
                policy.mermaidErRelativePath("provinces", TS));
        assertEquals(Path.of("diagram", "mermaid", "tables",
                        "provinces_" + TS + ".conceptual-erd.mmd"),
                policy.mermaidConceptualRelativePath("provinces", TS));
        assertEquals(Path.of("diagram", "graphviz", "tables", "provinces_" + TS + ".er.dot"),
                policy.graphvizErRelativePath("provinces", TS));
        assertEquals(Path.of("diagram", "graphviz", "tables",
                        "provinces_" + TS + ".conceptual-erd.dot"),
                policy.graphvizConceptualRelativePath("provinces", TS));
    }

    @Test
    void standaloneMermaidKeepsExistingSelectorFilenameGrammar() {
        ArtifactNamingPolicy policy = new ArtifactNamingPolicy();
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("BIM", "PROVINCES")
                .dependencyDepth(2)
                .build();

        assertEquals("BIM_PROVINCES__dependency-table-with-dependencies-depth-2.mmd",
                policy.standaloneMermaidFileName(options));
    }

    @Test
    void requestChildrenInheritGenerationTimestamp() {
        ArtifactGenerationContext request = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH, "batch.zip", TS);
        ArtifactGenerationContext child = request.child(
                ArtifactOrigin.ZIP_BATCH, "nested/provinces.docx");
        ArtifactGenerationContext isolated = request.isolatedChild(
                ArtifactOrigin.ZIP_BATCH, "nested/countries.docx");

        assertEquals(TS, child.generationTimestamp());
        assertEquals(TS, isolated.generationTimestamp());
        assertEquals(request.generationId(), child.generationId());
        assertEquals(request.generationId(), isolated.generationId());
        assertEquals(request.generatedAt(), child.generatedAt());
        assertEquals(request.generatedAt(), isolated.generatedAt());
    }

    @Test
    void logicalNamesCannotSmuggleDirectoryStructureIntoPolicy() {
        ArtifactNamingPolicy policy = new ArtifactNamingPolicy();

        assertThrows(IllegalArgumentException.class,
                () -> policy.ddlRelativePath("nested/BIM.PROVINCES", DatabasePlatform.ORACLE, TS));
    }
}
