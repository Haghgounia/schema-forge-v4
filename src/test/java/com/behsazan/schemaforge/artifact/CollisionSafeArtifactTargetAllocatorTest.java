package com.behsazan.schemaforge.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionSafeArtifactTargetAllocatorTest {
    private static final Path DDL = Path.of(
            "ddl", "oracle", "BIM.PROVINCES_20260823_010203_456.oracle.sql");

    @Test
    void firstReservationPreservesCanonicalPathAndCollisionUsesStableHashSuffix() {
        CollisionSafeArtifactTargetAllocator allocator = new CollisionSafeArtifactTargetAllocator();

        Path first = allocator.reserve(DDL, "a/provinces.docx");
        Path second = allocator.reserve(DDL, "b/provinces.docx");

        assertEquals(DDL, first);
        assertNotEquals(first, second);
        String name = second.getFileName().toString();
        assertTrue(name.matches(
                "BIM\\.PROVINCES__sf_[0-9a-f]{10}_20260823_010203_456\\.oracle\\.sql"), name);
    }

    @Test
    void equalCollisionInputsProduceSameVisibleSuffixAcrossAllocators() {
        CollisionSafeArtifactTargetAllocator left = new CollisionSafeArtifactTargetAllocator();
        CollisionSafeArtifactTargetAllocator right = new CollisionSafeArtifactTargetAllocator();

        left.reserve(DDL, "a/provinces.docx");
        right.reserve(DDL, "a/provinces.docx");
        Path leftCollision = left.reserve(DDL, "b/provinces.docx");
        Path rightCollision = right.reserve(DDL, "b/provinces.docx");

        assertEquals(leftCollision, rightCollision);
    }

    @Test
    void flywayMigrationCollisionFailsInsteadOfRenamingVersionedScript() {
        CollisionSafeArtifactTargetAllocator allocator = new CollisionSafeArtifactTargetAllocator();
        Path migration = Path.of("migration", "oracle", "V20260823010203001__BIM_PROVINCES_ALTER.sql");

        assertEquals(migration, allocator.reserve(migration, "a/provinces.docx"));
        assertThrows(IllegalStateException.class,
                () -> allocator.reserve(migration, "b/provinces.docx"));
    }
}
