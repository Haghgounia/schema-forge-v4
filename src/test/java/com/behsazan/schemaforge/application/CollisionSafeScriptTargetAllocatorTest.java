package com.behsazan.schemaforge.application;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies collision-safe naming for legacy source names that normalize to the same SQL path. */
class CollisionSafeScriptTargetAllocatorTest {

    @Test
    void shouldKeepNormalNameAndDisambiguateOnlyTheCollidingSource() {
        CollisionSafeScriptTargetAllocator allocator =
                new CollisionSafeScriptTargetAllocator(new OutputFileNamer());
        Path directory = Path.of("target/out/oracle/doc_files_download");
        String timestamp = "20260809_021500_000";

        var withSpace = allocator.reserveDdl(
                directory,
                "ShopCard_Ngfsswchd.sd.spc.tb.CTERefundShopCardTrans ",
                DatabasePlatform.ORACLE,
                timestamp,
                "doc_files_download/ShopCard_Ngfsswchd.sd.spc.tb.CTERefundShopCardTrans .doc|sha-a");
        var withoutSpace = allocator.reserveDdl(
                directory,
                "ShopCard_Ngfsswchd.sd.spc.tb.CTERefundShopCardTrans",
                DatabasePlatform.ORACLE,
                timestamp,
                "doc_files_download/ShopCard_Ngfsswchd.sd.spc.tb.CTERefundShopCardTrans.doc|sha-b");

        assertFalse(withSpace.collisionResolved());
        assertTrue(withoutSpace.collisionResolved());
        assertNotEquals(withSpace.resolvedTarget(), withoutSpace.resolvedTarget());
        assertTrue(withoutSpace.resolvedTarget().getFileName().toString().contains("__sf_"));
        assertTrue(withoutSpace.resolvedTarget().getFileName().toString().endsWith(".oracle.sql"));
        assertEquals(2, allocator.reservationCount());
    }
}
