package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for test-only self-reference/cycle pilot selection. */
class SpecialDependencyPilotSelectorTest {
    private static final List<DatabasePlatform> PLATFORMS = List.of(
            DatabasePlatform.ORACLE, DatabasePlatform.POSTGRESQL, DatabasePlatform.SQLSERVER);

    private final SpecialDependencyPilotSelector selector = new SpecialDependencyPilotSelector();

    @Test
    void selectsCrossDialectSelfReference() {
        Table node = Table.builder("TSTSHMA", "NODE")
                .addColumn(required("ID"))
                .addColumn(nullable("PARENT_ID"))
                .primaryKey(pk("PK_NODE", "ID"))
                .addForeignKey(fk("FK_NODE_PARENT", "PARENT_ID", "NODE", "ID"))
                .build();

        Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> byTable = grouped(node);
        SpecialDependencyPilotSelector.SelfReferenceSelection selection =
                selector.selectSelfReference(byTable, 5, PLATFORMS);

        assertEquals("TSTSHMA.NODE", selection.seedTable());
        assertEquals(1, selection.selected().size());
        assertEquals(1, selection.analysis().selfReferences());
        assertTrue(selection.analysis().deployable());
    }

    @Test
    void selectsCoexistingTwoTableCycle() {
        Table a = Table.builder("TSTSHMA", "A")
                .addColumn(required("ID"))
                .addColumn(nullable("B_ID"))
                .primaryKey(pk("PK_A", "ID"))
                .addForeignKey(fk("FK_A_B", "B_ID", "B", "ID"))
                .build();
        Table b = Table.builder("TSTSHMA", "B")
                .addColumn(required("ID"))
                .addColumn(nullable("A_ID"))
                .primaryKey(pk("PK_B", "ID"))
                .addForeignKey(fk("FK_B_A", "A_ID", "A", "ID"))
                .build();

        Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> byTable = grouped(a, b);
        List<SpecialDependencyPilotSelector.CycleAssessment> result = selector.assessCycles(
                byTable,
                List.of(new HistoricalDependencyCoverage.CycleGroup(List.of("TSTSHMA.A", "TSTSHMA.B"))),
                5, 100, PLATFORMS);

        assertEquals(1, result.size());
        assertEquals(SpecialDependencyPilotSelector.CycleStatus.DEPLOYABLE_CYCLE, result.getFirst().status());
        assertEquals(2, result.getFirst().selected().size());
        assertEquals(1, result.getFirst().analysis().cycleGroups());
    }

    @Test
    void classifiesCycleAsHistoricalAggregateOnlyWhenVersionsCannotCoexist() {
        Table aOld = Table.builder("TSTSHMA", "A")
                .addColumn(required("ID"))
                .addColumn(nullable("B_ID"))
                .primaryKey(pk("PK_A_OLD", "ID"))
                .addForeignKey(fk("FK_A_B", "B_ID", "B", "ID"))
                .build();
        Table aNew = Table.builder("TSTSHMA", "A")
                .addColumn(required("ALT_ID"))
                .primaryKey(pk("PK_A_NEW", "ALT_ID"))
                .build();
        Table bOld = Table.builder("TSTSHMA", "B")
                .addColumn(required("ID"))
                .primaryKey(pk("PK_B_OLD", "ID"))
                .build();
        Table bNew = Table.builder("TSTSHMA", "B")
                .addColumn(required("ALT_ID"))
                .addColumn(nullable("A_ALT_ID"))
                .primaryKey(pk("PK_B_NEW", "ALT_ID"))
                .addForeignKey(fk("FK_B_A", "A_ALT_ID", "A", "ALT_ID"))
                .build();

        Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> byTable = new LinkedHashMap<>();
        add(byTable, occurrence(aOld, "a-old.schema.json"));
        add(byTable, occurrence(aNew, "a-new.schema.json"));
        add(byTable, occurrence(bOld, "b-old.schema.json"));
        add(byTable, occurrence(bNew, "b-new.schema.json"));

        List<SpecialDependencyPilotSelector.CycleAssessment> result = selector.assessCycles(
                byTable,
                List.of(new HistoricalDependencyCoverage.CycleGroup(List.of("TSTSHMA.A", "TSTSHMA.B"))),
                5, 100, PLATFORMS);

        assertEquals(SpecialDependencyPilotSelector.CycleStatus.HISTORICAL_AGGREGATE_ONLY,
                result.getFirst().status());
        assertTrue(result.getFirst().selected().isEmpty());
    }

    @Test
    void selfReferenceClosureIncludesExternalParent() {
        Table parent = Table.builder("TSTSHMA", "PARENT")
                .addColumn(required("ID"))
                .primaryKey(pk("PK_PARENT", "ID"))
                .build();
        Table node = Table.builder("TSTSHMA", "NODE")
                .addColumn(required("ID"))
                .addColumn(nullable("PARENT_NODE_ID"))
                .addColumn(nullable("PARENT_ID"))
                .primaryKey(pk("PK_NODE", "ID"))
                .addForeignKey(fk("FK_NODE_SELF", "PARENT_NODE_ID", "NODE", "ID"))
                .addForeignKey(fk("FK_NODE_PARENT", "PARENT_ID", "PARENT", "ID"))
                .build();

        SpecialDependencyPilotSelector.SelfReferenceSelection selection =
                selector.selectSelfReference(grouped(node, parent), 5, PLATFORMS);

        assertEquals(2, selection.selected().size());
        assertEquals(2, selection.analysis().resolvedPhysicalForeignKeys());
    }

    @Test
    void isolatesRealSelfReferenceWhenHistoricalExternalClosureIsUnavailable() {
        Table node = Table.builder("TSTSHMA", "NODE")
                .addColumn(required("ID"))
                .addColumn(nullable("PARENT_NODE_ID"))
                .addColumn(nullable("MISSING_PARENT_ID"))
                .primaryKey(pk("PK_NODE", "ID"))
                .addForeignKey(fk("FK_NODE_SELF", "PARENT_NODE_ID", "NODE", "ID"))
                .addForeignKey(fk("FK_NODE_MISSING", "MISSING_PARENT_ID", "MISSING_PARENT", "ID"))
                .build();

        SpecialDependencyPilotSelector.SelfReferenceAssessment assessment =
                selector.assessSelfReference(grouped(node), 5, PLATFORMS);

        assertEquals(SpecialDependencyPilotSelector.SelfReferenceStatus.DEPLOYABLE, assessment.status());
        assertEquals(SpecialDependencyPilotSelector.SelfReferenceMode.ISOLATED_SELF_REFERENCE,
                assessment.selection().mode());
        assertEquals(1, assessment.selection().omittedExternalPhysicalForeignKeys());
        assertEquals(1, assessment.selection().analysis().selfReferences());
        assertEquals(1, assessment.selection().analysis().resolvedPhysicalForeignKeys());
    }

    private static Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> grouped(Table... tables) {
        Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> result = new LinkedHashMap<>();
        for (Table table : tables) add(result, occurrence(table, table.qualifiedName().name().value() + ".schema.json"));
        return result;
    }

    private static void add(
            Map<String, List<SpecialDependencyPilotSelector.TableOccurrence>> grouped,
            SpecialDependencyPilotSelector.TableOccurrence occurrence) {
        grouped.computeIfAbsent(occurrence.tableKey(), ignored -> new java.util.ArrayList<>()).add(occurrence);
    }

    private static SpecialDependencyPilotSelector.TableOccurrence occurrence(Table table, String snapshot) {
        return new SpecialDependencyPilotSelector.TableOccurrence(
                SpecialDependencyPilotSelector.tableKey(table.qualifiedName()),
                table, List.of(), snapshot, snapshot.replace(".schema.json", ".doc"));
    }

    private static Column required(String name) {
        return Column.required(name, DataType.numeric("NUMBER", 12, 0));
    }

    private static Column nullable(String name) {
        return Column.nullable(name, DataType.numeric("NUMBER", 12, 0));
    }

    private static PrimaryKey pk(String name, String... columns) {
        return new PrimaryKey(Identifier.of(name), identifiers(columns));
    }

    @SuppressWarnings("unused")
    private static UniqueKey uk(String name, String... columns) {
        return new UniqueKey(Identifier.of(name), identifiers(columns), false, false);
    }

    private static ForeignKey fk(
            String name, String column, String referencedTable, String referencedColumn) {
        return new ForeignKey(
                Identifier.of(name), List.of(Identifier.of(column)),
                QualifiedName.of("TSTSHMA", referencedTable), List.of(Identifier.of(referencedColumn)),
                ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION);
    }

    private static List<Identifier> identifiers(String... names) {
        return java.util.Arrays.stream(names).map(Identifier::of).toList();
    }
}
