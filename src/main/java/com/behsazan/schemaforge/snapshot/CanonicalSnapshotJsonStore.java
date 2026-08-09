package com.behsazan.schemaforge.snapshot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * UTF-8 JSON reader/writer for canonical snapshots and their manifests.
 *
 * <p>Writes are atomic when the filesystem supports atomic moves, preventing an interrupted run
 * from leaving a half-written cache entry. Unknown JSON fields are tolerated so additive snapshot
 * changes can remain forward-readable while explicit version checks still protect semantics.</p>
 */
public final class CanonicalSnapshotJsonStore {
    private final ObjectMapper objectMapper;

    public CanonicalSnapshotJsonStore() {
        this(new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    CanonicalSnapshotJsonStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Reads one canonical snapshot from disk. */
    public CanonicalSchemaSnapshot readSnapshot(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), CanonicalSchemaSnapshot.class);
    }

    /** Writes one canonical snapshot using an atomic replace where supported. */
    public void writeSnapshot(Path path, CanonicalSchemaSnapshot snapshot) throws IOException {
        write(path, snapshot);
    }

    /** Writes a manifest or report object with the same deterministic JSON settings. */
    public void write(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            String json = objectMapper.writeValueAsString(value) + System.lineSeparator();
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
