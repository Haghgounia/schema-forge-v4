package com.behsazan.schemaforge.validation.naming;

import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalObjectNamingAnalyzerTest {

    @Test
    void ignoresSourceIndexNameCollisionBecauseFormulaIncludesOwningTable() {
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addTable(tableWithIndex("T1", "IX_SHARED"))
                .addTable(tableWithIndex("T2", "IX_SHARED"))
                .build();
        PhysicalObjectNamingAnalyzer analyzer = new PhysicalObjectNamingAnalyzer();

        assertFalse(hasCollision(analyzer.analyze(schema, new OracleDialect())));
        assertFalse(hasCollision(analyzer.analyze(schema, new PostgreSqlDialect())));
        assertFalse(hasCollision(analyzer.analyze(schema, new Db2LuwDialect())));
        assertFalse(hasCollision(analyzer.analyze(schema, new Db2ZosDialect())));
        assertFalse(hasCollision(analyzer.analyze(schema, new SqlServerDialect())));
        assertFalse(hasCollision(analyzer.analyze(schema, new MySqlDialect())));
    }

    @Test
    void detectsRelationNamespaceCollisionBetweenTableAndSequenceForOracleAndPostgreSql() {
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", "SEQ_ORDER")
                        .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                        .build())
                .addSequence(new Sequence(QualifiedName.of("APP", "SEQ_ORDER"),
                        1, 1, null, null, false, null, Description.empty()))
                .build();
        PhysicalObjectNamingAnalyzer analyzer = new PhysicalObjectNamingAnalyzer();

        assertTrue(hasCollision(analyzer.analyze(schema, new OracleDialect())));
        assertTrue(hasCollision(analyzer.analyze(schema, new PostgreSqlDialect())));
    }

    @Test
    void reportsSourceIdentifierTooLongInsteadOfSilentlyRenamingBusinessTable() {
        String longTable = "T_" + "A".repeat(70);
        DatabaseSchema schema = DatabaseSchema.builder("APP")
                .addTable(Table.builder("APP", longTable)
                        .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                        .build())
                .build();

        var issues = new PhysicalObjectNamingAnalyzer().analyze(schema, new PostgreSqlDialect());

        assertTrue(issues.stream().anyMatch(issue -> "SOURCE_IDENTIFIER_TOO_LONG".equals(issue.code())));
    }

    private static Table tableWithIndex(String tableName, String indexName) {
        return Table.builder("APP", tableName)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addIndex(new Index(Identifier.of(indexName),
                        List.of(new IndexColumn(Identifier.of("ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .build();
    }

    private static boolean hasCollision(List<com.behsazan.schemaforge.specification.validation.ValidationIssue> issues) {
        return issues.stream().anyMatch(issue -> "PHYSICAL_NAME_COLLISION".equals(issue.code()));
    }
}
