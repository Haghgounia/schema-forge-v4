package com.behsazan.schemaforge.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds in-memory ZIP packages from generated artifact directories and owns shared package cleanup helpers.
 *
 * <p>This component deliberately contains no generation or naming policy. It packages only regular
 * non-symbolic-link files located under the supplied artifact directory.</p>
 *
 * <p>ZIP entries are emitted in deterministic package-relative path order and use a fixed ZIP
 * modification timestamp so repeated packaging of identical content produces stable archive
 * structure and, within the same ZIP implementation, stable archive bytes.</p>
 */
public final class ArtifactPackageBuilder {

    /**
     * Earliest normal DOS ZIP timestamp. Using a fixed local timestamp avoids
     * filesystem modification times leaking into generated package bytes.
     */
    private static final LocalDateTime FIXED_ZIP_TIMESTAMP =
            LocalDateTime.of(1980, 1, 1, 0, 0);

    public byte[] zipDirectory(Path directory) throws IOException {

        Objects.requireNonNull(
                directory,
                "directory must not be null");

        Path root = directory
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "ZIP source must be an existing directory: " + root);
        }

        List<Path> files;

        try (var paths = Files.walk(root)) {
            files = paths
                    .filter(path ->
                            Files.isRegularFile(
                                    path,
                                    LinkOption.NOFOLLOW_LINKS))
                    .sorted(
                            Comparator.comparing(
                                            (Path path) ->
                                                    normalizePath(
                                                            root.relativize(path)),
                                            String.CASE_INSENSITIVE_ORDER)
                                    .thenComparing(
                                            path ->
                                                    normalizePath(
                                                            root.relativize(path))))
                    .toList();
        }

        validateUniqueEntryPaths(root, files);

        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();

        try (ZipOutputStream zip =
                     new ZipOutputStream(bytes)) {

            for (Path file : files) {

                String entryName =
                        normalizePath(
                                root.relativize(file));

                ZipEntry entry =
                        new ZipEntry(entryName);

                entry.setTimeLocal(
                        FIXED_ZIP_TIMESTAMP);

                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }

        return bytes.toByteArray();
    }

    public String normalizePath(Path path) {

        Objects.requireNonNull(
                path,
                "path must not be null");

        return path.toString()
                .replace('\\', '/');
    }

    public void deleteRecursively(Path root) {

        if (root == null || !Files.exists(root)) {
            return;
        }

        try (var paths = Files.walk(root)) {

            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Preserve historical best-effort cleanup behavior.
                        }
                    });

        } catch (IOException ignored) {
            // Preserve historical best-effort cleanup behavior.
        }
    }

    private void validateUniqueEntryPaths(
            Path root,
            List<Path> files) {

        Set<String> entryPaths =
                new HashSet<>();

        for (Path file : files) {

            String entryName =
                    normalizePath(
                            root.relativize(file));

            if (entryName.isBlank()) {
                throw new IllegalStateException(
                        "ZIP entry path must not be blank");
            }

            /*
             * Case-insensitive collision detection deliberately matches the
             * package/manifest portability contract and protects extraction
             * on case-insensitive filesystems.
             */
            String key =
                    entryName.toLowerCase(
                            Locale.ROOT);

            if (!entryPaths.add(key)) {
                throw new IllegalStateException(
                        "Duplicate ZIP entry path: "
                                + entryName);
            }
        }
    }
}