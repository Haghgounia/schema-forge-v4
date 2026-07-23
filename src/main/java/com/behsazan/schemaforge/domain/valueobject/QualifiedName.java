package com.behsazan.schemaforge.domain.valueobject;

import java.util.Objects;
import java.util.Optional;

public record QualifiedName(Identifier schema, Identifier name) {
    public QualifiedName {
        Objects.requireNonNull(name, "name must not be null");
    }
    public static QualifiedName of(String schema, String name) {
        return new QualifiedName(schema == null || schema.isBlank() ? null : Identifier.of(schema), Identifier.of(name));
    }
    public Optional<Identifier> schemaName() { return Optional.ofNullable(schema); }
    @Override public String toString() { return schema == null ? name.toString() : schema + "." + name; }
}
