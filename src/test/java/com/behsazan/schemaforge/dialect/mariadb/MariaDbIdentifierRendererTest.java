package com.behsazan.schemaforge.dialect.mariadb;

import com.behsazan.schemaforge.domain.valueobject.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDbIdentifierRendererTest {
    @Test
    void shouldUseBackticksAndPreserveCanonicalSourceCase() {
        MariaDbIdentifierRenderer renderer = new MariaDbIdentifierRenderer();

        assertEquals("`PARTY`", renderer.render(Identifier.of("PARTY")));
        assertEquals("`PartyName`", renderer.render(Identifier.of("PartyName")));
    }
}
