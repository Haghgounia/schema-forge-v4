package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTimezoneAwareTimestampAdaptationTest {

    @Test
    void shouldRenderTimezoneAwareTimestampAsExplicitTextEnvelopeWithDbaWarning() {
        Column grantedAt = new Column(Identifier.of("GRANTED_AT"),
                DataType.simple("TIMESTAMP_WITH_TIME_ZONE"),
                false, null, Description.empty(), false, 1);
        Table table = Table.builder("COL", "PARTY_CONSENT")
                .addColumn(grantedAt)
                .build();

        String sql = new DdlGenerator(new MySqlDialect()).generate(
                DatabaseSchema.builder("COL").addTable(table).build());

        assertTrue(sql.contains("`GRANTED_AT` VARCHAR(128) NOT NULL"));
        assertTrue(sql.contains("[MYSQL-TSTZ-TEXT-001]"));
        assertTrue(sql.contains("[WARNING] MYSQL_TIMEZONE_TIMESTAMP_TEXT_ADAPTATION"));
        assertTrue(!sql.contains("[ERROR] MYSQL_TIMEZONE_TIMESTAMP_UNSUPPORTED"));
        assertTrue(sql.contains("canonical datatype remains timezone-aware"));
    }

    @Test
    void shouldKeepStrictLogicalTypeMapperLosslessContract() {
        MySqlTypeMapper mapper = new MySqlTypeMapper();
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));
    }

    @Test
    void shouldNotSilentlyAdaptLocalTimeZoneSemantics() {
        Column localTs = new Column(Identifier.of("LOCAL_TS"),
                DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE"),
                true, null, Description.empty(), false, 1);
        Table table = Table.builder("COL", "T")
                .addColumn(localTs)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new DdlGenerator(new MySqlDialect()).generate(
                        DatabaseSchema.builder("COL").addTable(table).build()));
    }

    @Test
    void shouldLeaveOrdinaryTimestampMappingUnchanged() {
        Column createdAt = new Column(Identifier.of("CREATED_AT"),
                DataType.simple("TIMESTAMP"),
                true, null, Description.empty(), false, 1);
        assertEquals("DATETIME", new MySqlDialect().sqlType(createdAt));
    }
}
