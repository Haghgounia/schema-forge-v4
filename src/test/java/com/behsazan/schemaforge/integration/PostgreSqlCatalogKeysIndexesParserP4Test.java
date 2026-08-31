package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlCatalogKeysIndexesParserP4Test {

    @Test
    void primaryKeyTextInsideCommentDoesNotCreateInlineConstraint() {
        String sql = """
                CREATE TABLE tstshma.sample (
                    id NUMERIC NOT NULL /* PRIMARY KEY is required by another dialect */, 
                    code VARCHAR(10),
                    CONSTRAINT pk_sample PRIMARY KEY (id)
                )
                """;
        int searchFrom = sql.toUpperCase().indexOf("SAMPLE") + "SAMPLE".length();
        List<PostgreSqlCatalogKeysIndexesP4IT.ExpectedConstraint> constraints =
                PostgreSqlCatalogKeysIndexesP4IT.parseCreateTableConstraints(sql, searchFrom, "sample.sql");
        assertEquals(1, constraints.size());
        assertEquals("P", constraints.get(0).type());
        assertEquals(List.of("id"), constraints.get(0).columns());
    }

    @Test
    void commentStripperPreservesQuotedText() {
        String sql = "SELECT '/* PRIMARY KEY */' AS x /* UNIQUE */";
        String stripped = PostgreSqlCatalogKeysIndexesP4IT.stripSqlCommentsPreservingQuotedText(sql);
        assertTrue(stripped.contains("'/* PRIMARY KEY */'"));
        assertTrue(!stripped.contains("UNIQUE"));
    }
}
