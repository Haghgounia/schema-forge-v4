package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards MySQL CREATE-time index compatibility for EA AutoNum columns. */
class MySqlAutoIncrementSupportingIndexTest {

    @Test
    void shouldCreateSupportingIndexWhenAutoIncrementHasNoPrimaryKey() {
        Column identity = identity("PARTY_STATUS_HISTORY_ID", 1);
        Column partyId = numeric("PARTY_ID", 2);
        Table table = Table.builder("COL", "PARTY_STATUS_HISTORY")
                .addColumn(identity)
                .addColumn(partyId)
                .build();

        String sql = generate(table);

        assertTrue(sql.contains(
                "`PARTY_STATUS_HISTORY_ID` BIGINT UNSIGNED AUTO_INCREMENT NOT NULL"));
        assertTrue(sql.contains(
                "KEY `SF_AI_PARTY_STATUS_HISTORY_ID` (`PARTY_STATUS_HISTORY_ID`)"));
        assertTrue(sql.contains("[MYSQL-AUTO-INDEX-001]"));
        assertTrue(sql.contains("canonical key semantics unchanged"));
    }

    @Test
    void shouldCreateSupportingIndexWhenAutoIncrementIsNotFirstInCompositePrimaryKey() {
        Column identity = identity("EVENT_ID", 2);
        Column tenantId = numeric("TENANT_ID", 1);
        Table table = Table.builder("COL", "TENANT_EVENT")
                .addColumn(tenantId)
                .addColumn(identity)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_TENANT_EVENT"),
                        List.of(Identifier.of("TENANT_ID"), Identifier.of("EVENT_ID"))))
                .build();

        String sql = generate(table);

        assertTrue(sql.contains("CONSTRAINT `PK_TENANT_EVENT` PRIMARY KEY (`TENANT_ID`,`EVENT_ID`)"));
        assertTrue(sql.contains("KEY `SF_AI_EVENT_ID` (`EVENT_ID`)"));
    }

    @Test
    void shouldNotCreateSupportingIndexWhenPrimaryKeyAlreadyStartsWithAutoIncrement() {
        Column identity = identity("EVENT_ID", 1);
        Column tenantId = numeric("TENANT_ID", 2);
        Table table = Table.builder("COL", "EVENT")
                .addColumn(identity)
                .addColumn(tenantId)
                .primaryKey(new PrimaryKey(
                        Identifier.of("PK_EVENT"),
                        List.of(Identifier.of("EVENT_ID"), Identifier.of("TENANT_ID"))))
                .build();

        String sql = generate(table);

        assertTrue(sql.contains("CONSTRAINT `PK_EVENT` PRIMARY KEY (`EVENT_ID`,`TENANT_ID`)"));
        assertFalse(sql.contains("MYSQL-AUTO-INDEX-001"));
        assertFalse(sql.contains("SF_AI_EVENT_ID"));
    }

    private String generate(Table table) {
        DatabaseSchema schema = DatabaseSchema.builder("COL").addTable(table).build();
        return new DdlGenerator(new MySqlDialect(), schema).generate(schema);
    }

    private Column identity(String name, int position) {
        return new Column(
                Identifier.of(name),
                DataType.numeric("NUMBER", 19, 0),
                false,
                null,
                Description.empty(),
                true,
                position);
    }

    private Column numeric(String name, int position) {
        return new Column(
                Identifier.of(name),
                DataType.numeric("NUMBER", 19, 0),
                false,
                null,
                Description.empty(),
                false,
                position);
    }
}
