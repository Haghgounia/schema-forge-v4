package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.postgresql.PostgreSqlPhysicalRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlPhysicalP3Test {

    @Test
    void rendersTableParallelWorkersOnlyFromExplicitEvidence() {
        PostgreSqlPhysicalRenderer renderer = new PostgreSqlPhysicalRenderer();
        Table explicit = baseTableBuilder("PG_PARALLEL")
                .physicalOption("POSTGRESQL_TABLE_PARALLEL_WORKERS", "4")
                .build();
        String explicitSql = renderer.tableOptions(explicit, false);
        assertTrue(explicitSql.contains("parallel_workers = 4"));
        assertTrue(explicitSql.contains("[SOURCE PHYSICAL] POSTGRESQL_TABLE_PARALLEL_WORKERS=4"));

        Table absent = baseTableBuilder("PG_PARALLEL_DEFAULT").build();
        String absentSql = renderer.tableOptions(absent, false);
        assertFalse(absentSql.contains("parallel_workers = 2"));
        assertTrue(absentSql.contains("when unset PostgreSQL derives it from relation size"));
    }

    @Test
    void rendersGistAndGinMethodSpecificOptionsFromObjectScopedEvidence() {
        PostgreSqlPhysicalRenderer renderer = new PostgreSqlPhysicalRenderer();
        Table table = baseTableBuilder("PG_METHOD_OPTIONS").build();

        Index gist = index("IX_GIST", Map.of(
                "POSTGRESQL_INDEX_METHOD", "gist",
                "POSTGRESQL_GIST_BUFFERING", "on",
                "POSTGRESQL_INDEX_FILLFACTOR", "70"));
        String gistSql = renderer.indexOptions(table, gist, List.of(Identifier.of("CODE")), false);
        assertTrue(gistSql.contains("Index access method (source/profile): gist"));
        assertTrue(gistSql.contains("fillfactor = 70"));
        assertTrue(gistSql.contains("buffering = on"));

        Index gin = index("IX_GIN", Map.of(
                "POSTGRESQL_INDEX_METHOD", "gin",
                "POSTGRESQL_GIN_FASTUPDATE", "off",
                "POSTGRESQL_GIN_PENDING_LIST_LIMIT", "8192"));
        String ginSql = renderer.indexOptions(table, gin, List.of(Identifier.of("CODE")), false);
        assertTrue(ginSql.contains("fastupdate = off"));
        assertTrue(ginSql.contains("gin_pending_list_limit = 8192"));
        assertFalse(ginSql.contains("fillfactor = 90"));
    }

    @Test
    void rendersBrinOptionsAndSurfacesMethodConflictsWithoutNormalization() {
        PostgreSqlPhysicalRenderer renderer = new PostgreSqlPhysicalRenderer();
        Table table = baseTableBuilder("PG_BRIN_OPTIONS").build();

        Index brin = index("IX_BRIN", Map.of(
                "POSTGRESQL_INDEX_METHOD", "brin",
                "POSTGRESQL_BRIN_PAGES_PER_RANGE", "64",
                "POSTGRESQL_BRIN_AUTOSUMMARIZE", "on"));
        String brinSql = renderer.indexOptions(table, brin, List.of(Identifier.of("CODE")), false);
        assertTrue(brinSql.contains("pages_per_range = 64"));
        assertTrue(brinSql.contains("autosummarize = on"));

        Index conflict = index("IX_CONFLICT", Map.of(
                "POSTGRESQL_INDEX_METHOD", "gin",
                "POSTGRESQL_BRIN_PAGES_PER_RANGE", "128"));
        String conflictSql = renderer.indexOptions(table, conflict, List.of(Identifier.of("CODE")), false);
        assertTrue(conflictSql.contains("[SOURCE PHYSICAL ISSUE][POSTGRESQL]"));
        assertTrue(conflictSql.contains("applies only to BRIN indexes"));
        assertTrue(conflictSql.contains("pages_per_range = <BRIN_PAGES_PER_RANGE>"));
        assertFalse(conflictSql.contains("pages_per_range = 128"));
    }

    private static Table.Builder baseTableBuilder(String name) {
        return Table.builder("ACC", name)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 10, 0)))
                .addColumn(Column.nullable("CODE", DataType.varchar("VARCHAR", 100)));
    }

    private static Index index(String name, Map<String, String> physicalOptions) {
        return new Index(
                Identifier.of(name),
                List.of(new IndexColumn(Identifier.of("CODE"), SortDirection.ASC)),
                IndexType.NORMAL,
                Description.empty(),
                List.of(),
                null,
                physicalOptions);
    }
}
