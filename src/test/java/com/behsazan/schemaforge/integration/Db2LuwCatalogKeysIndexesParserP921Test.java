package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Db2LuwCatalogKeysIndexesParserP921Test {

    @Test
    void ignoresPrimaryKeyWordsInsideDb2LuwAdaptationComments() {
        String ddl = """
                CREATE TABLE TSTSHMA.T_SAMPLE (
                  ID INTEGER NOT NULL /* [DB2/LUW PK NULLABILITY ADAPTATION] Db2 LUW requires every PRIMARY KEY column to be explicitly NOT NULL. */,
                  CODE INTEGER,
                  CONSTRAINT PK_T_SAMPLE PRIMARY KEY (ID),
                  CONSTRAINT UQ_T_SAMPLE UNIQUE (CODE)
                )
                """;

        List<?> constraints = Db2LuwCatalogKeysIndexesReconciliationP92IT
                .parseTableConstraints(ddl, 0, "sample.db2luw.sql");

        assertEquals(2, constraints.size(),
                "PRIMARY KEY wording inside a generated comment must not create an inline PK");
    }

    @Test
    void stillRecognizesRealInlinePrimaryAndUniqueConstraints() {
        String ddl = """
                CREATE TABLE TSTSHMA.T_INLINE (
                  ID INTEGER PRIMARY KEY,
                  CODE INTEGER UNIQUE
                )
                """;

        List<?> constraints = Db2LuwCatalogKeysIndexesReconciliationP92IT
                .parseTableConstraints(ddl, 0, "inline.db2luw.sql");

        assertEquals(2, constraints.size());
    }
}
