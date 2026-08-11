package com.behsazan.schemaforge.deployment;

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
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegratedSchemaDeploymentPlannerTest {
    private final IntegratedSchemaDeploymentPlanner planner = new IntegratedSchemaDeploymentPlanner();

    @Test
    void buildsDeterministicFourPhasePlanAndResolvesOwnerSchemaForeignKey() {
        Table customer = Table.builder("TSTSHMA", "CUSTOMER")
                .description("Customer master")
                .addColumn(column("ID"))
                .addColumn(column("NATIONAL_ID"))
                .primaryKey(new PrimaryKey(id("PK_CUSTOMER"), List.of(id("ID"))))
                .addUniqueKey(new UniqueKey(id("UK_CUSTOMER_NID"), List.of(id("NATIONAL_ID"))))
                .build();
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .addColumn(column("STATUS"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addCheck(new CheckConstraint(id("CHK_ACCOUNT_STATUS"), "STATUS >= 0"))
                .addIndex(new Index(id("IX_ACCOUNT_CUSTOMER"),
                        List.of(new IndexColumn(id("CUSTOMER_ID"), SortDirection.ASC)),
                        IndexType.NORMAL, Description.empty()))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .physicalOption("GRANTS", "SELECT TO APP_READER")
                .build();
        Sequence sequence = new Sequence(QualifiedName.of("TSTSHMA", "SEQ_ACCOUNT"),
                1, 1, null, null, false, null, Description.empty());

        DatabaseSchema schema = DatabaseSchema.builder("INTEGRATED")
                .addSequence(sequence)
                .addTable(customer)
                .addTable(account)
                .build();

        IntegratedSchemaDeploymentPlan plan = planner.plan(schema);

        assertEquals(1, plan.preTableSequences().size());
        assertEquals(List.of("TSTSHMA.ACCOUNT", "TSTSHMA.CUSTOMER"),
                plan.phase1Tables().stream().map(table -> table.qualifiedName().toString()).toList());
        assertEquals(1, plan.phase2CheckConstraints().size());
        assertEquals(1, plan.phase2UniqueKeys().size());
        assertEquals(1, plan.phase2Indexes().size());
        assertEquals(3, plan.phase2ObjectCount());
        assertEquals(1, plan.phase3ForeignKeys().size());
        assertEquals("TSTSHMA.CUSTOMER", plan.phase3ForeignKeys().getFirst().referencedTable().toString());
        assertEquals(2, plan.phase4MetadataTables().size());
        assertTrue(plan.foreignKeyAnalysis().deployable());
    }

    @Test
    void excludesLogicalForeignKeysFromDeploymentPhase() {
        Table local = Table.builder("TSTSHMA", "LOCAL_TABLE")
                .addColumn(column("ID"))
                .addColumn(column("REMOTE_ID"))
                .primaryKey(new PrimaryKey(id("PK_LOCAL_TABLE"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_LOCAL_REMOTE"), List.of(id("REMOTE_ID")),
                        QualifiedName.of("REMOTE", "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION,
                        false, false, false, true))
                .build();

        IntegratedSchemaDeploymentPlan plan = planner.plan(schema(local));

        assertEquals(0, plan.phase3ForeignKeys().size());
        assertEquals(1, plan.foreignKeyAnalysis().logicalForeignKeys());
        assertTrue(plan.foreignKeyAnalysis().deployable());
    }

    @Test
    void blocksPlanWhenReferencedTableIsMissing() {
        Table account = Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(column("ID"))
                .addColumn(column("CUSTOMER_ID"))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, "CUSTOMER"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> planner.plan(schema(account)));

        assertTrue(error.getMessage().startsWith("INTEGRATED_DEPLOYMENT_BLOCKED:"));
        assertTrue(error.getMessage().contains("MISSING_REFERENCED_TABLE"));
    }

    @Test
    void cycleRemainsDeployableBecauseForeignKeysArePostTablePhase() {
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

        IntegratedSchemaDeploymentPlan plan = planner.plan(schema(a, b));

        assertEquals(2, plan.phase1Tables().size());
        assertEquals(2, plan.phase3ForeignKeys().size());
        assertEquals(1, plan.foreignKeyAnalysis().cycleGroups());
        assertTrue(plan.foreignKeyAnalysis().deployable());
    }

    @Test
    void ordersForeignKeysDeterministicallyByOwnerAndConstraintName() {
        Table parent = tableWithPrimaryKey("TSTSHMA", "PARENT");
        Table child = Table.builder("TSTSHMA", "CHILD")
                .addColumn(column("ID"))
                .addColumn(column("P1_ID"))
                .addColumn(column("P2_ID"))
                .primaryKey(new PrimaryKey(id("PK_CHILD"), List.of(id("ID"))))
                .addForeignKey(new ForeignKey(id("FK_Z"), List.of(id("P1_ID")),
                        QualifiedName.of(null, "PARENT"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .addForeignKey(new ForeignKey(id("FK_A"), List.of(id("P2_ID")),
                        QualifiedName.of(null, "PARENT"), List.of(id("ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();

        IntegratedSchemaDeploymentPlan plan = planner.plan(schema(parent, child));

        assertEquals(List.of("FK_A", "FK_Z"), plan.phase3ForeignKeys().stream()
                .map(deployment -> deployment.foreignKey().name().value()).toList());
    }

    private static DatabaseSchema schema(Table... tables) {
        DatabaseSchema.Builder builder = DatabaseSchema.builder("TEST");
        for (Table table : tables) builder.addTable(table);
        return builder.build();
    }

    private static Table tableWithPrimaryKey(String schema, String table) {
        return Table.builder(schema, table)
                .addColumn(column("ID"))
                .primaryKey(new PrimaryKey(id("PK_" + table), List.of(id("ID"))))
                .build();
    }

    private static Column column(String name) {
        return Column.required(name, DataType.numeric("NUMBER", 19, 0));
    }

    private static Identifier id(String value) {
        return Identifier.of(value);
    }
}
