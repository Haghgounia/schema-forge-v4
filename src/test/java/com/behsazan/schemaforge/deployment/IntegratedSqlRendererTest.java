package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegratedSqlRendererTest {
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void rendersOracleInFourOrderedIntegratedPhases() {
        DatabaseSchema schema = schema();
        IntegratedSqlScript script = new IntegratedSqlRenderer(new OracleDialect())
                .render(schema, planner.plan(schema));

        assertCommonPhases(script);
        assertTrue(script.phase3ForeignKeyStatements().getFirst().contains("FOREIGN KEY"));
        assertTrue(script.phase4MetadataStatements().stream().anyMatch(sql -> sql.contains("COMMENT ON TABLE")));
        assertTrue(script.phase4MetadataStatements().stream().anyMatch(sql -> sql.startsWith("GRANT SELECT")));
    }

    @Test
    void rendersPostgreSqlIndexWithoutSchemaQualifyingIndexName() {
        DatabaseSchema schema = schema();
        IntegratedSqlScript script = new IntegratedSqlRenderer(new PostgreSqlDialect())
                .render(schema, planner.plan(schema));

        assertCommonPhases(script);
        String phase2 = String.join("\n", script.phase2TableLocalStatements());
        assertTrue(phase2.contains("CREATE INDEX ix_child_parent ON tstshma.child"), phase2);
        assertFalse(phase2.contains("CREATE INDEX tstshma.ix_child_parent"), phase2);
    }

    @Test
    void rendersSqlServerForeignKeyAsPostTableChunk() {
        DatabaseSchema schema = schema();
        IntegratedSqlScript script = new IntegratedSqlRenderer(new SqlServerDialect())
                .render(schema, planner.plan(schema));

        assertCommonPhases(script);
        String fk = script.phase3ForeignKeyStatements().getFirst();
        assertTrue(fk.contains("FOREIGN KEY"), fk);
        assertTrue(fk.contains("REFERENCES"), fk);
        assertTrue(fk.contains("CHECK CONSTRAINT"), fk);
    }


    @Test
    void blocksSqlServerIntegratedForeignKeyWhenRenderedColumnTypesDiffer() {
        Table parent = Table.builder("TSTSHMA", "PARENT_MISMATCH")
                .addColumn(Column.required("TYPECODE", DataType.numeric("NUMBER", 3, 0)))
                .primaryKey(new PrimaryKey(id("PK_PARENT_MISMATCH"), List.of(id("TYPECODE"))))
                .build();
        Table child = Table.builder("TSTSHMA", "CHILD_MISMATCH")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("TYPECODE", DataType.numeric("NUMBER", 2, 0)))
                .primaryKey(new PrimaryKey(id("PK_CHILD_MISMATCH"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_CHILD_MISMATCH_TYPE"), List.of(id("TYPECODE")),
                        QualifiedName.of(null, "PARENT_MISMATCH"), List.of(id("TYPECODE")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("INTEGRATED")
                .addTable(parent)
                .addTable(child)
                .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new IntegratedSqlRenderer(new SqlServerDialect()).render(schema, planner.plan(schema)));

        assertTrue(exception.getMessage().contains("SQLSERVER_FK_TYPE_MISMATCH"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DECIMAL(2,0)"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DECIMAL(3,0)"), exception.getMessage());
    }

    @Test
    void rendersCircularForeignKeysOnlyAfterBothTablesExist() {
        Table a = Table.builder("TSTSHMA", "A")
                .addColumn(column("ID"))
                .addColumn(column("B_ID"))
                .primaryKey(new PrimaryKey(id("PK_A"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_A_B"), List.of(id("B_ID")),
                        QualifiedName.of(null, "B"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
        Table b = Table.builder("TSTSHMA", "B")
                .addColumn(column("ID"))
                .addColumn(column("A_ID"))
                .primaryKey(new PrimaryKey(id("PK_B"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_B_A"), List.of(id("A_ID")),
                        QualifiedName.of(null, "A"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("INTEGRATED").addTable(a).addTable(b).build();

        IntegratedSqlScript script = new IntegratedSqlRenderer(new PostgreSqlDialect())
                .render(schema, planner.plan(schema));
        String sql = script.combinedSql();

        assertEquals(2, script.phase1TableStatements().size());
        assertEquals(2, script.phase3ForeignKeyStatements().size());
        assertTrue(sql.indexOf("FOREIGN KEY") > sql.lastIndexOf("CREATE TABLE"), sql);
    }

    @Test
    void combinedScriptAlwaysPlacesEveryTableBeforeForeignKeys() {
        DatabaseSchema schema = schema();
        IntegratedSqlScript script = new IntegratedSqlRenderer(new PostgreSqlDialect())
                .render(schema, planner.plan(schema));
        String sql = script.combinedSql();

        int parent = sql.indexOf("CREATE TABLE tstshma.parent");
        int child = sql.indexOf("CREATE TABLE tstshma.child");
        int fk = sql.indexOf("FOREIGN KEY");

        assertTrue(parent >= 0, sql);
        assertTrue(child >= 0, sql);
        assertTrue(fk > parent, sql);
        assertTrue(fk > child, sql);
    }

    private static void assertCommonPhases(IntegratedSqlScript script) {
        assertEquals(2, script.phase1TableStatements().size());
        assertTrue(script.phase2TableLocalStatements().size() >= 3);
        assertEquals(1, script.phase3ForeignKeyStatements().size());
        assertTrue(script.phase4MetadataStatements().size() >= 2);
        assertTrue(script.combinedSql().contains("PHASE 1 - TABLES"));
        assertTrue(script.combinedSql().contains("PHASE 3 - FOREIGN KEYS"));
    }

    private static DatabaseSchema schema() {
        Table parent = Table.builder("TSTSHMA", "PARENT")
                .description("Parent table")
                .addColumn(column("ID"))
                .addColumn(column("CODE"))
                .primaryKey(new PrimaryKey(id("PK_PARENT"), List.of(id("ID"))))
                .addUniqueKey(new UniqueKey(id("UK_PARENT_CODE"), List.of(id("CODE"))))
                .build();

        Table child = Table.builder("TSTSHMA", "CHILD")
                .description("Child table")
                .addColumn(column("ID"))
                .addColumn(column("PARENT_ID"))
                .addColumn(column("STATUS"))
                .primaryKey(new PrimaryKey(id("PK_CHILD"), List.of(id("ID"))))
                .addCheck(new CheckConstraint(id("CHK_CHILD_STATUS"), "STATUS >= 0"))
                .addIndex(new Index(id("IX_CHILD_PARENT"),
                        List.of(new IndexColumn(id("PARENT_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(id("FK_CHILD_PARENT"), List.of(id("PARENT_ID")),
                        QualifiedName.of(null, "PARENT"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .physicalOption("GRANTS", "SELECT TO APP_READER")
                .build();

        return DatabaseSchema.builder("INTEGRATED")
                .addTable(parent)
                .addTable(child)
                .build();
    }

    private static Column column(String name) {
        return Column.required(name, DataType.numeric("NUMBER", 19, 0));
    }

    private static Identifier id(String value) {
        return Identifier.of(value);
    }
}
