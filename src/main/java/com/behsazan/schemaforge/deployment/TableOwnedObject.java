package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Objects;

/** Associates a canonical table-owned object with its owning table for deployment planning. */
public record TableOwnedObject<T>(QualifiedName table, T object) {
    public TableOwnedObject {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(object, "object must not be null");
    }
}
