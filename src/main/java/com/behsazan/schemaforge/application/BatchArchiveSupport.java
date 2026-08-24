package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.CollisionSafeArtifactTargetAllocator;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Filesystem and ledger support for ZIP-batch ingestion and collision-safe package assembly.
 *
 * <p>This class deliberately owns no generation policy. It preserves the C5 batch path/collision
 * behavior while keeping archive mechanics out of the API facade.</p>
 */
public final class BatchArchiveSupport {

    private BatchArchiveSupport() {
    }

    public static void unzipSafely(MultipartFile file, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    public static List<Path> processableWordDocuments(Path inputDirectory) throws IOException {
        try (var files = Files.walk(inputDirectory)) {
            return files.filter(Files::isRegularFile)
                    .filter(BatchArchiveSupport::isProcessableWordDocument)
                    .sorted(Comparator.comparing(path ->
                            normalizePath(inputDirectory.relativize(path)).toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

    public static long countRegularFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    public static Map<String, String> moveGeneratedFiles(
            Path source,
            Path destination,
            CollisionSafeArtifactTargetAllocator allocator,
            String sourceIdentity) throws IOException {
        Map<String, String> remapped = new LinkedHashMap<>();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path requestedRelative = source.relativize(file);
                Path resolvedRelative = allocator.reserve(
                        requestedRelative, sourceIdentity + "::" + normalizePath(requestedRelative));
                Path target = destination.resolve(resolvedRelative);
                Files.createDirectories(target.getParent());
                Files.move(file, target);
                remapped.put(normalizePath(requestedRelative), normalizePath(resolvedRelative));
            }
        }
        return remapped;
    }

    public static void mergeBatchArtifacts(
            ArtifactGenerationContext documentContext,
            ArtifactGenerationContext batchContext,
            Map<String, String> remappedPaths) {
        for (ArtifactDescriptor descriptor : documentContext.ledger().snapshot()) {
            if (descriptor.status() != ArtifactStatus.GENERATED) {
                batchContext.ledger().add(descriptor);
                continue;
            }
            String remapped = remappedPaths.get(descriptor.relativePath());
            if (remapped == null) {
                throw new IllegalStateException(
                        "Generated artifact was not moved into the batch package: " + descriptor.relativePath());
            }
            batchContext.ledger().add(new ArtifactDescriptor(
                    descriptor.type(),
                    descriptor.platform(),
                    descriptor.logicalName(),
                    remapped,
                    descriptor.mediaType(),
                    descriptor.generationId(),
                    descriptor.status(),
                    descriptor.provenance()));
        }
    }

    public static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String safe = value == null ? "" : value;
            escaped.add("\"" + safe.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    public static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public static void appendBatchError(
            StringBuilder errors, int sequence, String document, Exception exception) {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        errors.append("============================================================\n")
                .append("Sequence : ").append(sequence).append('\n')
                .append("Document : ").append(document).append('\n')
                .append("Error    : ").append(exception.getClass().getName())
                .append(": ").append(safeMessage(exception)).append('\n')
                .append("------------------------------------------------------------\n")
                .append(stackTrace)
                .append('\n');
    }

    private static boolean isProcessableWordDocument(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".docx")) return false;
        if (name.startsWith("~$") || name.startsWith("._") || name.startsWith(".")) return false;
        for (Path segment : path) {
            if ("__MACOSX".equalsIgnoreCase(segment.toString())) return false;
        }
        return true;
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
