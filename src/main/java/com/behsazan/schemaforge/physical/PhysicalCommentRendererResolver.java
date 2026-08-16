package com.behsazan.schemaforge.physical;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.physical.db2zos.Db2ZosPhysicalRenderer;
import com.behsazan.schemaforge.physical.oracle.OraclePhysicalRenderer;
import com.behsazan.schemaforge.physical.postgresql.PostgreSqlPhysicalRenderer;
import com.behsazan.schemaforge.physical.sqlserver.SqlServerPhysicalRenderer;

/** Resolves the small Phase-1 physical renderer without exposing DBMS options in service APIs. */
public final class PhysicalCommentRendererResolver {
    private PhysicalCommentRendererResolver() {
    }

    public static PhysicalCommentRenderer resolve(Dialect dialect) {
        if (dialect instanceof OracleDialect) {
            return new OraclePhysicalRenderer();
        }
        if (dialect instanceof PostgreSqlDialect) {
            return new PostgreSqlPhysicalRenderer();
        }
        if (dialect instanceof SqlServerDialect) {
            return new SqlServerPhysicalRenderer();
        }
        if (dialect instanceof Db2ZosDialect) {
            return new Db2ZosPhysicalRenderer();
        }
        return new PhysicalCommentRenderer() {
            @Override
            public String tableOptions(com.behsazan.schemaforge.domain.model.Table table, boolean activePlacementPresent) {
                return "";
            }

            @Override
            public String indexOptions(com.behsazan.schemaforge.domain.model.Table table,
                                       java.util.List<com.behsazan.schemaforge.domain.valueobject.Identifier> keyColumns,
                                       boolean activePlacementPresent) {
                return "";
            }
        };
    }
}
