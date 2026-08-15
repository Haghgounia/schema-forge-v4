package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidBatchDiagramExporterTest {

    @Test
    void exportsRelationsAcrossUniqueTables() {
        Table parent = parent("APP", "PARENT");
        Table child = child("APP", "CHILD", "PARENT");

        MermaidBatchDiagramExporter.Result result = new MermaidBatchDiagramExporter()
                .export(List.of(parent, child));

        assertEquals(2, result.tableDefinitions());
        assertEquals(2, result.distinctTableNames());
        assertEquals(0, result.duplicateTableNames());
        assertEquals(2, result.exportedTables());
        assertEquals(1, result.physicalForeignKeys());
        assertEquals(1, result.resolvedPhysicalForeignKeys());
        assertTrue(result.dependency().contains("SF_APP_CHILD -->|FK_CHILD_PARENT| SF_APP_PARENT"),
                result.dependency());
        assertTrue(result.er().contains("SF_APP_PARENT ||--o{ SF_APP_CHILD"), result.er());
    }

    @Test
    void excludesEveryDuplicateDefinitionWithoutSelectingAVersion() {
        Table duplicateV1 = parent("APP", "PARENT");
        Table duplicateV2 = parent("APP", "PARENT");
        Table child = child("APP", "CHILD", "PARENT");

        MermaidBatchDiagramExporter.Result result = new MermaidBatchDiagramExporter()
                .export(List.of(duplicateV1, duplicateV2, child));

        assertEquals(3, result.tableDefinitions());
        assertEquals(2, result.distinctTableNames());
        assertEquals(1, result.duplicateTableNames());
        assertEquals(1, result.exportedTables());
        assertEquals(1, result.physicalForeignKeys());
        assertEquals(0, result.resolvedPhysicalForeignKeys());
        assertFalse(result.dependency().contains("APP.PARENT"), result.dependency());
        assertTrue(result.dependency().contains("APP.CHILD"), result.dependency());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("INPUT_DUPLICATE_TABLE")
                        && issue.sourceTable().equals("APP.PARENT")
                        && issue.occurrences() == 2));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("INPUT_DUPLICATE_TABLE_TARGET")
                        && issue.sourceTable().equals("APP.CHILD")
                        && issue.targetTable().equals("APP.PARENT")));
    }

    @Test
    void reportsMissingExternalTargetsAndKeepsDiagramValid() {
        Table orphan = child("APP", "CHILD", "OUTSIDE_BATCH");

        MermaidBatchDiagramExporter.Result result = new MermaidBatchDiagramExporter()
                .export(List.of(orphan));

        assertEquals(1, result.exportedTables());
        assertEquals(1, result.physicalForeignKeys());
        assertEquals(0, result.resolvedPhysicalForeignKeys());
        assertTrue(result.dependency().startsWith("flowchart LR\n"));
        assertTrue(result.dependency().contains("%% unresolved FK:"), result.dependency());
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.code().equals("MISSING_REFERENCED_TABLE")
                        && issue.targetTable().equals("APP.OUTSIDE_BATCH")));
    }

    private static Table parent(String schema, String name) {
        return Table.builder(schema, name)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_" + name), List.of(Identifier.of("ID"))))
                .build();
    }

    private static Table child(String schema, String name, String target) {
        return Table.builder(schema, name)
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(Identifier.of("PK_" + name), List.of(Identifier.of("ID"))))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_" + name + "_PARENT"),
                        List.of(Identifier.of("PARENT_ID")),
                        QualifiedName.of(null, target),
                        List.of(Identifier.of("ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }
}
