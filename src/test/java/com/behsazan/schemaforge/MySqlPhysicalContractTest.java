package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlPhysicalContractTest {
    @Test
    void rendersMySqlPhysicalTableAndIndexReviewBlocksAndKeepsExplicitTablespaceActive() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 32)))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty(), List.of(), null,
                        Map.of("MYSQL_INDEX_TYPE", "BTREE")))
                .physicalOption("TABLESPACE", "TS_APP")
                .physicalOption("MYSQL_ENGINE", "InnoDB")
                .physicalOption("MYSQL_COLLATION", "utf8mb4_0900_ai_ci")
                .physicalOption("MYSQL_ROW_FORMAT", "DYNAMIC")
                .build();

        String sql = new DdlGenerator(new MySqlDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());

        assertTrue(sql.contains("-- MYSQL TABLE PHYSICAL OPTIONS"));
        assertTrue(sql.contains("ENGINE=InnoDB"));
        assertTrue(sql.contains("DEFAULT COLLATE=utf8mb4_0900_ai_ci"));
        assertTrue(sql.contains("ROW_FORMAT=DYNAMIC"));
        assertTrue(sql.contains("TABLESPACE `TS_APP`"));
        assertTrue(sql.contains("-- MYSQL INDEX PHYSICAL OPTIONS"));
        assertTrue(sql.contains("USING BTREE"));
    }
}
