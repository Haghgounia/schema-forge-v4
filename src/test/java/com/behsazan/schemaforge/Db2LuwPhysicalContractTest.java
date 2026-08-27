package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
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

class Db2LuwPhysicalContractTest {
    @Test
    void rendersLuwTableAndIndexPhysicalReviewBlocksWithoutGuessingInfrastructure() {
        Table table = Table.builder("APP", "CUSTOMER")
                .addColumn(Column.required("ID", DataType.simple("INTEGER")))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 32)))
                .addIndex(new Index(Identifier.of("IX_CUSTOMER_CODE"),
                        List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty(), List.of(), null,
                        Map.of("INDEX_TABLESPACE", "TS_INDEX",
                                "DB2_LUW_INDEX_PCTFREE", "15",
                                "DB2_LUW_INDEX_MINPCTUSED", "40",
                                "DB2_LUW_INDEX_REVERSE_SCANS", "ALLOW",
                                "DB2_LUW_INDEX_COMPRESSION", "YES",
                                "DB2_LUW_INDEX_PAGE_SPLIT", "LOW")))
                .physicalOption("TABLESPACE", "TS_DATA")
                .physicalOption("DB2_LUW_INDEX_TABLESPACE", "TS_INDEX")
                .physicalOption("DB2_LUW_LONG_TABLESPACE", "TS_LONG")
                .physicalOption("DB2_LUW_TABLE_PCTFREE", "5")
                .physicalOption("DB2_LUW_APPEND", "OFF")
                .physicalOption("DB2_LUW_VOLATILE", "NO")
                .physicalOption("DB2_LUW_TABLE_ORGANIZATION", "ROW")
                .physicalOption("DB2_LUW_ROW_COMPRESSION", "ADAPTIVE")
                .physicalOption("DB2_LUW_VALUE_COMPRESSION", "YES")
                .build();

        String sql = new DdlGenerator(new Db2LuwDialect()).generate(
                DatabaseSchema.builder("APP").addTable(table).build());

        assertTrue(sql.contains("-- DB2 LUW TABLE PHYSICAL OPTIONS"));
        assertTrue(sql.contains("PCTFREE 5"));
        assertTrue(sql.contains("APPEND OFF"));
        assertTrue(sql.contains("NOT VOLATILE CARDINALITY"));
        assertTrue(sql.contains("ORGANIZE BY ROW"));
        assertTrue(sql.contains("VALUE COMPRESSION"));
        assertTrue(sql.contains("COMPRESS YES ADAPTIVE"));
        assertTrue(sql.contains("INDEX IN TS_INDEX"));
        assertTrue(sql.contains("LONG IN TS_LONG"));
        assertTrue(sql.contains("IN TS_DATA"));

        assertTrue(sql.contains("-- DB2 LUW INDEX PHYSICAL OPTIONS"));
        assertTrue(sql.contains("PCTFREE 15"));
        assertTrue(sql.contains("MINPCTUSED 40"));
        assertTrue(sql.contains("ALLOW REVERSE SCANS"));
        assertTrue(sql.contains("COMPRESS YES"));
        assertTrue(sql.contains("PAGE SPLIT LOW"));
        assertTrue(sql.contains("IN TS_INDEX"));
    }
}
