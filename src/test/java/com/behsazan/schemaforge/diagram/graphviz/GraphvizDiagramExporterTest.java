package com.behsazan.schemaforge.diagram.graphviz;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphvizDiagramExporterTest {
    private final GraphvizDiagramExporter exporter = new GraphvizDiagramExporter();

    @Test
    void rendersErDotWithColumnsTypesKeysAndRelationship() {
        String dot = exporter.export(sampleTables(), DiagramExportOptions.erAll());

        assertTrue(dot.startsWith("digraph SchemaForge_ER {\n"), dot);
        assertTrue(dot.contains("<B>TSTSHMA.CUSTOMER</B>"), dot);
        assertTrue(dot.contains(">PK</TD><TD ALIGN=\"LEFT\">CUSTOMER_ID</TD><TD ALIGN=\"LEFT\">NUMBER(19,0)</TD>"), dot);
        assertTrue(dot.contains(">FK</TD><TD ALIGN=\"LEFT\">CUSTOMER_ID</TD>"), dot);
        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\" -> \"TSTSHMA.CUSTOMER\" [label=\"FK_ACCOUNT_CUSTOMER\"]"), dot);
    }

    @Test
    void dependencyDotUsesSimpleNodesAndFkLabels() {
        DiagramExportOptions options = DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build();
        String dot = exporter.export(sampleTables(), options);

        assertTrue(dot.startsWith("digraph SchemaForge_Dependency {\n"), dot);
        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\" [label=\"TSTSHMA.ACCOUNT\"]"), dot);
        assertTrue(dot.contains("\"TSTSHMA.TXN\" -> \"TSTSHMA.ACCOUNT\" [label=\"FK_TXN_ACCOUNT\"]"), dot);
    }

    @Test
    void resolvesUnqualifiedForeignKeyAgainstChildSchema() {
        Table customer = customer("TSTSHMA");
        Table account = account("TSTSHMA", QualifiedName.of(null, "CUSTOMER"));

        String dot = exporter.export(List.of(customer, account),
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build());

        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\" -> \"TSTSHMA.CUSTOMER\""), dot);
    }

    @Test
    void tableWithDependenciesHonoursDepth() {
        DiagramExportOptions depthOne = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(1)
                .build();
        String one = exporter.export(sampleTables(), depthOne);
        assertTrue(one.contains("TSTSHMA.CUSTOMER"), one);
        assertTrue(one.contains("TSTSHMA.TXN"), one);
        assertFalse(one.contains("TSTSHMA.TXN_DETAIL"), one);

        DiagramExportOptions depthTwo = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(2)
                .build();
        String two = exporter.export(sampleTables(), depthTwo);
        assertTrue(two.contains("TSTSHMA.TXN_DETAIL"), two);
    }

    @Test
    void depthZeroRendersOnlyRootTable() {
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(0)
                .build();
        String dot = exporter.export(sampleTables(), options);
        assertTrue(dot.contains("TSTSHMA.ACCOUNT"), dot);
        assertFalse(dot.contains("TSTSHMA.CUSTOMER"), dot);
        assertFalse(dot.contains("TSTSHMA.TXN"), dot);
    }

    @Test
    void schemaAndSelectedTableScopesAreRespected() {
        List<Table> tables = new ArrayList<>(sampleTables());
        tables.add(customer("ARCHIVE"));

        String schema = exporter.export(tables, DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.SCHEMA)
                .schema("ARCHIVE")
                .build());
        assertTrue(schema.contains("ARCHIVE.CUSTOMER"), schema);
        assertFalse(schema.contains("TSTSHMA.ACCOUNT"), schema);

        String selected = exporter.export(tables, DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.SELECTED_TABLES)
                .selectedTable(QualifiedName.of("TSTSHMA", "CUSTOMER"))
                .selectedTable(QualifiedName.of("TSTSHMA", "ACCOUNT"))
                .build());
        assertTrue(selected.contains("TSTSHMA.CUSTOMER"), selected);
        assertTrue(selected.contains("TSTSHMA.ACCOUNT"), selected);
        assertFalse(selected.contains("TSTSHMA.TXN\""), selected);
    }

    @Test
    void logicalForeignKeysAreDashedOnlyWhenEnabled() {
        Table parent = customer("TSTSHMA");
        Table child = Table.builder("TSTSHMA", "LOGICAL_CHILD")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_LOGICAL_CHILD", "ID"))
                .addForeignKey(new ForeignKey(id("LFK_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        QualifiedName.of(null, "CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION,
                        false, false, false, false))
                .build();

        String defaultDot = exporter.export(List.of(parent, child),
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build());
        assertFalse(defaultDot.contains("LFK_CUSTOMER"), defaultDot);

        String logicalDot = exporter.export(List.of(parent, child), DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .includeLogicalForeignKeys(true)
                .build());
        assertTrue(logicalDot.contains("label=\"LFK_CUSTOMER\", style=dashed"), logicalDot);
    }

    @Test
    void missingReferencedTableIsWrittenAsDotComment() {
        Table orphan = Table.builder("TSTSHMA", "ORPHAN")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_ORPHAN", "ID"))
                .addForeignKey(fk("FK_ORPHAN_PARENT", "PARENT_ID", "MISSING_PARENT", "ID"))
                .build();

        String dot = exporter.export(List.of(orphan), DiagramExportOptions.erAll());
        assertTrue(dot.contains("// unresolved FK: TSTSHMA.ORPHAN.FK_ORPHAN_PARENT -> MISSING_PARENT"), dot);
    }

    @Test
    void clusteredDependencyGroupsNodesBySchema() {
        List<Table> tables = new ArrayList<>(sampleTables());
        tables.add(customer("ARCHIVE"));

        String dot = exporter.exportClusteredDependency(tables, false);

        assertTrue(dot.contains("subgraph cluster_ARCHIVE_1"), dot);
        assertTrue(dot.contains("label=\"ARCHIVE\""), dot);
        assertTrue(dot.contains("subgraph cluster_TSTSHMA_2"), dot);
        assertTrue(dot.contains("label=\"TSTSHMA\""), dot);
        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\" -> \"TSTSHMA.CUSTOMER\""), dot);
    }


    @Test
    void compactProfileExcludesDisconnectedTables() {
        List<Table> tables = new ArrayList<>(sampleTables());
        tables.add(customer("ARCHIVE"));

        String dot = exporter.export(
                tables,
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build(),
                GraphvizRenderOptions.compact());

        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\""), dot);
        assertTrue(dot.contains("\"TSTSHMA.CUSTOMER\""), dot);
        assertFalse(dot.contains("ARCHIVE.CUSTOMER"), dot);
        assertTrue(dot.contains("subgraph cluster_TSTSHMA_1"), dot);
    }

    @Test
    void overviewProfileSuppressesForeignKeyLabels() {
        String dot = exporter.export(
                sampleTables(),
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build(),
                GraphvizRenderOptions.overview());

        assertTrue(dot.contains("\"TSTSHMA.ACCOUNT\" -> \"TSTSHMA.CUSTOMER\";"), dot);
        assertFalse(dot.contains("FK_ACCOUNT_CUSTOMER"), dot);
    }

    @Test
    void fullClusteredProfileKeepsDisconnectedTablesAndLabels() {
        List<Table> tables = new ArrayList<>(sampleTables());
        tables.add(customer("ARCHIVE"));

        String dot = exporter.export(
                tables,
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build(),
                GraphvizRenderOptions.fullClustered());

        assertTrue(dot.contains("ARCHIVE.CUSTOMER"), dot);
        assertTrue(dot.contains("FK_ACCOUNT_CUSTOMER"), dot);
        assertTrue(dot.contains("subgraph cluster_ARCHIVE_1"), dot);
    }

    @Test
    void compactProfileCanRenderEmptyGraphWhenNoResolvedRelationshipsExist() {
        String dot = exporter.export(
                List.of(customer("BIM")),
                DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build(),
                GraphvizRenderOptions.compact());

        assertTrue(dot.startsWith("digraph SchemaForge_Clustered_Dependency"), dot);
        assertFalse(dot.contains("BIM.CUSTOMER"), dot);
    }

    @Test
    void outputIsDeterministicRegardlessOfInputOrder() {
        List<Table> normal = sampleTables();
        List<Table> reversed = new ArrayList<>(normal);
        java.util.Collections.reverse(reversed);
        DiagramExportOptions options = DiagramExportOptions.builder().type(DiagramType.DEPENDENCY).build();

        assertEquals(exporter.export(normal, options), exporter.export(reversed, options));
    }

    @Test
    void rejectsDuplicateQualifiedTableInput() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> exporter.export(List.of(customer("TSTSHMA"), customer("TSTSHMA")),
                        DiagramExportOptions.erAll()));
        assertTrue(exception.getMessage().contains("INPUT_DUPLICATE_TABLE"), exception.getMessage());
    }

    private List<Table> sampleTables() {
        Table customer = customer("TSTSHMA");
        Table account = account("TSTSHMA", QualifiedName.of(null, "CUSTOMER"));
        Table txn = Table.builder("TSTSHMA", "TXN")
                .addColumn(Column.required("TXN_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_TXN", "TXN_ID"))
                .addForeignKey(fk("FK_TXN_ACCOUNT", "ACCOUNT_ID", "ACCOUNT", "ACCOUNT_ID"))
                .build();
        Table detail = Table.builder("TSTSHMA", "TXN_DETAIL")
                .addColumn(Column.required("DETAIL_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("TXN_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_TXN_DETAIL", "DETAIL_ID"))
                .addForeignKey(fk("FK_DETAIL_TXN", "TXN_ID", "TXN", "TXN_ID"))
                .build();
        return List.of(customer, account, txn, detail);
    }

    private Table customer(String schema) {
        return Table.builder(schema, "CUSTOMER")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("NAME", DataType.varchar("VARCHAR", 100)))
                .primaryKey(pk("PK_CUSTOMER", "CUSTOMER_ID"))
                .build();
    }

    private Table account(String schema, QualifiedName customerTarget) {
        return Table.builder(schema, "ACCOUNT")
                .addColumn(Column.required("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_ACCOUNT", "ACCOUNT_ID"))
                .addForeignKey(new ForeignKey(id("FK_ACCOUNT_CUSTOMER"), List.of(id("CUSTOMER_ID")),
                        customerTarget, List.of(id("CUSTOMER_ID")),
                        ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION))
                .build();
    }

    private ForeignKey fk(String name, String column, String targetTable, String targetColumn) {
        return new ForeignKey(id(name), List.of(id(column)), QualifiedName.of(null, targetTable),
                List.of(id(targetColumn)), ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION);
    }

    private PrimaryKey pk(String name, String... columns) {
        return new PrimaryKey(id(name), java.util.Arrays.stream(columns).map(this::id).toList());
    }

    private Identifier id(String value) {
        return Identifier.of(value);
    }
}
