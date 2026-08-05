package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyWordSpecificationParserMappingTest {

    @Test
    void mapsLegacyDocToCanonicalSchemaAndOracleDdl() throws Exception {
        Path source = Path.of(getClass().getResource(
                "/13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc").toURI());

        var schema = new LegacyWordSpecificationParser().parse(source.getParent(), source, "DPS");

        assertEquals("DPS", schema.name().normalized());
        assertEquals("LEGACY_WORD", schema.metadata().get("source.parser"));
        assertEquals("REST_PARAMETER", schema.metadata().get("source.schemaSource"));
        assertEquals(1, schema.tables().size());
        var table = schema.tables().getFirst();
        assertEquals("CTPINCOMEPARAMACTIVITYLOG", table.qualifiedName().name().normalized());
        assertFalse(table.columns().isEmpty());
        assertFalse(table.persianName().isEmpty());

        PreparedSchema prepared = new SchemaPreparationService().prepare(schema);
        String sql = new DdlGenerator(new OracleDialect())
                .generate(prepared.schema(), prepared.validationReport());
        assertTrue(sql.contains("CREATE TABLE DPS.CTPINCOMEPARAMACTIVITYLOG"));
    }
}
