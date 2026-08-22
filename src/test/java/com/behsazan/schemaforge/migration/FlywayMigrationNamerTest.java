package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayMigrationNamerTest {
    @Test
    void usesTimestampVersionAndQualifiedTableDescription() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T22:05:06Z"), ZoneOffset.UTC);
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .build();

        FlywayMigrationNamer namer = new FlywayMigrationNamer(clock);
        assertEquals("V20260821220506000__APP_CUSTOMER_ALTER.sql", namer.fileName(table));

        Table second = Table.builder("APP", "ORDER_HEADER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .build();
        assertEquals("V20260821220506001__APP_ORDER_HEADER_ALTER.sql", namer.fileName(second));
    }
}
