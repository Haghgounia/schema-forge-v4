package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7.7A acceptance gate for deterministic multi-table / run-all deployment ordering.
 *
 * <p>SchemaForge deliberately creates all tables before phase-3 foreign keys. Therefore
 * dependency safety does not require topologically sorting CREATE TABLE statements: even a
 * cycle remains deployable because cross-table foreign keys are deferred until every table
 * exists. This gate verifies that invariant across all registered core DBMS renderers.</p>
 */
class RunAllOrderingAcceptanceTest {
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void multiLevelCompositeAndMultipleParentDependenciesRenderAfterAllTablesForAllDialects() {
        DatabaseSchema schema = dependencyGraph(false);
        IntegratedSchemaDeploymentPlan plan = planner.plan(schema);

        assertEquals(5, plan.phase1Tables().size());
        assertEquals(3, plan.phase3ForeignKeys().size());
        assertEquals(0, plan.foreignKeyAnalysis().errorCount());
        assertEquals(3, plan.foreignKeyAnalysis().resolvedPhysicalForeignKeys());
        assertTrue(plan.foreignKeyAnalysis().deployable());

        assertEquals(List.of(
                        "TSTSHMA.AUX_PARENT",
                        "TSTSHMA.ISOLATED",
                        "TSTSHMA.LEAF_CHILD",
                        "TSTSHMA.MID_PARENT",
                        "TSTSHMA.ROOT_PARENT"),
                plan.phase1Tables().stream().map(table -> table.qualifiedName().toString()).toList());

        assertEquals(List.of(
                        "FK_LEAF_AUX",
                        "FK_LEAF_MID",
                        "FK_MID_ROOT"),
                plan.phase3ForeignKeys().stream()
                        .map(deployment -> deployment.foreignKey().name().value())
                        .toList());

        for (Dialect dialect : dialects()) {
            IntegratedSqlScript script = new IntegratedSqlRenderer(dialect).render(schema, plan);
            assertEquals(5, script.phase1TableStatements().size(), dialect.getClass().getSimpleName());
            assertEquals(3, script.phase3ForeignKeyStatements().size(), dialect.getClass().getSimpleName());
            assertEveryForeignKeyOccursAfterEveryCreateTable(script, dialect);
        }
    }

    @Test
    void planAndRenderedSqlAreDeterministicRegardlessOfInputInsertionOrder() {
        DatabaseSchema forward = dependencyGraph(false);
        DatabaseSchema reverse = dependencyGraph(true);

        IntegratedSchemaDeploymentPlan forwardPlan = planner.plan(forward);
        IntegratedSchemaDeploymentPlan reversePlan = planner.plan(reverse);

        assertEquals(
                forwardPlan.phase1Tables().stream().map(table -> table.qualifiedName().toString()).toList(),
                reversePlan.phase1Tables().stream().map(table -> table.qualifiedName().toString()).toList());
        assertEquals(
                forwardPlan.phase3ForeignKeys().stream().map(fk -> fk.foreignKey().name().value()).toList(),
                reversePlan.phase3ForeignKeys().stream().map(fk -> fk.foreignKey().name().value()).toList());

        for (Dialect dialect : dialects()) {
            String left = new IntegratedSqlRenderer(dialect).render(forward, forwardPlan).combinedSql();
            String right = new IntegratedSqlRenderer(dialect).render(reverse, reversePlan).combinedSql();
            assertEquals(left, right, dialect.getClass().getSimpleName());
        }
    }

    @Test
    void circularDependenciesRemainDeployableBecauseForeignKeysAreDeferredForAllDialects() {
        Table a = Table.builder("TSTSHMA", "A")
                .addColumn(column("ID"))
                .addColumn(column("B_ID"))
                .primaryKey(new PrimaryKey(id("PK_A"), List.of(id("ID"))))
                .addForeignKey(fk("FK_A_B", List.of("B_ID"), "B", List.of("ID")))
                .build();
        Table b = Table.builder("TSTSHMA", "B")
                .addColumn(column("ID"))
                .addColumn(column("A_ID"))
                .primaryKey(new PrimaryKey(id("PK_B"), List.of(id("ID"))))
                .addForeignKey(fk("FK_B_A", List.of("A_ID"), "A", List.of("ID")))
                .build();
        DatabaseSchema schema = DatabaseSchema.builder("R77_CYCLE").addTable(b).addTable(a).build();

        IntegratedSchemaDeploymentPlan plan = planner.plan(schema);

        assertTrue(plan.foreignKeyAnalysis().deployable());
        assertEquals(1, plan.foreignKeyAnalysis().cycleGroups());
        assertEquals(2, plan.phase1Tables().size());
        assertEquals(2, plan.phase3ForeignKeys().size());

        for (Dialect dialect : dialects()) {
            IntegratedSqlScript script = new IntegratedSqlRenderer(dialect).render(schema, plan);
            assertEveryForeignKeyOccursAfterEveryCreateTable(script, dialect);
        }
    }

    private static void assertEveryForeignKeyOccursAfterEveryCreateTable(IntegratedSqlScript script, Dialect dialect) {
        String sql = script.combinedSql();
        int lastCreate = sql.lastIndexOf("CREATE TABLE");
        int firstForeignKey = sql.indexOf("FOREIGN KEY");
        assertTrue(lastCreate >= 0, dialect.getClass().getSimpleName() + " missing CREATE TABLE");
        assertTrue(firstForeignKey > lastCreate,
                dialect.getClass().getSimpleName() + " emitted FK before all tables existed:\n" + sql);
    }

    private static DatabaseSchema dependencyGraph(boolean reverseInsertion) {
        Table root = Table.builder("TSTSHMA", "ROOT_PARENT")
                .addColumn(column("ID1"))
                .addColumn(column("ID2"))
                .primaryKey(new PrimaryKey(id("PK_ROOT_PARENT"), List.of(id("ID1"), id("ID2"))))
                .build();

        Table mid = Table.builder("TSTSHMA", "MID_PARENT")
                .addColumn(column("ID"))
                .addColumn(column("ROOT_ID1"))
                .addColumn(column("ROOT_ID2"))
                .primaryKey(new PrimaryKey(id("PK_MID_PARENT"), List.of(id("ID"))))
                .addForeignKey(fk("FK_MID_ROOT",
                        List.of("ROOT_ID1", "ROOT_ID2"), "ROOT_PARENT", List.of("ID1", "ID2")))
                .build();

        Table aux = Table.builder("TSTSHMA", "AUX_PARENT")
                .addColumn(column("ID"))
                .primaryKey(new PrimaryKey(id("PK_AUX_PARENT"), List.of(id("ID"))))
                .build();

        Table leaf = Table.builder("TSTSHMA", "LEAF_CHILD")
                .addColumn(column("ID"))
                .addColumn(column("MID_ID"))
                .addColumn(column("AUX_ID"))
                .primaryKey(new PrimaryKey(id("PK_LEAF_CHILD"), List.of(id("ID"))))
                .addForeignKey(fk("FK_LEAF_MID", List.of("MID_ID"), "MID_PARENT", List.of("ID")))
                .addForeignKey(fk("FK_LEAF_AUX", List.of("AUX_ID"), "AUX_PARENT", List.of("ID")))
                .build();

        Table isolated = Table.builder("TSTSHMA", "ISOLATED")
                .addColumn(column("ID"))
                .primaryKey(new PrimaryKey(id("PK_ISOLATED"), List.of(id("ID"))))
                .build();

        List<Table> tables = new ArrayList<>(List.of(root, mid, aux, leaf, isolated));
        if (reverseInsertion) Collections.reverse(tables);

        DatabaseSchema.Builder builder = DatabaseSchema.builder("R77_RUN_ALL");
        tables.forEach(builder::addTable);
        return builder.build();
    }

    private static ForeignKey fk(
            String name,
            List<String> columns,
            String referencedTable,
            List<String> referencedColumns) {
        return new ForeignKey(
                id(name),
                columns.stream().map(RunAllOrderingAcceptanceTest::id).toList(),
                QualifiedName.of(null, referencedTable),
                referencedColumns.stream().map(RunAllOrderingAcceptanceTest::id).toList(),
                ReferentialAction.NO_ACTION,
                ReferentialAction.NO_ACTION);
    }

    private static List<Dialect> dialects() {
        return List.of(
                new OracleDialect(),
                new PostgreSqlDialect(),
                new Db2ZosDialect(),
                new Db2LuwDialect(),
                new SqlServerDialect(),
                new MySqlDialect());
    }

    private static Column column(String name) {
        return Column.required(name, DataType.numeric("NUMBER", 19, 0));
    }

    private static Identifier id(String value) {
        return Identifier.of(value);
    }
}
