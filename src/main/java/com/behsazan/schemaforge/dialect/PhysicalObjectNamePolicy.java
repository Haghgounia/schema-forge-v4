package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic physical naming policy for generated database objects.
 *
 * <p>The canonical/logical identifier is preserved whenever the target DBMS can represent it.
 * When a DBMS-specific length limit would be exceeded, the visible prefix/stem is retained and
 * a stable hash suffix is appended. This avoids the collision-prone behaviour of plain
 * truncation and keeps logical names portable across all supported engines.</p>
 *
 * <p>This policy is intentionally used for generated/supporting objects (constraints, indexes,
 * sequences, triggers, etc.), not for business table/column names. Table/column identifiers are
 * source-model identifiers and must be validated rather than silently renamed.</p>
 */
public final class PhysicalObjectNamePolicy {
    private static final int DEFAULT_MAX = 128;
    private static final int POSTGRESQL_MAX = 63;
    private static final int MYSQL_MAX = 64;
    private static final int HASH_HEX_LENGTH = 12;

    private PhysicalObjectNamePolicy() {
    }

    public static Identifier physicalIdentifier(Dialect dialect, Identifier logical) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(logical, "logical identifier must not be null");
        return Identifier.of(fit(logical.value(), maximumLength(dialect)));
    }

    public static Identifier physicalIdentifier(DatabasePlatform platform, Identifier logical) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(logical, "logical identifier must not be null");
        return Identifier.of(fit(logical.value(), maximumLength(platform)));
    }

    public static String physicalValue(Dialect dialect, String logical) {
        Objects.requireNonNull(logical, "logical identifier must not be null");
        return fit(logical, maximumLength(dialect));
    }

    public static int maximumLength(DatabasePlatform platform) {
        return switch (platform) {
            case POSTGRESQL -> POSTGRESQL_MAX;
            case MYSQL -> MYSQL_MAX;
            case ORACLE, SQLSERVER, DB2_LUW, DB2_ZOS -> DEFAULT_MAX;
        };
    }

    public static int maximumLength(Dialect dialect) {
        String name = dialect.name().replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (name.contains("POSTGRES")) return POSTGRESQL_MAX;
        if (name.contains("MYSQL")) return MYSQL_MAX;
        return DEFAULT_MAX;
    }

    static String fit(String logical, int maximumLength) {
        String value = logical.trim();
        if (value.length() <= maximumLength) return value;

        String hash = stableHash(value);
        int stemLength = maximumLength - HASH_HEX_LENGTH - 1;
        if (stemLength < 1) {
            throw new IllegalArgumentException("identifier maximum length is too small: " + maximumLength);
        }
        String stem = value.substring(0, stemLength);
        return stem + "_" + hash;
    }

    private static String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest).substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
