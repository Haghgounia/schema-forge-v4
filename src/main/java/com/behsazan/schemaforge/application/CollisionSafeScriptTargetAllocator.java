package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Backward-compatible DDL target adapter over the central C5 artifact naming/collision policy.
 *
 * <p>The first request keeps the standard DDL filename. A collision is resolved by the same
 * deterministic {@code __sf_<hash>} convention used by packaged artifacts.</p>
 */
public final class CollisionSafeScriptTargetAllocator {
    private final ArtifactNamingPolicy artifactNamingPolicy;
    private final CollisionSafeArtifactTargetAllocator targetAllocator =
            new CollisionSafeArtifactTargetAllocator();

    public CollisionSafeScriptTargetAllocator(OutputFileNamer outputFileNamer) {
        this.artifactNamingPolicy = new ArtifactNamingPolicy(
                Objects.requireNonNull(outputFileNamer, "outputFileNamer must not be null"));
    }

    /** Reserves a unique DDL target for one logical source identity. */
    public Allocation reserveDdl(
            Path targetDirectory,
            String logicalName,
            DatabasePlatform platform,
            String timestamp,
            String sourceIdentity) {

        Objects.requireNonNull(targetDirectory, "targetDirectory must not be null");
        Objects.requireNonNull(logicalName, "logicalName must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity must not be null");

        String fileName = artifactNamingPolicy.ddlFileName(logicalName, platform, timestamp);
        Path requestedRelative = Path.of(fileName);
        Path resolvedRelative = targetAllocator.reserve(requestedRelative, sourceIdentity);
        Path requested = targetDirectory.resolve(requestedRelative);
        Path resolved = targetDirectory.resolve(resolvedRelative);
        return new Allocation(requested, resolved, !requestedRelative.equals(resolvedRelative));
    }

    public int reservationCount() {
        return targetAllocator.reservationCount();
    }

    /** Requested standard path and the actual unique path reserved for the source. */
    public record Allocation(Path requestedTarget, Path resolvedTarget, boolean collisionResolved) {
        public Allocation {
            Objects.requireNonNull(requestedTarget, "requestedTarget must not be null");
            Objects.requireNonNull(resolvedTarget, "resolvedTarget must not be null");
        }
    }
}
