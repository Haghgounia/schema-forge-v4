package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalObjectNamePolicyTest {

    @Test
    void usesTargetSpecificIdentifierLimitsForAllSixPlatforms() {
        assertEquals(128, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.ORACLE));
        assertEquals(63, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.POSTGRESQL));
        assertEquals(128, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.SQLSERVER));
        assertEquals(64, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.MYSQL));
        assertEquals(128, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.DB2_LUW));
        assertEquals(128, PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.DB2_ZOS));

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertEquals(PhysicalObjectNamePolicy.maximumLength(platform),
                    PhysicalObjectNamePolicy.maximumLength(DialectFactory.create(platform)),
                    platform + " platform/dialect limits must not diverge");
        }
    }

    @Test
    void preservesLogicalNameAtAndBelowTargetBoundary() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            int limit = PhysicalObjectNamePolicy.maximumLength(platform);
            Identifier below = Identifier.of("I" + "X".repeat(limit - 2));
            Identifier exact = Identifier.of("I" + "X".repeat(limit - 1));

            assertEquals(below, PhysicalObjectNamePolicy.physicalIdentifier(platform, below));
            assertEquals(exact, PhysicalObjectNamePolicy.physicalIdentifier(platform, exact));
            assertEquals(exact,
                    PhysicalObjectNamePolicy.physicalIdentifier(DialectFactory.create(platform), exact));
        }
    }

    @Test
    void truncatesOnlyWhenLimitIsExceededAndUsesTwelveHexSha256Suffix() throws Exception {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            int limit = PhysicalObjectNamePolicy.maximumLength(platform);
            String logical = "F" + "K".repeat(limit); // limit + 1
            Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(
                    platform, Identifier.of(logical));

            String hash = sha256Prefix(logical, 12);
            int stemLength = limit - 13; // '_' + 12 hex chars
            String expected = logical.substring(0, stemLength) + "_" + hash;

            assertEquals(limit, physical.value().length(), platform + " must consume the exact target limit");
            assertEquals(expected, physical.value(), platform + " truncate/hash formula changed");
            assertTrue(physical.value().matches("[A-Za-z][A-Za-z0-9_$#]*_[0-9A-F]{12}$"));
        }
    }

    @Test
    void truncationUsesExactLeftPrefixEvenWhenBoundaryEndsWithUnderscore() throws Exception {
        int limit = PhysicalObjectNamePolicy.maximumLength(DatabasePlatform.POSTGRESQL);
        int stemLength = limit - 13;
        String logical = "F" + "K".repeat(stemLength - 2) + "_" + "LONG_SUFFIX_1234567890";

        Identifier physical = PhysicalObjectNamePolicy.physicalIdentifier(
                DatabasePlatform.POSTGRESQL, Identifier.of(logical));

        String expected = logical.substring(0, stemLength) + "_" + sha256Prefix(logical, 12);
        assertEquals(expected, physical.value());
        assertEquals(limit, physical.value().length());
        assertTrue(physical.value().contains("__"), "exact LEFT(prefix) contract must not trim a boundary underscore");
    }

    @Test
    void shortensLongNamesDeterministicallyWithoutPlainTruncationCollisions() {
        String common = "FK_VERY_LONG_BUSINESS_TABLE_NAME_WITH_A_LONG_REFERENCE_COLUMN_PREFIX_";
        Identifier first = Identifier.of(common + "FIRST_PARENT_REFERENCE_COLUMN");
        Identifier second = Identifier.of(common + "SECOND_PARENT_REFERENCE_COLUMN");

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Identifier firstPhysical = PhysicalObjectNamePolicy.physicalIdentifier(platform, first);
            Identifier repeated = PhysicalObjectNamePolicy.physicalIdentifier(platform, first);
            Identifier dialectPhysical = PhysicalObjectNamePolicy.physicalIdentifier(
                    DialectFactory.create(platform), first);
            Identifier secondPhysical = PhysicalObjectNamePolicy.physicalIdentifier(platform, second);

            assertEquals(firstPhysical, repeated, platform + " physical naming must be deterministic");
            assertEquals(firstPhysical, dialectPhysical, platform + " platform/dialect shortening must match");
            assertTrue(firstPhysical.value().length() <= PhysicalObjectNamePolicy.maximumLength(platform));
            assertNotEquals(firstPhysical, secondPhysical, platform + " long names must not collide");
            if (first.value().length() > PhysicalObjectNamePolicy.maximumLength(platform)) {
                assertTrue(firstPhysical.value().matches(".*_[0-9A-F]{12}$"), firstPhysical.value());
            }
        }
    }

    private static String sha256Prefix(String value, int length) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().withUpperCase().formatHex(digest).substring(0, length);
    }
}
