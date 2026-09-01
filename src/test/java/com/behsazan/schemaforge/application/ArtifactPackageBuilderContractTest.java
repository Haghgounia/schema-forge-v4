package com.behsazan.schemaforge.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPackageBuilderContractTest {

    @TempDir
    Path temp;

    @Test
    void entriesMustBeWrittenInDeterministicOrder() throws Exception {

        Path root = Files.createDirectories(temp.resolve("output"));

        Files.createDirectories(root.resolve("z"));
        Files.createDirectories(root.resolve("a"));

        Files.writeString(
                root.resolve("z/z.txt"),
                "Z",
                StandardCharsets.UTF_8);

        Files.writeString(
                root.resolve("a/b.txt"),
                "B",
                StandardCharsets.UTF_8);

        Files.writeString(
                root.resolve("a/a.txt"),
                "A",
                StandardCharsets.UTF_8);

        byte[] zip =
                new ArtifactPackageBuilder()
                        .zipDirectory(root);

        assertEquals(
                List.of(
                        "a/a.txt",
                        "a/b.txt",
                        "z/z.txt"),
                entryNames(zip));
    }

    @Test
    void identicalInputMustProduceIdenticalZipBytes() throws Exception {

        Path root = Files.createDirectories(temp.resolve("output"));

        Files.createDirectories(root.resolve("ddl/oracle"));

        Path file =
                root.resolve("ddl/oracle/T.sql");

        Files.writeString(
                file,
                "CREATE TABLE T (ID NUMBER);\n",
                StandardCharsets.UTF_8);

        ArtifactPackageBuilder builder =
                new ArtifactPackageBuilder();

        byte[] first =
                builder.zipDirectory(root);

        /*
         * Change the source file modification time without changing
         * the content. The ZIP bytes must remain identical.
         */
        Files.setLastModifiedTime(
                file,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() + 60_000));

        byte[] second =
                builder.zipDirectory(root);

        assertArrayEquals(
                first,
                second);
    }

    @Test
    void zipEntryPathsMustUseForwardSlashes() throws Exception {

        Path root = Files.createDirectories(temp.resolve("output"));

        Path nested =
                root.resolve("ddl")
                        .resolve("oracle")
                        .resolve("T.sql");

        Files.createDirectories(
                nested.getParent());

        Files.writeString(
                nested,
                "SQL",
                StandardCharsets.UTF_8);

        byte[] zip =
                new ArtifactPackageBuilder()
                        .zipDirectory(root);

        List<String> entries =
                entryNames(zip);

        assertEquals(
                List.of("ddl/oracle/T.sql"),
                entries);

        assertFalse(
                entries.getFirst().contains("\\"));
    }

    @Test
    void directoriesMustNotBecomeZipEntries() throws Exception {

        Path root = Files.createDirectories(temp.resolve("output"));

        Files.createDirectories(
                root.resolve("empty-directory"));

        Files.writeString(
                root.resolve("a.txt"),
                "A",
                StandardCharsets.UTF_8);

        byte[] zip =
                new ArtifactPackageBuilder()
                        .zipDirectory(root);

        assertEquals(
                List.of("a.txt"),
                entryNames(zip));
    }

    @Test
    void caseInsensitiveDuplicateEntryPathsMustBeRejectedWhenFilesystemAllowsThem()
            throws Exception {

        Path root = Files.createDirectories(temp.resolve("output"));

        Path upper =
                root.resolve("A.txt");

        Path lower =
                root.resolve("a.txt");

        Files.writeString(
                upper,
                "UPPER",
                StandardCharsets.UTF_8);

        /*
         * On Windows these two names normally address the same file.
         * The collision scenario can therefore only be exercised on a
         * case-sensitive filesystem.
         */
        Files.writeString(
                lower,
                "lower",
                StandardCharsets.UTF_8);

        long distinctNames;

        try (var files = Files.list(root)) {
            distinctNames = files
                    .map(path -> path.getFileName().toString())
                    .distinct()
                    .count();
        }

        if (distinctNames < 2) {
            return;
        }

        assertThrows(
                IllegalStateException.class,
                () -> new ArtifactPackageBuilder()
                        .zipDirectory(root));
    }

    @Test
    void nonexistentSourceDirectoryMustBeRejected() {

        Path missing =
                temp.resolve("missing");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactPackageBuilder()
                        .zipDirectory(missing));
    }

    private static List<String> entryNames(
            byte[] zipBytes) throws Exception {

        List<String> names =
                new ArrayList<>();

        try (ZipInputStream zip =
                     new ZipInputStream(
                             new ByteArrayInputStream(
                                     zipBytes))) {

            var entry = zip.getNextEntry();

            while (entry != null) {

                names.add(
                        entry.getName());

                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }

        return names;
    }
}