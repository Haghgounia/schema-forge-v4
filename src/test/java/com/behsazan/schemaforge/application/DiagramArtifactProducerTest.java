package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramArtifactProducerTest {
    private static final String TIMESTAMP = "20260823_020000_000";

    @TempDir
    Path tempDir;

    @Test
    void shouldProducePerDocumentDiagramsWithCanonicalPathsAndLedgerEntries() throws Exception {
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        DiagramArtifactProducer producer = new DiagramArtifactProducer(naming);
        ArtifactGenerationContext context = context(ArtifactOrigin.STANDARD_WORD, "customer.docx");
        DatabaseSchema schema = schema();

        producer.writeMermaidArtifact(schema, tempDir, "customer", TIMESTAMP, context);
        producer.writeGraphvizArtifact(schema, tempDir, "customer", TIMESTAMP, context);
        producer.writeConceptualErdArtifacts(schema, tempDir, "customer", TIMESTAMP, context);

        assertTrue(Files.isRegularFile(tempDir.resolve(naming.mermaidErRelativePath("customer", TIMESTAMP))));
        assertTrue(Files.isRegularFile(tempDir.resolve(naming.graphvizErRelativePath("customer", TIMESTAMP))));
        assertTrue(Files.isRegularFile(tempDir.resolve(
                naming.mermaidConceptualRelativePath("customer", TIMESTAMP))));
        assertTrue(Files.isRegularFile(tempDir.resolve(
                naming.graphvizConceptualRelativePath("customer", TIMESTAMP))));

        var artifacts = context.ledger().snapshot();
        assertEquals(4, artifacts.size());
        assertEquals(2, artifacts.stream().filter(a -> a.type() == ArtifactType.MERMAID_DIAGRAM).count());
        assertEquals(2, artifacts.stream().filter(a -> a.type() == ArtifactType.GRAPHVIZ_DIAGRAM).count());
    }

    @Test
    void shouldProduceBatchDiagramReportsWithoutChangingBatchContract() throws Exception {
        ArtifactNamingPolicy naming = new ArtifactNamingPolicy();
        DiagramArtifactProducer producer = new DiagramArtifactProducer(naming);
        ArtifactGenerationContext context = context(ArtifactOrigin.ZIP_BATCH, "batch.zip");
        List<Table> tables = schema().tables();

        producer.writeBatchMermaidArtifacts(tables, tempDir, context);
        producer.writeBatchGraphvizArtifacts(tables, tempDir, context);

        assertTrue(Files.isRegularFile(tempDir.resolve(naming.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ER))));
        assertTrue(Files.isRegularFile(tempDir.resolve(naming.batchMermaidRelativePath(
                ArtifactNamingPolicy.BatchMermaidArtifact.ISSUES))));
        assertTrue(Files.isRegularFile(tempDir.resolve(naming.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.OVERVIEW))));
        assertTrue(Files.isRegularFile(tempDir.resolve(naming.batchGraphvizRelativePath(
                ArtifactNamingPolicy.BatchGraphvizArtifact.SUMMARY))));

        var artifacts = context.ledger().snapshot();
        assertEquals(12, artifacts.size());
        assertEquals(8, artifacts.stream()
                .filter(a -> a.type() == ArtifactType.MERMAID_DIAGRAM
                        || a.type() == ArtifactType.GRAPHVIZ_DIAGRAM)
                .count());
        assertEquals(2, artifacts.stream().filter(a -> a.type() == ArtifactType.ISSUE_REPORT).count());
        assertEquals(2, artifacts.stream().filter(a -> a.type() == ArtifactType.SUMMARY_REPORT).count());
    }

    private ArtifactGenerationContext context(ArtifactOrigin origin, String sourceName) {
        return ArtifactGenerationContext.create(
                origin, sourceName, TIMESTAMP, OffsetDateTime.parse("2026-08-23T02:00:00-07:00"));
    }

    private DatabaseSchema schema() {
        Table table = Table.builder("TSTSHMA", "CUSTOMER")
                .addColumn(Column.required("CUSTOMER_ID", DataType.numeric("NUMBER", 19, 0)))
                .build();
        return DatabaseSchema.builder("TSTSHMA").addTable(table).build();
    }
}
