package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactContractTest {

    @Test
    void contractVersionShouldStartAtV1() {
        assertEquals("1", ArtifactContract.VERSION);
    }

    @Test
    void shouldDescribePlatformSpecificGeneratedArtifact() {
        ArtifactDescriptor artifact = new ArtifactDescriptor(
                ArtifactType.DDL,
                DatabasePlatform.ORACLE,
                "BIM.PROVINCES",
                "ddl/oracle/BIM.PROVINCES_20260822_010203_000.oracle.sql",
                "application/sql",
                "gen-20260822-001",
                ArtifactStatus.GENERATED,
                new ArtifactProvenance(
                        ArtifactOrigin.STANDARD_WORD,
                        "MCB.BIM.TBL.PROVINCES.V1.2.docx",
                        "ddl-generator"));

        assertEquals(ArtifactContract.VERSION, artifact.contractVersion());
        assertEquals(DatabasePlatform.ORACLE, artifact.platformOptional().orElseThrow());
        assertFalse(artifact.platformNeutral());
        assertEquals("ddl/oracle/BIM.PROVINCES_20260822_010203_000.oracle.sql", artifact.relativePath());
    }

    @Test
    void shouldDescribePlatformNeutralGeneratedArtifact() {
        ArtifactDescriptor artifact = new ArtifactDescriptor(
                ArtifactType.CANONICAL_JSON,
                null,
                "BIM.PROVINCES",
                "model/MCB.BIM.TBL.PROVINCES.V1.2_20260822_010203_000.schema.json",
                "application/json",
                "gen-20260822-001",
                ArtifactStatus.GENERATED,
                new ArtifactProvenance(
                        ArtifactOrigin.ZIP_BATCH,
                        "MCB.BIM.TBL.PROVINCES.V1.2.docx",
                        "canonical-json-writer"));

        assertTrue(artifact.platformNeutral());
        assertTrue(artifact.platformOptional().isEmpty());
    }

    @Test
    void skippedAndFailedOutcomesMayExistWithoutAFileIdentity() {
        ArtifactDescriptor skipped = new ArtifactDescriptor(
                ArtifactType.CRUD,
                DatabasePlatform.ORACLE,
                "FEE.FEE_VERSION",
                null,
                null,
                "gen-20260822-002",
                ArtifactStatus.SKIPPED,
                new ArtifactProvenance(
                        ArtifactOrigin.ENTERPRISE_ARCHITECT,
                        "Party_14050514.xml",
                        "oracle-crud-generator"));

        ArtifactDescriptor failed = new ArtifactDescriptor(
                ArtifactType.COMPARISON_WORKBOOK,
                DatabasePlatform.MYSQL,
                "BIM.PROVINCES",
                "",
                "",
                "gen-20260822-003",
                ArtifactStatus.FAILED,
                new ArtifactProvenance(
                        ArtifactOrigin.STANDARD_WORD,
                        "MCB.BIM.TBL.PROVINCES.V1.2.docx",
                        "comparison-writer"));

        assertEquals("", skipped.relativePath());
        assertEquals("", failed.mediaType());
    }

    @Test
    void generatedArtifactRequiresRelativePathAndMediaType() {
        ArtifactProvenance provenance = new ArtifactProvenance(
                ArtifactOrigin.INTERNAL,
                "",
                "test-producer");

        assertThrows(IllegalArgumentException.class, () -> new ArtifactDescriptor(
                ArtifactType.MANIFEST,
                null,
                "manifest",
                "",
                "application/json",
                "gen-1",
                ArtifactStatus.GENERATED,
                provenance));

        assertThrows(IllegalArgumentException.class, () -> new ArtifactDescriptor(
                ArtifactType.MANIFEST,
                null,
                "manifest",
                "manifest.json",
                "",
                "gen-1",
                ArtifactStatus.GENERATED,
                provenance));
    }

    @Test
    void relativePathMustBePortableAndPackageRelative() {
        ArtifactProvenance provenance = new ArtifactProvenance(
                ArtifactOrigin.INTERNAL,
                "",
                "test-producer");

        assertThrows(IllegalArgumentException.class, () -> generated("/manifest.json", provenance));
        assertThrows(IllegalArgumentException.class, () -> generated("C:/work/manifest.json", provenance));
        assertThrows(IllegalArgumentException.class, () -> generated("reports\\manifest.json", provenance));
        assertThrows(IllegalArgumentException.class, () -> generated("reports/../manifest.json", provenance));
        assertThrows(IllegalArgumentException.class, () -> generated("reports//manifest.json", provenance));
    }

    @Test
    void provenanceRequiresAnOriginAndProducerButSourceNameMayBeEmpty() {
        ArtifactProvenance provenance = new ArtifactProvenance(
                ArtifactOrigin.INTERNAL,
                null,
                "artifact-contract");

        assertEquals("", provenance.sourceName());
        assertThrows(NullPointerException.class,
                () -> new ArtifactProvenance(null, "source", "producer"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactProvenance(ArtifactOrigin.INTERNAL, "source", " "));
    }

    @Test
    void artifactTypesShouldCoverEveryC41InventoryFamilyWithoutTransportSpecificZipType() {
        EnumSet<ArtifactType> expected = EnumSet.of(
                ArtifactType.DDL,
                ArtifactType.MIGRATION,
                ArtifactType.CRUD,
                ArtifactType.CANONICAL_JSON,
                ArtifactType.COMPARISON_WORKBOOK,
                ArtifactType.MERMAID_DIAGRAM,
                ArtifactType.GRAPHVIZ_DIAGRAM,
                ArtifactType.MANIFEST,
                ArtifactType.RUN_SCRIPT,
                ArtifactType.SUMMARY_REPORT,
                ArtifactType.ERROR_REPORT,
                ArtifactType.ISSUE_REPORT);

        assertEquals(expected, EnumSet.allOf(ArtifactType.class));
    }

    private static ArtifactDescriptor generated(String path, ArtifactProvenance provenance) {
        return new ArtifactDescriptor(
                ArtifactType.MANIFEST,
                null,
                "manifest",
                path,
                "application/json",
                "gen-1",
                ArtifactStatus.GENERATED,
                provenance);
    }
}
