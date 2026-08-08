package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Verifies the public legacy Word parser API and its immutable result contract.
 *
 * <p>The test ensures callers receive structured extraction data directly, without relying on
 * an intermediate CSV serialization round trip.</p>
 */
class SchemaForgeWordTableParserApiTest {
    @Test
    void exposesImmutablePublicResultWithoutCsvRoundTrip() throws Exception {
        Path source = resource("13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc");
        WordTableParseResult result = WordTableParser.create().parse(source.getParent(), source);

        assertTrue(result.acceptedTableDocument());
        assertNotNull(result.table());
        assertEquals("CTPIncomeParamActivityLog", result.table().technicalName());
        assertEquals(MetadataConfidence.TRUSTED, result.table().persianNameConfidence());
        assertEquals("EXPLICIT_ENTITY_HEADER", result.table().persianNameSource());
        assertFalse(result.columns().isEmpty());
    }

    private Path resource(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/" + name).toURI());
    }
}
