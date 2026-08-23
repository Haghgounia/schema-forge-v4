package com.behsazan.schemaforge.artifact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Allocates deterministic unique package-relative targets for colliding artifacts. */
public final class CollisionSafeArtifactTargetAllocator {
    private static final Pattern TIMESTAMP = Pattern.compile("_(\\d{8}_\\d{6}_\\d{3})(?=\\.)");
    private final Set<String> reserved = new LinkedHashSet<>();

    public Path reserve(Path requestedRelativePath, String sourceIdentity) {
        Objects.requireNonNull(requestedRelativePath, "requestedRelativePath must not be null");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity must not be null");
        Path normalized = requestedRelativePath.normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("artifact path must be package-relative: " + requestedRelativePath);
        }
        String key = portable(normalized).toLowerCase(Locale.ROOT);
        if (reserved.add(key)) {
            return normalized;
        }
        if (normalized.getNameCount() > 0
                && normalized.getName(0).toString().equalsIgnoreCase("migration")) {
            throw new IllegalStateException(
                    "Flyway migration path collision must not be renamed: " + portable(normalized));
        }

        String hash = stableSuffix(sourceIdentity + "::" + portable(normalized));
        Path parent = normalized.getParent();
        String fileName = normalized.getFileName().toString();
        int attempt = 0;
        while (true) {
            String suffix = "__sf_" + hash + (attempt == 0 ? "" : "_" + attempt);
            String candidateName = disambiguate(fileName, suffix);
            Path candidate = parent == null ? Path.of(candidateName) : parent.resolve(candidateName);
            String candidateKey = portable(candidate).toLowerCase(Locale.ROOT);
            if (reserved.add(candidateKey)) {
                return candidate;
            }
            attempt++;
        }
    }

    public int reservationCount() {
        return reserved.size();
    }

    private static String disambiguate(String fileName, String suffix) {
        Matcher matcher = TIMESTAMP.matcher(fileName);
        if (matcher.find()) {
            return fileName.substring(0, matcher.start()) + suffix + fileName.substring(matcher.start());
        }
        int dot = fileName.indexOf('.');
        return dot > 0
                ? fileName.substring(0, dot) + suffix + fileName.substring(dot)
                : fileName + suffix;
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

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
