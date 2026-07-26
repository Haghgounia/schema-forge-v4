package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlIdentifierRenderer;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlIdentifierRendererTest {
    private final PostgreSqlIdentifierRenderer renderer = new PostgreSqlIdentifierRenderer();

    @Test
    void rendersOrdinaryIdentifierAsLowerCaseWithoutQuotes() {
        Identifier identifier = Identifier.of("CUSTOMER_ID");
        assertEquals("customer_id", renderer.render(identifier));
        assertFalse(renderer.requiresQuoting(identifier));
    }

    @Test
    void quotesReservedWord() {
        Identifier identifier = Identifier.of("USER");
        assertEquals("\"user\"", renderer.render(identifier));
        assertTrue(renderer.requiresQuoting(identifier));
    }

    @Test
    void quotesIdentifierContainingPostgreSqlUnsafeCharacter() {
        Identifier identifier = Identifier.of("ACCOUNT#NO");
        assertEquals("\"account#no\"", renderer.render(identifier));
        assertTrue(renderer.requiresQuoting(identifier));
    }
}
