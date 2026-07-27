package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosIdentifierRenderer;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects Db2 for z/OS ordinary and delimited identifier rendering. */
class Db2ZosIdentifierRendererTest {
    private final Db2ZosIdentifierRenderer renderer = new Db2ZosIdentifierRenderer();

    @Test
    void shouldFoldSafeOrdinaryIdentifiersToUppercase() {
        assertEquals("CUSTOMER_ID", renderer.render(Identifier.of("customer_id")));
        assertFalse(renderer.requiresQuoting(Identifier.of("customer_id")));
    }

    @Test
    void shouldQuoteReservedWordsAndDb2UnsafeCharacters() {
        assertEquals("\"ORDER\"", renderer.render(Identifier.of("ORDER")));
        assertEquals("\"CUSTOMER$ID\"", renderer.render(Identifier.of("CUSTOMER$ID")));
        assertEquals("\"CUSTOMER#ID\"", renderer.render(Identifier.of("CUSTOMER#ID")));
        assertTrue(renderer.requiresQuoting(Identifier.of("ORDER")));
    }
}
