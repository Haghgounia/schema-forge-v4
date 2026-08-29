package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void preservesValidLogicalSpellingIncludingRepeatedUnderscores() {
        Identifier logical = Identifier.of("IX_PATTERN_OPERATION__2");

        for (DatabasePlatform platform : DatabasePlatform.values()) {
            assertEquals(logical.value(),
                    PhysicalObjectNamePolicy.physicalIdentifier(platform, logical).value());
        }
    }

    @Test
    void shortensLongNamesDeterministicallyWithoutPlainTruncationCollisions() {
        String common = "FK_VERY_LONG_BUSINESS_TABLE_NAME_WITH_A_LONG_REFERENCE_COLUMN_PREFIX_";
        Identifier first = Identifier.of(common + "FIRST_PARENT_REFERENCE_COLUMN");
        Identifier second = Identifier.of(common + "SECOND_PARENT_REFERENCE_COLUMN");

        Identifier pgFirst = PhysicalObjectNamePolicy.physicalIdentifier(DatabasePlatform.POSTGRESQL, first);
        Identifier pgFirstAgain = PhysicalObjectNamePolicy.physicalIdentifier(DatabasePlatform.POSTGRESQL, first);
        Identifier pgSecond = PhysicalObjectNamePolicy.physicalIdentifier(DatabasePlatform.POSTGRESQL, second);
        Identifier mysqlFirst = PhysicalObjectNamePolicy.physicalIdentifier(DatabasePlatform.MYSQL, first);

        assertEquals(pgFirst, pgFirstAgain);
        assertEquals(63, pgFirst.value().length());
        assertEquals(64, mysqlFirst.value().length());
        assertNotEquals(pgFirst, pgSecond);
        assertTrue(pgFirst.value().matches("[A-Za-z][A-Za-z0-9_$#]*"));
        assertTrue(pgFirst.value().matches(".*_[0-9A-F]{10}$"));
    }
}
