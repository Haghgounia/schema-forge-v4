package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoricalDependencyCoverageTest {

    private final HistoricalDependencyCoverage analyzer = new HistoricalDependencyCoverage();

    @Test
    void countsHistoricalDuplicatesWithoutResolvingThem() {
        Table first = table("TSTSHMA", "A");
        Table second = table("TSTSHMA", "A");

        HistoricalDependencyCoverage.Result result = analyzer.analyze(List.of(
                definition("a1", first), definition("a2", second)));

        assertEquals(2, result.tableDefinitions());
        assertEquals(1, result.distinctTables());
        assertEquals(1, result.duplicateOccurrences());
    }

    @Test
    void reportsSelfReferenceSeparatelyFromCycleGroups() {
        Table node = tableWithForeignKey("TSTSHMA", "NODE", "PARENT_ID",
                "FK_NODE_PARENT", "TSTSHMA", "NODE", "ID");

        HistoricalDependencyCoverage.Result result = analyzer.analyze(List.of(definition("node", node)));

        assertEquals(1, result.selfReferenceDefinitions());
        assertEquals(1, result.distinctSelfReferenceRelations());
        assertEquals(0, result.aggregateCycleGroups());
    }

    @Test
    void detectsAggregateMultiTableCycle() {
        Table a = tableWithForeignKey("TSTSHMA", "A", "REF_ID", "FK_A_B", "TSTSHMA", "B", "ID");
        Table b = tableWithForeignKey("TSTSHMA", "B", "REF_ID", "FK_B_C", "TSTSHMA", "C", "ID");
        Table c = tableWithForeignKey("TSTSHMA", "C", "REF_ID", "FK_C_A", "TSTSHMA", "A", "ID");

        HistoricalDependencyCoverage.Result result = analyzer.analyze(List.of(
                definition("a", a), definition("b", b), definition("c", c)));

        assertEquals(3, result.aggregateDependencyEdges());
        assertEquals(1, result.aggregateCycleGroups());
        assertEquals(3, result.aggregateCycleTables());
    }

    @Test
    void reportsMissingTargetWithoutAddingGraphEdge() {
        Table a = tableWithForeignKey("TSTSHMA", "A", "REF_ID", "FK_A_MISSING",
                "TSTSHMA", "MISSING", "ID");

        HistoricalDependencyCoverage.Result result = analyzer.analyze(List.of(definition("a", a)));

        assertEquals(1, result.missingReferencedTableDefinitions());
        assertEquals(0, result.aggregateDependencyEdges());
        assertEquals(0, result.aggregateCycleGroups());
    }

    private static HistoricalDependencyCoverage.Definition definition(String name, Table table) {
        DatabaseSchema schema = DatabaseSchema.builder("TEST").addTable(table).build();
        return new HistoricalDependencyCoverage.Definition(name + ".schema.json", name + ".doc", schema);
    }

    private static Table table(String schema, String name) {
        return Table.builder(schema, name)
                .addColumn(column("ID"))
                .primaryKey(new PrimaryKey(Identifier.of("PK_" + name), List.of(Identifier.of("ID")), false, false))
                .build();
    }

    private static Table tableWithForeignKey(
            String schema,
            String name,
            String localColumn,
            String foreignKeyName,
            String targetSchema,
            String targetTable,
            String targetColumn) {
        return Table.builder(schema, name)
                .addColumn(column("ID"))
                .addColumn(column(localColumn))
                .primaryKey(new PrimaryKey(Identifier.of("PK_" + name), List.of(Identifier.of("ID")), false, false))
                .addForeignKey(new ForeignKey(
                        Identifier.of(foreignKeyName),
                        List.of(Identifier.of(localColumn)),
                        QualifiedName.of(targetSchema, targetTable),
                        List.of(Identifier.of(targetColumn)),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private static Column column(String name) {
        return new Column(
                Identifier.of(name), DataType.numeric("NUMBER", 10, 0), true, new DefaultValue(null),
                Description.empty(), false, null, null);
    }
}
