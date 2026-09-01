package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactOutcomeContractTest {

    @Test
    void artifactStatusMustExposeFrozenOutcomeSet() {

        assertEquals(
                EnumSet.of(
                        ArtifactStatus.GENERATED,
                        ArtifactStatus.BLOCKED,
                        ArtifactStatus.SKIPPED,
                        ArtifactStatus.FAILED),
                EnumSet.allOf(ArtifactStatus.class));
    }

    @Test
    void ledgerMustRecordBlockedArtifactWithExplicitReason() {

        ArtifactGenerationContext context =
                ArtifactGenerationContext.create(
                        ArtifactOrigin.STANDARD_WORD,
                        "sample.docx");

        context.ledger().blocked(
                context,
                ArtifactType.DDL,
                DatabasePlatform.DB2_ZOS,
                "TST.TEST_TABLE",
                "ArtifactOutcomeContractTest",
                "DB2_ZOS_DDL_BLOCKED: validation environment unavailable");

        ArtifactDescriptor descriptor =
                context.ledger().snapshot().getFirst();

        assertEquals(
                ArtifactStatus.BLOCKED,
                descriptor.status());

        assertEquals(
                ArtifactType.DDL,
                descriptor.type());

        assertEquals(
                DatabasePlatform.DB2_ZOS,
                descriptor.platform());

        assertEquals(
                "TST.TEST_TABLE",
                descriptor.logicalName());

        assertTrue(
                descriptor.relativePath().isEmpty());

        assertTrue(
                descriptor.mediaType().isEmpty());

        assertEquals(
                "DB2_ZOS_DDL_BLOCKED: validation environment unavailable",
                descriptor.outcomeReason());
    }

    @Test
    void ledgerMustRejectBlockedArtifactWithoutReason() {

        ArtifactGenerationContext context =
                ArtifactGenerationContext.create(
                        ArtifactOrigin.STANDARD_WORD,
                        "sample.docx");

        assertThrows(
                IllegalArgumentException.class,
                () -> context.ledger().blocked(
                        context,
                        ArtifactType.DDL,
                        DatabasePlatform.DB2_ZOS,
                        "TST.TEST_TABLE",
                        "ArtifactOutcomeContractTest",
                        "   "));
    }

    @Test
    void descriptorMustRejectBlockedArtifactWithoutReasonEvenWhenLedgerIsBypassed() {

        ArtifactProvenance provenance =
                new ArtifactProvenance(
                        ArtifactOrigin.STANDARD_WORD,
                        "sample.docx",
                        "ArtifactOutcomeContractTest");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactDescriptor(
                        ArtifactType.DDL,
                        DatabasePlatform.DB2_ZOS,
                        "TST.TEST_TABLE",
                        "",
                        "",
                        "generation-1",
                        ArtifactStatus.BLOCKED,
                        provenance,
                        ""));
    }

    @Test
    void blockedReasonMustBeNormalized() {

        ArtifactProvenance provenance =
                new ArtifactProvenance(
                        ArtifactOrigin.STANDARD_WORD,
                        "sample.docx",
                        "ArtifactOutcomeContractTest");

        ArtifactDescriptor descriptor =
                new ArtifactDescriptor(
                        ArtifactType.DDL,
                        DatabasePlatform.DB2_ZOS,
                        "TST.TEST_TABLE",
                        "",
                        "",
                        "generation-1",
                        ArtifactStatus.BLOCKED,
                        provenance,
                        "  DB2_ZOS_DDL_BLOCKED  ");

        assertEquals(
                "DB2_ZOS_DDL_BLOCKED",
                descriptor.outcomeReason());
    }
}