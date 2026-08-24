package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactOrigin;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchArchiveSupportTest {

    @TempDir
    Path temp;

    @Test
    void rejectsZipSlipEntryBeforeWritingOutsideDestination() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            archive = bytes.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "unsafe.zip", "application/zip", archive);
        Path destination = Files.createDirectories(temp.resolve("input"));

        assertThrows(IllegalArgumentException.class,
                () -> BatchArchiveSupport.unzipSafely(file, destination));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test
    void findsOnlyProcessableDocxFilesInStableCaseInsensitiveOrder() throws Exception {
        Files.createDirectories(temp.resolve("A"));
        Files.createDirectories(temp.resolve("b"));
        Files.createDirectories(temp.resolve("__MACOSX"));
        Files.writeString(temp.resolve("A/alpha.DOCX"), "a");
        Files.writeString(temp.resolve("b/Bravo.docx"), "b");
        Files.writeString(temp.resolve("~$draft.docx"), "x");
        Files.writeString(temp.resolve(".hidden.docx"), "x");
        Files.writeString(temp.resolve("._meta.docx"), "x");
        Files.writeString(temp.resolve("__MACOSX/ghost.docx"), "x");
        Files.writeString(temp.resolve("legacy.doc"), "x");
        Files.writeString(temp.resolve("notes.txt"), "x");

        List<String> relative = BatchArchiveSupport.processableWordDocuments(temp).stream()
                .map(temp::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .toList();

        assertEquals(List.of("A/alpha.DOCX", "b/Bravo.docx"), relative);
    }

    @Test
    void collisionRemapAndLedgerMergePreserveGeneratedDescriptors() throws Exception {
        Path destination = Files.createDirectories(temp.resolve("out"));
        Path firstSource = Files.createDirectories(temp.resolve("first"));
        Path secondSource = Files.createDirectories(temp.resolve("second"));
        Path relative = Path.of("ddl", "oracle",
                "APP.CUSTOMERS_20260823_040000_000.oracle.sql");
        Files.createDirectories(firstSource.resolve(relative).getParent());
        Files.createDirectories(secondSource.resolve(relative).getParent());
        Files.writeString(firstSource.resolve(relative), "first");
        Files.writeString(secondSource.resolve(relative), "second");

        ArtifactGenerationContext batch = ArtifactGenerationContext.create(
                ArtifactOrigin.ZIP_BATCH,
                "batch.zip",
                "20260823_040000_000",
                OffsetDateTime.parse("2026-08-23T04:00:00-07:00"));
        ArtifactGenerationContext first = batch.isolatedChild(ArtifactOrigin.ZIP_BATCH, "a/customers.docx");
        ArtifactGenerationContext second = batch.isolatedChild(ArtifactOrigin.ZIP_BATCH, "b/customers.docx");
        first.ledger().generated(first, ArtifactType.DDL, DatabasePlatform.ORACLE,
                "APP.CUSTOMERS", "ddl/oracle/APP.CUSTOMERS_20260823_040000_000.oracle.sql",
                "application/sql", "DdlGenerator");
        second.ledger().generated(second, ArtifactType.DDL, DatabasePlatform.ORACLE,
                "APP.CUSTOMERS", "ddl/oracle/APP.CUSTOMERS_20260823_040000_000.oracle.sql",
                "application/sql", "DdlGenerator");

        CollisionSafeArtifactTargetAllocator allocator = new CollisionSafeArtifactTargetAllocator();
        Map<String, String> firstMap = BatchArchiveSupport.moveGeneratedFiles(
                firstSource, destination, allocator, "a/customers.docx");
        BatchArchiveSupport.mergeBatchArtifacts(first, batch, firstMap);
        Map<String, String> secondMap = BatchArchiveSupport.moveGeneratedFiles(
                secondSource, destination, allocator, "b/customers.docx");
        BatchArchiveSupport.mergeBatchArtifacts(second, batch, secondMap);

        assertEquals(2, BatchArchiveSupport.countRegularFiles(destination));
        var descriptors = batch.ledger().snapshot();
        assertEquals(2, descriptors.size());
        assertEquals("ddl/oracle/APP.CUSTOMERS_20260823_040000_000.oracle.sql",
                descriptors.get(0).relativePath());
        assertTrue(descriptors.get(1).relativePath().matches(
                "ddl/oracle/APP\\.CUSTOMERS__sf_[0-9a-f]{10}_20260823_040000_000\\.oracle\\.sql"),
                descriptors.get(1).relativePath());
        assertEquals(batch.generationId(), descriptors.get(0).generationId());
        assertEquals(batch.generationId(), descriptors.get(1).generationId());
    }
}
