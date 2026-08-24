package com.behsazan.schemaforge.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPackageBuilderTest {

    @TempDir
    Path temp;

    private final ArtifactPackageBuilder builder = new ArtifactPackageBuilder();

    @Test
    void zipDirectoryPreservesRelativeEntryPathsAndContent() throws Exception {
        Path root = Files.createDirectories(temp.resolve("out"));
        Path ddl = root.resolve(Path.of("ddl", "oracle", "APP.CUSTOMERS.oracle.sql"));
        Path manifest = root.resolve("manifest.json");
        Files.createDirectories(ddl.getParent());
        Files.writeString(ddl, "CREATE TABLE APP.CUSTOMERS(ID NUMBER);", StandardCharsets.UTF_8);
        Files.writeString(manifest, "{}", StandardCharsets.UTF_8);

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(builder.zipDirectory(root)))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }

        assertEquals(2, entries.size());
        assertEquals("CREATE TABLE APP.CUSTOMERS(ID NUMBER);",
                entries.get("ddl/oracle/APP.CUSTOMERS.oracle.sql"));
        assertEquals("{}", entries.get("manifest.json"));
    }

    @Test
    void normalizePathAlwaysUsesForwardSlashes() {
        assertEquals("alpha/beta/file.sql",
                builder.normalizePath(Path.of("alpha", "beta", "file.sql")));
    }

    @Test
    void deleteRecursivelyRemovesNestedTreeAndKeepsMissingRootHarmless() throws Exception {
        Path root = Files.createDirectories(temp.resolve("work/nested"));
        Files.writeString(root.resolve("file.txt"), "x", StandardCharsets.UTF_8);
        Path work = temp.resolve("work");

        builder.deleteRecursively(work);
        builder.deleteRecursively(work);

        assertFalse(Files.exists(work));
        assertTrue(Files.exists(temp));
    }
}
