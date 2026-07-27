package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerIdentifierRenderer;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects SQL Server identifier folding and reserved-word delimiting. */
class SqlServerIdentifierRendererTest {
    private final SqlServerIdentifierRenderer renderer = new SqlServerIdentifierRenderer();

    @Test
    void shouldEmitSafeIdentifiersInCanonicalUpperCase() {
        assertEquals("CUSTOMER_ID", renderer.render(Identifier.of("customer_id")));
        assertFalse(renderer.requiresQuoting(Identifier.of("customer_id")));
    }

    @Test
    void shouldBracketReservedWords() {
        assertEquals("[ORDER]", renderer.render(Identifier.of("ORDER")));
        assertTrue(renderer.requiresQuoting(Identifier.of("ORDER")));
    }
}
