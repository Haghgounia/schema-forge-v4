package com.behsazan.schemaforge.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Allocates unique SQL output targets within one generation run without changing normal file names.
 *
 * <p>The first request keeps the standard {@link OutputFileNamer} result. If another source resolves
 * to the same path, a deterministic {@code __sf_<hash>} suffix is added to the logical name. This
 * preserves the one-source-document/one-SQL-file contract even when legacy source names differ only
 * by whitespace that the normal naming policy trims.</p>
 */
public final class CollisionSafeScriptTargetAllocator {
    private final OutputFileNamer outputFileNamer;
    private final Set<Path> reservedTargets = new LinkedHashSet<>();

    public CollisionSafeScriptTargetAllocator(OutputFileNamer outputFileNamer) {
        this.outputFileNamer = Objects.requireNonNull(outputFileNamer, "outputFileNamer must not be null");
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

        String requestedFileName = outputFileNamer.scriptFileName(
                logicalName, platform, OutputFileNamer.ScriptKind.DDL, timestamp);
        Path requested = targetDirectory.resolve(requestedFileName);
        if (reserve(requested)) {
            return new Allocation(requested, requested, false);
        }

        String normalizedLogicalName = logicalName.strip();
        if (normalizedLogicalName.isEmpty()) {
            normalizedLogicalName = "schemaforge";
        }
        String suffix = stableSuffix(sourceIdentity);
        int attempt = 0;
        while (true) {
            String disambiguatedLogicalName = normalizedLogicalName + "__sf_" + suffix
                    + (attempt == 0 ? "" : "_" + attempt);
            String resolvedFileName = outputFileNamer.scriptFileName(
                    disambiguatedLogicalName, platform, OutputFileNamer.ScriptKind.DDL, timestamp);
            Path resolved = targetDirectory.resolve(resolvedFileName);
            if (reserve(resolved)) {
                return new Allocation(requested, resolved, true);
            }
            attempt++;
        }
    }

    public int reservationCount() {
        return reservedTargets.size();
    }

    private boolean reserve(Path target) {
        return reservedTargets.add(target.toAbsolutePath().normalize());
    }

    private static String stableSuffix(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(10);
            for (int i = 0; i < 5; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** Requested standard path and the actual unique path reserved for the source. */
    public record Allocation(Path requestedTarget, Path resolvedTarget, boolean collisionResolved) {
        public Allocation {
            Objects.requireNonNull(requestedTarget, "requestedTarget must not be null");
            Objects.requireNonNull(resolvedTarget, "resolvedTarget must not be null");
        }
    }
}
