package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.physical.sqlserver.SqlServerPhysicalRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerPhysicalP4Test {

    @Test
    void rendersSourceDrivenStatisticsIncrementalAndXmlCompressionWithoutGuessing() {
        SqlServerPhysicalRenderer renderer = new SqlServerPhysicalRenderer();
        Table table = Table.builder("dbo", "XML_DOCS")
                .addColumn(Column.required("ID", DataType.simple("INT")))
                .addColumn(Column.nullable("PAYLOAD", DataType.simple("XML")))
                .physicalOption("SQLSERVER_TABLE_XML_COMPRESSION", "ON")
                .build();
        Index index = index("IX_XML", IndexType.NORMAL, Map.of(
                "SQLSERVER_INDEX_STATISTICS_INCREMENTAL", "ON",
                "SQLSERVER_INDEX_XML_COMPRESSION", "OFF"));

        String tableBlock = renderer.tableOptions(table, false);
        String indexBlock = renderer.indexOptions(table, index, List.of(Identifier.of("ID")), false);
        assertTrue(tableBlock.contains("XML_COMPRESSION = ON"));
        assertTrue(indexBlock.contains("STATISTICS_INCREMENTAL = ON"));
        assertTrue(indexBlock.contains("XML_COMPRESSION = OFF"));

        Table absent = Table.builder("dbo", "NO_SOURCE")
                .addColumn(Column.required("ID", DataType.simple("INT"))).build();
        String absentBlock = renderer.indexOptions(absent, index("IX_N", IndexType.NORMAL, Map.of()),
                List.of(Identifier.of("ID")), false);
        assertFalse(absentBlock.contains("STATISTICS_INCREMENTAL = ON"));
        assertFalse(absentBlock.contains("XML_COMPRESSION = ON"));
    }

    @Test
    void emitsClusteredAndNonclusteredOnlyFromExplicitCanonicalOrPhysicalEvidence() {
        Table table = Table.builder("dbo", "ORDERS")
                .addColumn(Column.required("ID", DataType.simple("INT")))
                .addColumn(Column.required("CODE", DataType.varchar("VARCHAR", 30)))
                .addColumn(Column.required("CREATED", DataType.simple("DATETIME2")))
                .addIndex(indexOn("IX_CLUSTERED", "CODE", IndexType.CLUSTERED, Map.of()))
                .addIndex(indexOn("IX_UNIQUE_NONCLUSTERED", "CREATED", IndexType.UNIQUE,
                        Map.of("SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED")))
                .build();
        String sql = new DdlGenerator(new SqlServerDialect())
                .generate(DatabaseSchema.builder("dbo").addTable(table).build());
        assertTrue(sql.contains("CREATE CLUSTERED INDEX IX_CLUSTERED"));
        assertTrue(sql.contains("CREATE UNIQUE NONCLUSTERED INDEX IX_UNIQUE_NONCLUSTERED"));
    }

    @Test
    void appliesBackingIndexOrganizationFromPkPhysicalOptions() {
        Table table = Table.builder("dbo", "PK_ORG")
                .addColumn(Column.required("ID", DataType.simple("INT")))
                .primaryKey(new PrimaryKey(Identifier.of("PK_ORG"), List.of(Identifier.of("ID")),
                        false, false, Map.of("SQLSERVER_INDEX_ORGANIZATION", "NONCLUSTERED")))
                .build();
        String sql = new DdlGenerator(new SqlServerDialect())
                .generate(DatabaseSchema.builder("dbo").addTable(table).build());
        assertTrue(sql.contains("PRIMARY KEY NONCLUSTERED (ID)"));
    }

    @Test
    void flagsIncrementalStatisticsOnFilteredIndexAsUnsupportedSourceEvidence() {
        SqlServerPhysicalRenderer renderer = new SqlServerPhysicalRenderer();
        Table table = Table.builder("dbo", "FILTERED_STATS")
                .addColumn(Column.required("ID", DataType.simple("INT"))).build();
        Index filtered = new Index(Identifier.of("IX_FILTERED"),
                List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                IndexType.NORMAL, Description.empty(), List.of(), "ID > 0",
                Map.of("SQLSERVER_INDEX_STATISTICS_INCREMENTAL", "ON"));
        String block = renderer.indexOptions(table, filtered, List.of(Identifier.of("ID")), false);
        assertTrue(block.contains("[SOURCE PHYSICAL ISSUE][SQLSERVER]"));
        assertTrue(block.contains("not supported for filtered indexes"));
    }

    private static Index index(String name, IndexType type, Map<String, String> physical) {
        return indexOn(name, "ID", type, physical);
    }

    private static Index indexOn(String name, String column, IndexType type, Map<String, String> physical) {
        return new Index(Identifier.of(name),
                List.of(new IndexColumn(Identifier.of(column), SortDirection.ASC)),
                type, Description.empty(), List.of(), null, physical);
    }
}
