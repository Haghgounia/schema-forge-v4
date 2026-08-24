package com.behsazan.schemaforge.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds in-memory ZIP packages from generated artifact directories and owns shared package cleanup helpers.
 *
 * <p>This component deliberately contains no generation or naming policy. It preserves the existing archive
 * entry-path and best-effort cleanup behavior while keeping packaging mechanics out of the API facade.</p>
 */
public final class ArtifactPackageBuilder {

    public byte[] zipDirectory(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(normalizePath(directory.relativize(path))));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    public void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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
}
