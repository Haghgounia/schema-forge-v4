package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.time.Instant;
import java.util.*;
public record Project(UUID id, Identifier code, String title, Description description, Instant createdAt, List<Specification> specifications, Map<String,String> settings) {
    public Project { id = id == null ? UUID.randomUUID() : id; Objects.requireNonNull(code); title = Objects.requireNonNull(title).trim(); if (title.isEmpty()) throw new IllegalArgumentException("project title must not be blank"); description = description == null ? Description.empty() : description; createdAt = createdAt == null ? Instant.now() : createdAt; specifications = specifications == null ? List.of() : List.copyOf(specifications); settings = settings == null ? Map.of() : Map.copyOf(settings); }
}
