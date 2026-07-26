package com.behsazan.schemaforge.metadata.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Offline JSON repository. The file is read for every invocation; no metadata cache is retained. */
public final class JsonMetadataProvider implements MetadataRepository {
    private final Path file;
    private final ObjectMapper objectMapper;

    public JsonMetadataProvider(Path file) {
        this(file, new ObjectMapper());
    }

    public JsonMetadataProvider(Path file, ObjectMapper objectMapper) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
        Set<String> requested = MetadataRepositorySupport.normalizeNames(columnNames, true);
        if (requested.isEmpty()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode columns = root.path("columns");
            if (!columns.isArray()) {
                throw new IllegalArgumentException("Metadata JSON must contain an array named 'columns'");
            }
            Map<String, MetadataColumnProfile> profiles = new LinkedHashMap<>();
            for (JsonNode column : columns) {
                String name = column.path("name").asText().trim().toUpperCase(Locale.ROOT);
                if (!requested.contains(name)) continue;
                List<MetadataTypeFrequency> frequencies = new ArrayList<>();
                for (JsonNode type : column.path("types")) {
                    frequencies.add(new MetadataTypeFrequency(
                            type.path("signature").asText(), type.path("frequency").asLong()));
                }
                profiles.put(name, new MetadataColumnProfile(
                        name, column.path("totalFrequency").asLong(0), frequencies));
            }
            return Map.copyOf(profiles);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read metadata JSON: " + file, exception);
        }
    }
}
