package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.mariadb.MariaDbDialect;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 gate for MariaDB physical-option isolation and DBA review comments. */
class MariaDbPhysicalContractTest {

    @Test
    void doesNotActivateOracleOrMySqlStyleTablespacePlacement() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .physicalOption("TABLESPACE", "ORACLE_DATA_TS")
                .physicalOption("MARIADB_TABLESPACE", "TS_APP")
                .build();

        String sql = new DdlGenerator(new MariaDbDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());

        assertFalse(sql.contains("ORACLE_DATA_TS"));
        assertFalse(sql.contains("TABLESPACE `TS_APP`"));
        assertTrue(sql.contains("MARIADB_TABLESPACE=TS_APP was not activated"));
        assertTrue(sql.contains("No executable MariaDB TABLESPACE placement is emitted"));
    }

    @Test
    void rendersMariaDbPhysicalTableAndIndexReviewBlocksWithoutMySqlLeakage() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 32)))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty(), List.of(), null,
                        Map.of("MARIADB_INDEX_TYPE", "BTREE")))
                .physicalOption("MARIADB_ENGINE", "InnoDB")
                .physicalOption("MARIADB_CHARACTER_SET", "utf8mb4")
                .physicalOption("MARIADB_COLLATION", "utf8mb4_unicode_ci")
                .physicalOption("MARIADB_ROW_FORMAT", "DYNAMIC")
                .build();

        String sql = new DdlGenerator(new MariaDbDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());

        assertTrue(sql.contains("-- MARIADB TABLE PHYSICAL OPTIONS"));
        assertTrue(sql.contains("ENGINE=InnoDB"));
        assertTrue(sql.contains("DEFAULT CHARACTER SET=utf8mb4"));
        assertTrue(sql.contains("DEFAULT COLLATE=utf8mb4_unicode_ci"));
        assertTrue(sql.contains("ROW_FORMAT=DYNAMIC"));
        assertTrue(sql.contains("-- MARIADB INDEX PHYSICAL OPTIONS"));
        assertTrue(sql.contains("USING BTREE"));
        assertFalse(sql.contains("-- MYSQL TABLE PHYSICAL OPTIONS"));
        assertFalse(sql.contains("-- MYSQL INDEX PHYSICAL OPTIONS"));
    }
}
