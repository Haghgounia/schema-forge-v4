package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidDiagramExporterTest {
    private final MermaidDiagramExporter exporter = new MermaidDiagramExporter();

    @Test
    void rendersCanonicalErDiagramWithTypesKeysAndRelationship() {
        List<Table> tables = sampleTables();

        String mermaid = exporter.export(tables, DiagramExportOptions.erAll());

        assertTrue(mermaid.startsWith("erDiagram\n"), mermaid);
        assertTrue(mermaid.contains("SF_TSTSHMA_CUSTOMER {"), mermaid);
        assertTrue(mermaid.contains("NUMBER_19_0 CUSTOMER_ID PK"), mermaid);
        assertTrue(mermaid.contains("VARCHAR_100_CHAR NAME"), mermaid);
        assertTrue(mermaid.contains("NUMBER_19_0 CUSTOMER_ID FK"), mermaid);
        assertTrue(mermaid.contains("SF_TSTSHMA_CUSTOMER ||--o{ SF_TSTSHMA_ACCOUNT : \"FK_ACCOUNT_CUSTOMER\""), mermaid);
    }

    @Test
    void resolvesUnqualifiedForeignKeyAgainstChildSchema() {
        Table customer = customer("TSTSHMA");
        Table account = account("TSTSHMA", QualifiedName.of(null, "CUSTOMER"), true);

        String mermaid = exporter.export(List.of(customer, account), DiagramExportOptions.erAll());

        assertTrue(mermaid.contains("SF_TSTSHMA_CUSTOMER ||--o{ SF_TSTSHMA_ACCOUNT"), mermaid);
    }

    @Test
    void tableWithDependenciesDepthOneIncludesIncomingAndOutgoingNeighbours() {
        List<Table> tables = sampleTables();
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(1)
                .build();

        String mermaid = exporter.export(tables, options);

        assertTrue(mermaid.contains("TSTSHMA.CUSTOMER"), mermaid);
        assertTrue(mermaid.contains("TSTSHMA.ACCOUNT"), mermaid);
        assertTrue(mermaid.contains("TSTSHMA.TXN"), mermaid);
        assertFalse(mermaid.contains("TSTSHMA.TXN_DETAIL"), mermaid);
        assertTrue(mermaid.contains("SF_TSTSHMA_ACCOUNT -->|FK_ACCOUNT_CUSTOMER| SF_TSTSHMA_CUSTOMER"), mermaid);
        assertTrue(mermaid.contains("SF_TSTSHMA_TXN -->|FK_TXN_ACCOUNT| SF_TSTSHMA_ACCOUNT"), mermaid);
    }

    @Test
    void tableWithDependenciesDepthTwoExpandsNextLevel() {
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(2)
                .build();

        String mermaid = exporter.export(sampleTables(), options);

        assertTrue(mermaid.contains("TSTSHMA.TXN_DETAIL"), mermaid);
        assertTrue(mermaid.contains("SF_TSTSHMA_TXN_DETAIL -->|FK_DETAIL_TXN| SF_TSTSHMA_TXN"), mermaid);
    }

    @Test
    void schemaScopeDoesNotLeakOtherSchemas() {
        List<Table> tables = new java.util.ArrayList<>(sampleTables());
        tables.add(customer("ARCHIVE"));
        DiagramExportOptions options = DiagramExportOptions.builder()
                .scope(DiagramScope.SCHEMA)
                .schema("ARCHIVE")
                .build();

        String mermaid = exporter.export(tables, options);

        assertTrue(mermaid.contains("ARCHIVE.CUSTOMER"), mermaid);
        assertFalse(mermaid.contains("TSTSHMA.CUSTOMER"), mermaid);
        assertFalse(mermaid.contains("TSTSHMA.ACCOUNT"), mermaid);
    }

    @Test
    void selectedTablesScopeRendersOnlyExplicitSelection() {
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.SELECTED_TABLES)
                .selectedTable(QualifiedName.of("TSTSHMA", "CUSTOMER"))
                .selectedTable(QualifiedName.of("TSTSHMA", "ACCOUNT"))
                .build();

        String mermaid = exporter.export(sampleTables(), options);

        assertTrue(mermaid.contains("TSTSHMA.CUSTOMER"), mermaid);
        assertTrue(mermaid.contains("TSTSHMA.ACCOUNT"), mermaid);
        assertFalse(mermaid.contains("TSTSHMA.TXN\""), mermaid);
    }

    @Test
    void logicalForeignKeysAreExcludedByDefaultAndCanBeIncluded() {
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

        DiagramExportOptions defaultOptions = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .build();
        String defaultDiagram = exporter.export(List.of(parent, child), defaultOptions);
        assertFalse(defaultDiagram.contains("LFK_CUSTOMER"), defaultDiagram);

        DiagramExportOptions includeLogical = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .includeLogicalForeignKeys(true)
                .build();
        String logicalDiagram = exporter.export(List.of(parent, child), includeLogical);
        assertTrue(logicalDiagram.contains("SF_TSTSHMA_LOGICAL_CHILD -.->|LFK_CUSTOMER| SF_TSTSHMA_CUSTOMER"), logicalDiagram);
    }


    @Test
    void nullableForeignKeyUsesOptionalParentCardinality() {
        Table customer = customer("TSTSHMA");
        Table account = account("TSTSHMA", QualifiedName.of(null, "CUSTOMER"), false);

        String mermaid = exporter.export(List.of(customer, account), DiagramExportOptions.erAll());

        assertTrue(mermaid.contains("SF_TSTSHMA_CUSTOMER o|--o{ SF_TSTSHMA_ACCOUNT"), mermaid);
    }

    @Test
    void conceptualErdOmitsFieldsAndUsesPkUkEvidenceForCardinality() {
        Table customer = customer("TSTSHMA");
        Table profile = Table.builder("TSTSHMA", "CUSTOMER_PROFILE")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_CUSTOMER_PROFILE", "CUSTOMER_ID"))
                .addUniqueKey(new UniqueKey(id("UK_CUSTOMER_PROFILE_CUSTOMER"), List.of(id("CUSTOMER_ID"))))
                .addForeignKey(fk("FK_PROFILE_CUSTOMER", "CUSTOMER_ID", "CUSTOMER", "CUSTOMER_ID"))
                .build();

        String mermaid = exporter.export(List.of(customer, profile), DiagramExportOptions.builder()
                .type(DiagramType.CONCEPTUAL_ERD)
                .build());

        assertTrue(mermaid.startsWith("erDiagram\n"), mermaid);
        assertTrue(mermaid.contains(
                "SF_TSTSHMA_CUSTOMER ||--o| SF_TSTSHMA_CUSTOMER_PROFILE : \"FK_PROFILE_CUSTOMER\""),
                mermaid);
        assertFalse(mermaid.contains("NUMBER_19_0"), mermaid);
        assertFalse(mermaid.contains("CUSTOMER_ID PK"), mermaid);
    }

    @Test
    void missingReferencedTableIsReportedAsMermaidComment() {
        Table orphan = Table.builder("TSTSHMA", "ORPHAN")
                .addColumn(Column.required("ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("PARENT_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_ORPHAN", "ID"))
                .addForeignKey(fk("FK_ORPHAN_PARENT", "PARENT_ID", "MISSING_PARENT", "ID"))
                .build();

        String mermaid = exporter.export(List.of(orphan), DiagramExportOptions.erAll());

        assertTrue(mermaid.contains("%% unresolved FK: TSTSHMA.ORPHAN.FK_ORPHAN_PARENT -> MISSING_PARENT"), mermaid);
    }

    @Test
    void rejectsDuplicateQualifiedTableInput() {
        Table first = customer("TSTSHMA");
        Table duplicate = customer("TSTSHMA");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> exporter.export(List.of(first, duplicate), DiagramExportOptions.erAll()));

        assertTrue(exception.getMessage().contains("INPUT_DUPLICATE_TABLE"), exception.getMessage());
    }

    @Test
    void fileWriterCreatesUtf8MermaidArtifact(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("diagrams").resolve("account.mmd");
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(1)
                .build();

        new MermaidDiagramFileWriter().write(output, sampleTables(), options);

        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.startsWith("flowchart LR"), content);
        assertTrue(content.contains("TSTSHMA.ACCOUNT"), content);
    }

    private List<Table> sampleTables() {
        Table customer = customer("TSTSHMA");
        Table account = account("TSTSHMA", QualifiedName.of(null, "CUSTOMER"), true);
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

    private Table account(String schema, QualifiedName customerTarget, boolean customerRequired) {
        Column customerColumn = customerRequired
                ? Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0))
                : Column.nullable("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0));
        return Table.builder(schema, "ACCOUNT")
                .addColumn(Column.required("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(customerColumn)
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
