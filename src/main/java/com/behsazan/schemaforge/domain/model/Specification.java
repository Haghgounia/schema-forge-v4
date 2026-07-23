package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.time.Instant;
import java.util.*;
public record Specification(UUID id, Identifier name, String sourceFile, Instant importedAt, DatabaseSchema schema, Map<String,String> attributes) {
    public Specification { id = id == null ? UUID.randomUUID() : id; Objects.requireNonNull(name); sourceFile = sourceFile == null ? "" : sourceFile; importedAt = importedAt == null ? Instant.now() : importedAt; Objects.requireNonNull(schema); attributes = attributes == null ? Map.of() : Map.copyOf(attributes); }
}
