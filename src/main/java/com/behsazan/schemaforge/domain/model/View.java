package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.Objects;
public record View(QualifiedName qualifiedName, String query, Description description, boolean materialized) implements SchemaObject {
    public View { Objects.requireNonNull(qualifiedName); query = Objects.requireNonNull(query).trim(); if (query.isEmpty()) throw new IllegalArgumentException("view query must not be blank"); description = description == null ? Description.empty() : description; }
    @Override public ObjectType objectType() { return ObjectType.VIEW; }
}
