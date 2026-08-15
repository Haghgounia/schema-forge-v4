package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;
import com.behsazan.schemaforge.diagram.DiagramType;
import com.behsazan.schemaforge.diagram.mermaid.GeneratedMermaidDiagram;
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
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidDiagramApiServiceTest {
    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();

    @Test
    void acceptsZipOfUniqueCanonicalSnapshots(@TempDir Path tempDir) throws Exception {
        Path customer = tempDir.resolve("customer.schema.json");
        Path account = tempDir.resolve("account.schema.json");
        writeSnapshot(customer, customerTable(), "customer.docx");
        writeSnapshot(account, accountTable(), "account.docx");

        MockMultipartFile zip = new MockMultipartFile(
                "file", "canonical.zip", "application/zip", zip(customer, account));
        DiagramExportOptions options = DiagramExportOptions.builder()
                .type(DiagramType.DEPENDENCY)
                .scope(DiagramScope.ALL)
                .build();

        GeneratedMermaidDiagram artifact = new MermaidDiagramApiService().generate(zip, options);

        assertEquals(2, artifact.inputTableCount());
        assertTrue(artifact.content().contains("FK_ACCOUNT_CUSTOMER"), artifact.content());
    }

    @Test
    void rejectsZipContainingDuplicateTableVersions(@TempDir Path tempDir) throws Exception {
        Path first = tempDir.resolve("customer-v1.schema.json");
        Path second = tempDir.resolve("customer-v2.schema.json");
        writeSnapshot(first, customerTable(), "customer-v1.docx");
        writeSnapshot(second, customerTable(), "customer-v2.docx");

        MockMultipartFile zip = new MockMultipartFile(
                "file", "historical.zip", "application/zip", zip(first, second));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new MermaidDiagramApiService().generate(zip, DiagramExportOptions.erAll()));

        assertTrue(exception.getMessage().contains("INPUT_DUPLICATE_TABLE"), exception.getMessage());
    }

    private byte[] zip(Path... files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Path file : files) {
                output.putNextEntry(new ZipEntry(file.getFileName().toString()));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private void writeSnapshot(Path path, Table table, String sourceName) throws Exception {
        DatabaseSchema schema = DatabaseSchema.builder("DIAGRAM_TEST").addTable(table).build();
        CanonicalSchemaSnapshot.SourceSnapshot source = new CanonicalSchemaSnapshot.SourceSnapshot(
                sourceName, sourceName, "0123456789abcdef", 100L,
                "2026-08-15T00:00:00Z", "test");
        store.writeSnapshot(path, mapper.toSnapshot(schema, source, "2026-08-15T00:00:00Z"));
    }

    private Table customerTable() {
        return Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .primaryKey(pk("PK_CUSTOMER", "CUSTOMER_ID"))
                .build();
    }

    private Table accountTable() {
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
