package com.behsazan.schemaforge.diagram.graphviz;

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

class GraphvizBatchDiagramExporterTest {
    private final GraphvizBatchDiagramExporter exporter = new GraphvizBatchDiagramExporter();

    @Test
    void exportsResolvedBatchDependencyAndClusteredDot() {
        GraphvizBatchDiagramExporter.Result result = exporter.export(List.of(
                customer("BIM"),
                account("BIM", "CUSTOMER"),
                customer("ARCHIVE")));

        assertEquals(3, result.tableDefinitions());
        assertEquals(3, result.distinctTableNames());
        assertEquals(0, result.duplicateTableNames());
        assertEquals(3, result.exportedTables());
        assertEquals(2, result.connectedTables());
        assertEquals(1, result.physicalForeignKeys());
        assertEquals(1, result.resolvedPhysicalForeignKeys());
        assertTrue(result.dependency().contains("\"BIM.ACCOUNT\" -> \"BIM.CUSTOMER\""));
        assertTrue(result.clusteredDependency().contains("label=\"BIM\""));
        assertTrue(result.clusteredDependency().contains("label=\"ARCHIVE\""));
        assertFalse(result.compactDependency().contains("ARCHIVE.CUSTOMER"));
        assertTrue(result.compactDependency().contains("FK_ACCOUNT_CUSTOMER"));
        assertFalse(result.overviewDependency().contains("FK_ACCOUNT_CUSTOMER"));
        assertTrue(result.overviewDependency().contains("\"BIM.ACCOUNT\" -> \"BIM.CUSTOMER\";"));
    }

    @Test
    void excludesAllDuplicateDefinitionsWithoutSelectingVersion() {
        GraphvizBatchDiagramExporter.Result result = exporter.export(List.of(
                customer("BIM"),
                customer("BIM"),
                account("BIM", "CUSTOMER")));

        assertEquals(3, result.tableDefinitions());
        assertEquals(2, result.distinctTableNames());
        assertEquals(1, result.duplicateTableNames());
        assertEquals(1, result.exportedTables());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INPUT_DUPLICATE_TABLE")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("INPUT_DUPLICATE_TABLE_TARGET")));
        assertTrue(!result.dependency().contains("\"BIM.CUSTOMER\" [label="));
    }

    @Test
    void reportsMissingReferencedTable() {
        GraphvizBatchDiagramExporter.Result result = exporter.export(List.of(account("BIM", "CUSTOMER")));

        assertEquals(1, result.physicalForeignKeys());
        assertEquals(0, result.resolvedPhysicalForeignKeys());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("MISSING_REFERENCED_TABLE")));
        assertTrue(result.dependency().contains("// unresolved FK: BIM.ACCOUNT.FK_ACCOUNT_CUSTOMER -> CUSTOMER"));
    }

    @Test
    void resultOrderingIsDeterministic() {
        GraphvizBatchDiagramExporter.Result first = exporter.export(List.of(
                account("BIM", "CUSTOMER"), customer("BIM"), customer("ARCHIVE")));
        GraphvizBatchDiagramExporter.Result second = exporter.export(List.of(
                customer("ARCHIVE"), customer("BIM"), account("BIM", "CUSTOMER")));

        assertEquals(first.dependency(), second.dependency());
        assertEquals(first.clusteredDependency(), second.clusteredDependency());
        assertEquals(first.compactDependency(), second.compactDependency());
        assertEquals(first.overviewDependency(), second.overviewDependency());
        assertEquals(first.issues(), second.issues());
    }

    private Table customer(String schema) {
        return Table.builder(schema, "CUSTOMER")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(id("PK_CUSTOMER"), List.of(id("CUSTOMER_ID"))))
                .build();
    }

    private Table account(String schema, String target) {
        return Table.builder(schema, "ACCOUNT")
                .addColumn(Column.required("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(new PrimaryKey(id("PK_ACCOUNT"), List.of(id("ACCOUNT_ID"))))
                .addForeignKey(new ForeignKey(
                        id("FK_ACCOUNT_CUSTOMER"),
                        List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, target),
                        List.of(id("CUSTOMER_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private Identifier id(String value) {
        return Identifier.of(value);
    }
}
