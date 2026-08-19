package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlIdentifierRendererTest {
    @Test
    void shouldUseBackticksAndPreserveCanonicalSourceCase() {
        MySqlIdentifierRenderer renderer = new MySqlIdentifierRenderer();
        assertEquals("`PARTY`", renderer.render(Identifier.of("PARTY")));
        assertEquals("`PartyName`", renderer.render(Identifier.of("PartyName")));
    }
}
