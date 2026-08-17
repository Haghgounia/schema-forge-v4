package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
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

class Db2ZosIndexBuildOptionsP7Test {

    @Test
    void rendersExplicitDefineAndDeferWithoutInventingDefaults() {
        String sql = ddl(
                Map.of("DEFINE", "YES", "DEFER", "NO"),
                Map.of());

        assertTrue(sql.contains(" DEFINE YES DEFER NO;"));
        assertFalse(sql.contains("[INDEX BUILD ISSUE][DB2/ZOS]"));
    }

    @Test
    void emitsDefineNoOnlyWhenExplicitStogroupEvidenceExists() {
        String sql = ddl(
                Map.of("DEFINE", "NO"),
                Map.of("DB2_INDEX_STOGROUP", "SG_APP"));

        assertTrue(sql.contains(" DEFINE NO;"));
        assertTrue(sql.contains("[INDEX BUILD REVIEW][DB2/ZOS] DEFINE NO is explicit"));
    }

    @Test
    void rejectsDefineNoWithoutExplicitStogroupEvidence() {
        String sql = ddl(
                Map.of("DEFINE", "NO"),
                Map.of());

        assertTrue(sql.contains("[INDEX BUILD ISSUE][DB2/ZOS] DEFINE=NO requires explicit DB2_INDEX_STOGROUP/INDEX_STOGROUP evidence"));
        assertFalse(sql.contains(" DEFINE NO;"));
    }

    @Test
    void warnsWhenDeferredBuildCanLeaveAPopulatedTableRebuildPending() {
        String sql = ddl(
                Map.of("DEFER", "YES"),
                Map.of());

        assertTrue(sql.contains(" DEFER YES;"));
        assertTrue(sql.contains("[INDEX BUILD REVIEW][DB2/ZOS] DEFER YES is explicit"));
        assertTrue(sql.contains("rebuild-pending status"));
    }

    private String ddl(Map<String, String> buildOptions, Map<String, String> physicalOptions) {
        Index index = new Index(
                Identifier.of("IX_T_C"),
                List.of(new IndexColumn(Identifier.of("C"), SortDirection.ASC)),
                IndexType.NORMAL,
                Description.empty(),
                List.of(),
                null,
                physicalOptions,
                buildOptions);

        Table table = Table.builder("APP", "T")
                .addColumn(Column.required("C", DataType.simple("INTEGER")))
                .addIndex(index)
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();
        return new DdlGenerator(new Db2ZosDialect()).generate(schema);
    }
}
