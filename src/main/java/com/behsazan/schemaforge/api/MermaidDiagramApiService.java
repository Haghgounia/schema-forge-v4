package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.mermaid.GeneratedMermaidDiagram;
import com.behsazan.schemaforge.diagram.mermaid.MermaidDiagramGenerationService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** API adapter that accepts a canonical snapshot or ZIP and delegates Mermaid generation. */
@Service
public class MermaidDiagramApiService {
    private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 20_000;

    private final MermaidDiagramGenerationService generationService;

    public MermaidDiagramApiService() {
        this(new MermaidDiagramGenerationService());
    }

    MermaidDiagramApiService(MermaidDiagramGenerationService generationService) {
        this.generationService = Objects.requireNonNull(generationService, "generationService must not be null");
    }

    public GeneratedMermaidDiagram generate(MultipartFile file, DiagramExportOptions options) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Canonical diagram input file must not be empty");
        }

        String originalName = safeFileName(file.getOriginalFilename());
        String lower = originalName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".schema.json")) {
            throw new IllegalArgumentException(
                    "Mermaid canonical input must be a *.schema.json file or a ZIP containing canonical snapshots");
        }

        Path work = Files.createTempDirectory("schemaforge-mermaid-api-");
        try {
            Path input;
            if (lower.endsWith(".zip")) {
                input = Files.createDirectories(work.resolve("input"));
                unzipSafely(file, input);
            } else {
                input = work.resolve(originalName);
                try (InputStream stream = file.getInputStream()) {
                    Files.copy(stream, input, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return generationService.generate(input, options);
        } finally {
            deleteRecursively(work);
        }
    }

    private static void unzipSafely(MultipartFile file, Path targetDirectory) throws IOException {
        long totalBytes = 0L;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("ZIP contains too many entries: " + entries);
                }
                Path target = targetDirectory.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDirectory)) {
                    throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zip.closeEntry();
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        totalBytes += read;
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException(
                                    "ZIP uncompressed content exceeds " + MAX_UNCOMPRESSED_BYTES + " bytes");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        if (entries == 0) {
            throw new IllegalArgumentException("ZIP is empty");
        }
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "input.schema.json";
        }
        String normalized = value.trim().replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String fileName = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("Input file name is invalid");
        }
        return fileName;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort temporary cleanup; generation result has already been produced.
                }
            });
        } catch (IOException ignored) {
            // Best-effort temporary cleanup.
        }
    }
}
