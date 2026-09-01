package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.artifact.manifest.ArtifactManifestOutcomes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactRequestStatusContractTest {

    @Test
    void completedRequestWithoutBlockedOrFailedArtifactsMustBeSuccess() {

        ArtifactManifestOutcomes outcomes =
                new ArtifactManifestOutcomes(
                        5,
                        0,
                        0,
                        0);

        assertEquals(
                ArtifactRequestStatus.SUCCESS,
                outcomes.requestStatus());
    }

    @Test
    void skippedArtifactsAloneMustNotMakeRequestPartial() {

        ArtifactManifestOutcomes outcomes =
                new ArtifactManifestOutcomes(
                        5,
                        0,
                        3,
                        0);

        assertEquals(
                ArtifactRequestStatus.SUCCESS,
                outcomes.requestStatus());
    }

    @Test
    void blockedArtifactMustMakeCompletedRequestPartialSuccess() {

        ArtifactManifestOutcomes outcomes =
                new ArtifactManifestOutcomes(
                        5,
                        1,
                        0,
                        0);

        assertEquals(
                ArtifactRequestStatus.PARTIAL_SUCCESS,
                outcomes.requestStatus());
    }

    @Test
    void failedArtifactMustMakeCompletedRequestPartialSuccess() {

        ArtifactManifestOutcomes outcomes =
                new ArtifactManifestOutcomes(
                        5,
                        0,
                        0,
                        1);

        assertEquals(
                ArtifactRequestStatus.PARTIAL_SUCCESS,
                outcomes.requestStatus());
    }

    @Test
    void mixedBlockedSkippedAndFailedMustRemainPartialSuccess() {

        ArtifactManifestOutcomes outcomes =
                new ArtifactManifestOutcomes(
                        5,
                        1,
                        2,
                        1);

        assertEquals(
                ArtifactRequestStatus.PARTIAL_SUCCESS,
                outcomes.requestStatus());

        assertEquals(
                9,
                outcomes.total());
    }

    @Test
    void outcomeCountersMustNotAcceptNegativeValues() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactManifestOutcomes(
                        5,
                        -1,
                        0,
                        0));
    }
}