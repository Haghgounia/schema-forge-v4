package com.behsazan.schemaforge.diagram.mermaid;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidDiagramGenerationServiceTest {
    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();

    @Test
    void generatesDependencyArtifactFromProductionCanonicalInput(@TempDir Path tempDir) throws Exception {
        writeSnapshot(tempDir.resolve("customer.schema.json"), customer(), "customer.docx");
        writeSnapshot(tempDir.resolve("account.schema.json"), account(), "account.docx");

        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.TABLE_WITH_DEPENDENCIES)
                .rootTable("TSTSHMA", "ACCOUNT")
                .dependencyDepth(1)
                .build();

        GeneratedMermaidDiagram artifact = new MermaidDiagramGenerationService().generate(tempDir, options);

        assertEquals("TSTSHMA_ACCOUNT__dependency-table-with-dependencies-depth-1.mmd", artifact.fileName());
        assertEquals(2, artifact.inputTableCount());
        assertTrue(artifact.content().startsWith("flowchart LR\n"), artifact.content());
        assertTrue(artifact.content().contains("FK_ACCOUNT_CUSTOMER"), artifact.content());
        assertTrue(artifact.utf8().length > 0);
    }

    @Test
    void usesDeterministicAllSchemaFileName(@TempDir Path tempDir) throws Exception {
        writeSnapshot(tempDir.resolve("customer.schema.json"), customer(), "customer.docx");

        GeneratedMermaidDiagram artifact = new MermaidDiagramGenerationService()
                .generate(tempDir, DiagramExportOptions.erAll());

        assertEquals("schema__er-all.mmd", artifact.fileName());
        assertTrue(artifact.content().startsWith("erDiagram\n"), artifact.content());
    }

    private void writeSnapshot(Path path, Table table, String sourceName) throws Exception {
        DatabaseSchema schema = DatabaseSchema.builder("DIAGRAM_TEST").addTable(table).build();
        CanonicalSchemaSnapshot.SourceSnapshot source = new CanonicalSchemaSnapshot.SourceSnapshot(
                sourceName, sourceName, "0123456789abcdef", 100L,
                "2026-08-15T00:00:00Z", "test");
        store.writeSnapshot(path, mapper.toSnapshot(schema, source, "2026-08-15T00:00:00Z"));
    }

    private Table customer() {
        return Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_CUSTOMER", "CUSTOMER_ID"))
                .build();
    }

    private Table account() {
        return Table.builder("TSTSHMA", "ACCOUNT")
                .addColumn(Column.required("ACCOUNT_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_ACCOUNT", "ACCOUNT_ID"))
                .addForeignKey(new ForeignKey(
                        Identifier.of("FK_ACCOUNT_CUSTOMER"),
                        List.of(Identifier.of("CUSTOMER_ID")),
                        QualifiedName.of("TSTSHMA", "CUSTOMER"),
                        List.of(Identifier.of("CUSTOMER_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION))
                .build();
    }

    private PrimaryKey pk(String name, String column) {
        return new PrimaryKey(Identifier.of(name), List.of(Identifier.of(column)));
    }
}
