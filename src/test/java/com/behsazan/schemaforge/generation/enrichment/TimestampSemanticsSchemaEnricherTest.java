package com.behsazan.schemaforge.generation.enrichment;

import com.behsazan.schemaforge.application.TimestampSemantics;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TimestampSemanticsSchemaEnricherTest {

    @Test
    void sourceAndWithoutTimeZoneLeaveUnqualifiedTimestampUnchanged() {
        DatabaseSchema schema = schemaWith(
                Column.required("EVENT_AT", DataType.numeric("TIMESTAMP", 6, null)));

        assertSame(schema, new TimestampSemanticsSchemaEnricher(TimestampSemantics.SOURCE).enrich(schema));
        assertSame(schema, new TimestampSemanticsSchemaEnricher(TimestampSemantics.WITHOUT_TIME_ZONE).enrich(schema));
    }

    @Test
    void withTimeZoneRewritesOnlyUnqualifiedTimestampAndPreservesPrecision() {
        DatabaseSchema schema = schemaWith(
                Column.required("EVENT_AT", DataType.numeric("TIMESTAMP", 6, null)),
                Column.required("EXPLICIT_TZ", DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", 4, null)),
                Column.required("EXPLICIT_LOCAL", DataType.simple("TIMESTAMP_WITH_LOCAL_TIME_ZONE")),
                Column.required("BUSINESS_DATE", DataType.simple("DATE")));

        DatabaseSchema enriched = new TimestampSemanticsSchemaEnricher(
                TimestampSemantics.WITH_TIME_ZONE).enrich(schema);

        assertType(enriched, "EVENT_AT", "TIMESTAMP_WITH_TIME_ZONE", 6);
        assertType(enriched, "EXPLICIT_TZ", "TIMESTAMP_WITH_TIME_ZONE", 4);
        assertType(enriched, "EXPLICIT_LOCAL", "TIMESTAMP_WITH_LOCAL_TIME_ZONE", null);
        assertType(enriched, "BUSINESS_DATE", "DATE", null);
    }

    @Test
    void withLocalTimeZoneDoesNotOverrideExplicitSourceQualifier() {
        DatabaseSchema schema = schemaWith(
                Column.required("EVENT_AT", DataType.simple("TIMESTAMP")),
                Column.required("EXPLICIT_TZ", DataType.simple("TIMESTAMP_WITH_TIME_ZONE")));

        DatabaseSchema enriched = new TimestampSemanticsSchemaEnricher(
                TimestampSemantics.WITH_LOCAL_TIME_ZONE).enrich(schema);

        assertType(enriched, "EVENT_AT", "TIMESTAMP_WITH_LOCAL_TIME_ZONE", null);
        assertType(enriched, "EXPLICIT_TZ", "TIMESTAMP_WITH_TIME_ZONE", null);
    }

    private static DatabaseSchema schemaWith(Column... columns) {
        Table.Builder table = Table.builder("APP", "EVENTS");
        for (Column column : columns) table.addColumn(column);
        return DatabaseSchema.builder("APP").addTable(table.build()).build();
    }

    private static void assertType(DatabaseSchema schema, String column, String type, Integer precision) {
        Column actual = schema.tables().getFirst().findColumn(column).orElseThrow();
        assertEquals(type, actual.dataType().name().normalized());
        assertEquals(precision, actual.dataType().precision());
    }
}
